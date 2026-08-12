package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.RepoTarget
import java.io.File

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
                if (!repositoryDirectory.exists() || !context.git.isGitRepo(repositoryDirectory)) continue
                val inspection = when (val client = context.git) {
                    is com.submodule.branchswitcher.git.RepositoryStateBatchGitClient ->
                        client.inspectRepositoryState(repositoryDirectory)
                    is WriteGuardGitClient -> client.inspectRepositoryStateIfAvailable(repositoryDirectory)
                    else -> null
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
        if (context.git.currentBranch(repositoryDirectory) == target.branch) {
            context.log.info("already on '${target.branch}', no stash needed")
            return state
        }
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
        val stashResult = context.git.stash(
            repositoryDirectory,
            "branch-switcher: before -> ${target.branch}",
        )
        if (!stashResult.ok) {
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
            context.git.stashTopOid(repositoryDirectory)
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
        context.progressHandle?.apply {
            fraction = index.toDouble() / total
            text2 = if (path == ".") context.projectRoot.fileName.toString() else path
        }
    }
}
