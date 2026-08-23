package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitQueryException
import com.submodule.branchswitcher.git.isTermination
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.RepoTarget
import java.io.File

/** Subject prefix of every stash this plugin creates; used to locate them reliably. */
internal const val STASH_MESSAGE_PREFIX = "branch-switcher: before -> "

/** Result of probing one target's dirty state; the callers only receive it for a dirty repo. */
internal data class DirtyFacts(val submoduleOnlyDirty: Boolean)

/** The updated state and whether dirty handling marked the target skipped. */
internal data class DirtyTargetOutcome(val state: SwitchState, val skipped: Boolean)

/**
 * Probes [repositoryDirectory] for dirty state. Returns null when the directory is missing,
 * not a git repository, or clean; otherwise the facts dirty handling needs. Uses the one
 * [RepositoryStateGitClient.inspectRepositoryState] code path (an implementation with a
 * single-invocation read avoids a second status process per target).
 */
internal fun inspectDirtyState(
    context: SwitchContext,
    repositoryDirectory: File,
): DirtyFacts? {
    if (!repositoryDirectory.exists()) return null
    val inspection = context.git.inspectRepositoryState(repositoryDirectory)
    if (!inspection.isGitRepository) return null
    if (inspection.dirtyFileCount <= 0) return null
    return DirtyFacts(inspection.submoduleOnlyDirty)
}

/**
 * Applies the configured dirty strategy (stash, skip, or attempt checkout without stashing)
 * to one already-known-dirty target, shared by the main-repo dirty step and the
 * topology-confirmed submodule flow.
 */
internal fun handleTargetDirtyState(
    context: SwitchContext,
    target: RepoTarget,
    directory: File,
    facts: DirtyFacts,
    state: SwitchState,
    issues: MutableList<OperationIssue>,
    stage: OperationStage = OperationStage.DIRTY_HANDLING,
): DirtyTargetOutcome = when (context.options.dirty) {
    DirtyAction.Skip -> {
        context.log.info("[skip] working tree dirty - ${target.path}")
        issues += OperationIssue(stage, OperationIssueCode.WORKTREE_DIRTY, target.path)
        DirtyTargetOutcome(state.withSkipped(target.path), skipped = true)
    }
    DirtyAction.Stash -> stashTarget(context, target, directory, facts.submoduleOnlyDirty, state, issues, stage)
    DirtyAction.Force -> {
        context.log.info("[no stash] proceeding with dirty tree - ${target.path}")
        DirtyTargetOutcome(state, skipped = false)
    }
}

private fun stashTarget(
    context: SwitchContext,
    target: RepoTarget,
    repositoryDirectory: File,
    submoduleOnlyDirty: Boolean,
    state: SwitchState,
    issues: MutableList<OperationIssue>,
    stage: OperationStage,
): DirtyTargetOutcome {
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
        return DirtyTargetOutcome(state, skipped = false)
    }
    // The message carries the operation's opaque id (never the branch or a repository/file
    // path), so a retained WIP stash from an earlier execution can never be matched by
    // message and applied to this switch.
    val stashMessage = "$STASH_MESSAGE_PREFIX${context.operationId}"
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
        // A terminated push may have written refs/stash before dying; track any entry
        // it created so recovery can apply it instead of leaving a torn stash.
        if (stashResult.failureKind.isTermination) {
            trackGhostStashIfCreated(
                context, target, repositoryDirectory, beforeTop, stashMessage, state,
                StashPurpose.WIP_RESTORE_AFTER_SWITCH,
            )?.let { return DirtyTargetOutcome(it, skipped = false) }
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
        return DirtyTargetOutcome(state.withSkipped(target.path), skipped = true)
    }
    val stashOid = try {
        // Locate our stash by its unique per-operation message: an external `git stash push`
        // racing in, or a retained stash from an earlier operation, must never be mistaken
        // for this switch's WIP. There is no fallback to the arbitrary stack top.
        context.git.stashOidByMessage(repositoryDirectory, stashMessage)
    } catch (error: GitQueryException) {
        return unidentifiedStash(context, target, state, issues, stage, stashMessage, error.result.diagnostic())
    }
    if (stashOid == null) {
        return unidentifiedStash(context, target, state, issues, stage, stashMessage)
    }
    context.log.info("stash: ok (${target.path}, oid=$stashOid)")
    return DirtyTargetOutcome(
        state.withTrackedStash(
            target.path, StashPurpose.WIP_RESTORE_AFTER_SWITCH, stashMessage, stashOid,
        ),
        skipped = false,
    )
}

/**
 * After a terminated stash push, locates any entry the write actually created and
 * tracks it for recovery, tagged with [purpose]. Only the newest entry carrying our
 * message prefix is accepted: the top must have advanced AND the newest entry must match,
 * so an old backup or a concurrent external stash is never mistaken for this operation's
 * entry.
 *
 * Returns the state to return immediately, or null when there is no ghost entry.
 */
internal fun trackGhostStashIfCreated(
    context: SwitchContext,
    target: RepoTarget,
    repositoryDirectory: File,
    beforeTop: String?,
    stashMessage: String,
    state: SwitchState,
    purpose: StashPurpose,
    approvedPaths: Set<String> = emptySet(),
): SwitchState? {
    val ghostOid = try {
        val top = context.git.stashTopOid(repositoryDirectory)
        if (top == null || top == beforeTop) null
        else context.git.stashOidByMessage(repositoryDirectory, stashMessage)
    } catch (error: GitQueryException) {
        context.log.warn(
            "stash: could not inspect entries after a terminated push (${target.path}); " +
                "a torn stash entry may remain untracked: " + error.result.diagnostic(),
        )
        null
    }
    if (ghostOid == null) return null
    context.log.warn(
        "stash: terminated mid-write but entry created (${target.path}, oid=$ghostOid); " +
            "tracked for recovery",
    )
    return state.withTrackedStash(target.path, purpose, stashMessage, ghostOid, approvedPaths)
}

private fun unidentifiedStash(
    context: SwitchContext,
    target: RepoTarget,
    state: SwitchState,
    issues: MutableList<OperationIssue>,
    stage: OperationStage,
    stashMessage: String,
    diagnostic: String? = null,
): DirtyTargetOutcome {
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
    return DirtyTargetOutcome(
        state.withTrackedStash(
            target.path, StashPurpose.WIP_RESTORE_AFTER_SWITCH, stashMessage, oid = null,
        ).withSkipped(target.path),
        skipped = true,
    )
}
