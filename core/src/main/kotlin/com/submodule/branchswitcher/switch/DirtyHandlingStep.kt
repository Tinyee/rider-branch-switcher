package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.git.isTermination
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.RepoTarget
import java.io.File

/** Subject prefix of every stash this plugin creates; used to locate them reliably. */
internal const val STASH_MESSAGE_PREFIX = "branch-switcher: before -> "

/**
 * For each target with a dirty working tree, apply the configured strategy
 * (stash, skip, or attempt checkout without stashing).
 */
class DirtyHandlingStep : SwitchStep {
    override val name = "dirty handling"
    override val stage = OperationStage.DIRTY_HANDLING

    @Suppress("TooGenericExceptionCaught") // preserve state for cancellation and Git query failures
    override fun execute(context: SwitchContext, state: SwitchState): StepExecution {
        val issues = mutableListOf<OperationIssue>()
        var nextState = state
        try {
            val targets = context.preset.targets()
            for ((index, target) in targets.withIndex()) {
                updateProgress(context, index, targets.size, target.path)
                context.cancellationHandle?.checkCanceled()
                val repositoryDirectory = resolveGitDir(context.projectRoot, target.path)
                if (!repositoryDirectory.exists()) continue
                // Use the batch inspection's own repository fact to skip non-repos and
                // avoid a second process per target (isGitRepo runs `rev-parse`). The
                // capability is nullable: write-guard wrappers forward to their delegate
                // and null means the client chain lacks the batch read, so the fallback
                // per-query probes below are used instead.
                val inspection = (context.git as? com.submodule.branchswitcher.git.RepositoryStateBatchInspection)
                    ?.inspectRepositoryStateIfAvailable(repositoryDirectory)
                if (inspection?.isGitRepository == false ||
                    (inspection == null && !context.git.isGitRepo(repositoryDirectory))
                ) {
                    continue
                }
                val dirty = inspection?.dirtyFileCount?.let { it > 0 } ?: context.git.isDirty(repositoryDirectory)
                if (!dirty) continue
                val submoduleOnlyDirty = inspection?.submoduleOnlyDirty
                    ?: context.git.isSubmoduleOnlyDirty(repositoryDirectory)
                nextState = handleDirtyTarget(
                    context,
                    target,
                    repositoryDirectory,
                    submoduleOnlyDirty,
                    nextState,
                    issues,
                )
            }
        } catch (e: RuntimeException) {
            throw SwitchStepException(nextState, e)
        }
        val result = if (issues.isEmpty()) StepResult.Success else StepResult.Partial(issues)
        return StepExecution(result, nextState)
    }

    private fun handleDirtyTarget(
        context: SwitchContext,
        target: RepoTarget,
        repositoryDirectory: File,
        submoduleOnlyDirty: Boolean,
        state: SwitchState,
        issues: MutableList<OperationIssue>,
    ): SwitchState = when (context.options.dirty) {
        DirtyAction.Skip -> {
            context.log.info("[skip] working tree dirty - ${target.path}")
            issues += OperationIssue(stage, OperationIssueCode.WORKTREE_DIRTY, target.path)
            state.withSkipped(target.path)
        }
        DirtyAction.Stash -> stashTarget(context, target, repositoryDirectory, submoduleOnlyDirty, state, issues)
        DirtyAction.Force -> {
            context.log.info("[no stash] proceeding with dirty tree - ${target.path}")
            state
        }
    }

    private fun stashTarget(
        context: SwitchContext,
        target: RepoTarget,
        repositoryDirectory: File,
        submoduleOnlyDirty: Boolean,
        state: SwitchState,
        issues: MutableList<OperationIssue>,
    ): SwitchState {
        // Stash regardless of whether the repo is already on the target branch:
        // submodule switches still pull after checkout (SubmoduleTreeStep.pullIfEnabled
        // has no branch guard), so an unprotected dirty tree would be pulled anyway.
        // Treating on-target dirt the same as any other dirt keeps the chosen Stash
        // policy consistent. Skip and Force already apply uniformly.
        if (submoduleOnlyDirty) {
            // git stash ignores submodules, so this dirt cannot be stashed (git
            // reports "no local changes to save" and creates nothing). There is
            // no work here to protect: the checkout rewrites only gitlinks and
            // the submodule switch steps realign the submodule worktrees.
            context.log.info(
                "[stash skip] ${target.path} - only submodule changes, not stashable by git; " +
                    "proceeding without stash",
            )
            return state
        }
        val stashMessage = "$STASH_MESSAGE_PREFIX${target.branch}"
        // Snapshot the stash stack top before the push so a terminated push can be told
        // apart from a pre-existing entry (an old backup or an external stash) that must
        // never be mistaken for the current WIP.
        val beforeTop = try {
            context.git.stashTopOid(repositoryDirectory)
        } catch (error: GitQueryException) {
            context.log.warn(
                "stash: could not read stash top before push (${target.path}); " +
                    "ghost-stash detection after a terminated push will be less precise: " +
                    error.result.diagnostic(),
            )
            null
        }
        val stashResult = context.git.stash(repositoryDirectory, stashMessage)
        if (!stashResult.ok) {
            // A terminated stash push (cancel / timeout / interruption) may have written
            // refs/stash before dying, leaving WIP split between the stash and the tree.
            // Track any entry that did appear so recovery can still apply it instead of
            // leaving a "torn" stash nobody owns.
            if (stashResult.failureKind.isTermination) {
                val ghostOid = try {
                    val top = context.git.stashTopOid(repositoryDirectory)
                    // Only an entry the terminated push actually created is tracked:
                    // the top must have advanced AND the newest entry must carry our
                    // message prefix. Falling back to any other top would apply an old
                    // backup or a concurrent external stash onto the current tree.
                    if (top == null || top == beforeTop) null
                    else context.git.stashOidByMessage(repositoryDirectory, stashMessage)
                } catch (error: GitQueryException) {
                    context.log.warn(
                        "stash: could not inspect entries after a terminated push (${target.path}); " +
                            "a torn stash entry may remain untracked: " + error.result.diagnostic(),
                    )
                    null
                }
                if (ghostOid != null) {
                    context.log.warn(
                        "stash: terminated mid-write but entry created (${target.path}, oid=$ghostOid); " +
                            "tracked for recovery",
                    )
                    return state.withTrackedStash(target.path, "before -> ${target.branch}", ghostOid)
                }
            }
            val lockHint = context.git.indexLockFile(repositoryDirectory)?.let { lock ->
                " [index.lock exists at $lock; if no other git process is running, delete it and retry]"
            }.orEmpty()
            val diagnostic = "${stashResult.diagnostic()}$lockHint"
            context.log.warn("stash: FAIL (${target.path}): $diagnostic")
            issues += OperationIssue(
                stage,
                OperationIssueCode.STASH_FAILED,
                target.path,
                diagnostic = diagnostic,
            )
            return state.withSkipped(target.path)
        }
        val stashOid = try {
            // Locate our stash by message prefix: an external `git stash push` racing in
            // between stash and lookup must not make recovery apply the wrong entry.
            context.git.stashOidByMessage(repositoryDirectory, stashMessage)
                ?: context.git.stashTopOid(repositoryDirectory)
        } catch (error: GitQueryException) {
            return unidentifiedStash(context, target, state, issues, error.result.diagnostic())
        }
        if (stashOid == null) {
            return unidentifiedStash(context, target, state, issues)
        }
        context.log.info("stash: ok (${target.path}, oid=$stashOid)")
        return state.withTrackedStash(target.path, "before -> ${target.branch}", stashOid)
    }

    private fun unidentifiedStash(
        context: SwitchContext,
        target: RepoTarget,
        state: SwitchState,
        issues: MutableList<OperationIssue>,
        diagnostic: String? = null,
    ): SwitchState {
        context.log.error(
            "stash created but its object id could not be read (${target.path})" +
                diagnostic?.let { ": $it" }.orEmpty(),
        )
        issues += OperationIssue(
            stage,
            OperationIssueCode.STASH_IDENTITY_UNAVAILABLE,
            target.path,
            severity = OperationIssueSeverity.ERROR,
            diagnostic = diagnostic,
        )
        return state
            .withTrackedStash(target.path, "before -> ${target.branch}", oid = null)
            .withSkipped(target.path)
    }

    private fun updateProgress(context: SwitchContext, index: Int, total: Int, path: String) {
        context.progressHandle?.updateProgress(index, total, context.projectRoot, path)
    }
}
