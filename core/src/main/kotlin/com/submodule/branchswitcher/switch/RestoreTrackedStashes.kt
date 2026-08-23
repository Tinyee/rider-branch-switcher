package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.IndexLockBlockedException
import com.submodule.branchswitcher.git.SwitchGitClient
import com.submodule.branchswitcher.git.isTermination
import com.submodule.branchswitcher.log.AppLogger
import java.io.File
import java.nio.file.Path

/**
 * Restores tracked stashes, drops entries that applied cleanly, and retains failed
 * entries so a later recovery can retry them.
 *
 * Entries are restored in reverse creation order (WIP before approved). Per the
 * restore/drop matrix, an APPROVED_DISCARD stash is dropped WITHOUT applying — the discard
 * is authorized — when [discardApprovedFor] says its repository reached the target and will
 * not be rolled back; otherwise it is applied back (the switch did not happen for that repo,
 * or recovery rolled it back) and then dropped.
 *
 * [control] is polled before every repository so a user cancel stops the loop. An apply
 * that actually started and was terminated (cancelled/interrupted/timed out) may have
 * partially modified the worktree, so it is marked attempted and never re-applied
 * automatically; only a failure proven to occur before Git started (an index.lock block) is
 * retryable.
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount") // preserve successfully restored entries if a later Git query fails
internal fun restoreTrackedStashes(
    projectRoot: Path,
    git: SwitchGitClient,
    log: AppLogger,
    state: SwitchState,
    selectedPaths: Set<String>? = null,
    control: OperationControl? = null,
    discardApprovedFor: (String) -> Boolean = { false },
): StashRestoreResult {
    val issues = mutableListOf<OperationIssue>()
    var nextState = state
    var interrupted = false
    try {
        for (stash in state.stashesSnapshot().sortedByDescending { it.creationOrder }) {
            if (control?.isCanceled == true) {
                // Stop restoring on cancel; the remaining entries stay tracked and
                // retryable (no apply started for them). Callers use [interrupted]
                // to avoid automatically retrying after an explicit user cancel.
                interrupted = true
                break
            }
            val path = stash.repositoryPath
            if (selectedPaths != null && path !in selectedPaths) continue
            val repositoryDirectory = resolveGitDir(projectRoot, path)
            if (stash.purpose == StashPurpose.APPROVED_DISCARD && discardApprovedFor(path)) {
                // The repo switched and will not be rolled back: the discard is authorized,
                // so drop the isolated copy without applying it. The authorization is
                // re-verified against the actual checked-out tree; a drop failure or a drift
                // only leaves a backup and must not fail the switch.
                val discarded = discardApprovedStash(git, log, path, stash, repositoryDirectory, issues)
                nextState = if (discarded) nextState.withStashRestored(stash.id)
                else nextState.withRestoredStashBackup(stash.id)
                continue
            }
            val guardIssue = restoreGuard(git, log, path, stash, repositoryDirectory)
            if (guardIssue != null) {
                issues += guardIssue
                continue
            }
            val outcome = applyRestoredStash(
                git,
                log,
                nextState,
                stash,
                repositoryDirectory,
                issues,
                control,
            )
            nextState = outcome.state
            if (outcome.interrupted) interrupted = true
            if (outcome.stop) break
        }
    } catch (e: SwitchStepException) {
        val lock = e.cause as? IndexLockBlockedException
        if (lock == null) throw e
        issues += OperationIssue(
            stage = OperationStage.STASH_RESTORE,
            code = OperationIssueCode.INDEX_LOCK_BLOCKING,
            repositoryPath = repositoryPathFor(projectRoot, e.latestState, lock.workDir),
            severity = OperationIssueSeverity.ERROR,
            diagnostic = indexLockBlockedDiagnostic(lock.lockPath),
            lockPath = lock.lockPath,
        )
        return StashRestoreResult(e.latestState, issues)
    } catch (e: RuntimeException) {
        throw SwitchStepException(nextState, e)
    }
    return StashRestoreResult(nextState, issues, interrupted = interrupted)
}

/**
 * Maps a locked repository directory back to the tracked stash path it belongs to, for the
 * issue's display path. Falls back to "." when the work directory is not one of the tracked
 * repositories (a stale lock on a repo with no tracked stash).
 */
private fun repositoryPathFor(projectRoot: Path, state: SwitchState, workDir: File): String {
    val canonical = runCatching { workDir.canonicalFile }.getOrNull() ?: workDir
    return state.stashesSnapshot()
        .map(TrackedStash::repositoryPath)
        .firstOrNull { path ->
            runCatching { resolveGitDir(projectRoot, path).canonicalFile }.getOrNull() == canonical
        }
        ?: "."
}

/**
 * Returns the issue that must block restoring [stash] into [repositoryDirectory],
 * or null when the apply may proceed. Runs before any state mutation.
 */
private fun restoreGuard(
    git: SwitchGitClient,
    log: AppLogger,
    path: String,
    stash: TrackedStash,
    repositoryDirectory: File,
): OperationIssue? {
    if (stash.restoreAttempted) {
        log.warn("[fail] stash restore not retried for $path (${repositoryDirectory.path}); a previous apply may have changed the worktree")
        return OperationIssue(
            stage = OperationStage.STASH_RESTORE,
            code = OperationIssueCode.STASH_RESTORE_FAILED,
            repositoryPath = path,
            severity = OperationIssueSeverity.ERROR,
            diagnostic = "restore already attempted; recovery backup retained for manual inspection",
        )
    }
    if (stash.oid == null) {
        log.error("[fail] stash restore skipped - identity unavailable for $path (${repositoryDirectory.path}) (${stash.message})")
        return OperationIssue(
            stage = OperationStage.STASH_RESTORE,
            code = OperationIssueCode.STASH_IDENTITY_UNAVAILABLE,
            repositoryPath = path,
            severity = OperationIssueSeverity.ERROR,
        )
    }
    if (!repositoryDirectory.exists() || !git.isGitRepo(repositoryDirectory)) {
        log.warn("[fail] stash apply skipped - repository unavailable for $path (${repositoryDirectory.path}) (${stash.message})")
        return OperationIssue(
            stage = OperationStage.STASH_RESTORE,
            code = OperationIssueCode.STASH_REPOSITORY_UNAVAILABLE,
            repositoryPath = path,
        )
    }
    val existingLock = git.indexLockFile(repositoryDirectory)
    if (existingLock != null) {
        log.warn(
            "[fail] stash apply skipped - stale index.lock at $existingLock; " +
                "delete it and retry (${stash.message})",
        )
        return OperationIssue(
            stage = OperationStage.STASH_RESTORE,
            code = OperationIssueCode.INDEX_LOCK_BLOCKING,
            repositoryPath = path,
            diagnostic = indexLockBlockedDiagnostic(existingLock),
            lockPath = existingLock,
        )
    }
    return null
}

/**
 * Outcome of applying one stash entry: the updated state and whether to stop the loop.
 * [interrupted] distinguishes a termination caused by an explicit user cancel (which
 * must suppress the automatic retry) from a plain timeout termination.
 */
private data class RestoreApplyOutcome(
    val state: SwitchState,
    val stop: Boolean = false,
    val interrupted: Boolean = false,
)

/**
 * Applies [stash] into [repositoryDirectory] and records the outcome. A lock race or
 * generic apply failure throws [SwitchStepException] carrying the latest state, so the
 * caller must use the carried state rather than its own when it catches one.
 */
@Suppress("TooGenericExceptionCaught") // any apply failure must stay at-most-once, whatever the cause
private fun applyRestoredStash(
    git: SwitchGitClient,
    log: AppLogger,
    state: SwitchState,
    stash: TrackedStash,
    repositoryDirectory: File,
    issues: MutableList<OperationIssue>,
    control: OperationControl?,
): RestoreApplyOutcome {
    val path = stash.repositoryPath
    // restoreGuard only lets this run when the stash has an identity.
    val oid = stash.oid!!
    var current = state
    val applyResult = try {
        git.stashApply(repositoryDirectory, oid)
    } catch (error: IndexLockBlockedException) {
        // The git index-mutation funnel re-checked the index.lock and threw BEFORE Git
        // started, so the apply provably never ran: safe to retry after the lock is removed.
        current = current.withStashRestoreRetryable(stash.id)
        throw SwitchStepException(current, error)
    } catch (error: RuntimeException) {
        // Other exceptions may have reached Git and must remain at-most-once.
        current = current.withStashRestoreAttempted(stash.id)
        throw SwitchStepException(current, error)
    }
    if (applyResult.ok) {
        log.info("stash apply ok (${stash.message}, oid=$oid)")
        // Drop the applied entry so refs/stash does not accumulate one backup
        // per switch. A drop failure is only a leftover backup, not a restore
        // failure, so it is tracked for the manual-recovery notice.
        val dropped = dropRestoredStash(git, repositoryDirectory, oid, path, log)
        return RestoreApplyOutcome(
            if (dropped) current.withStashRestored(stash.id) else current.withRestoredStashBackup(stash.id),
        )
    }
    // Termination takes precedence over a raced lock: a terminated apply may have
    // created its own index.lock before dying, and misreading that lock as "git never
    // started" would mark the entry retryable and double-apply a partially-restored
    // worktree. Only a non-terminated apply failure with a lock present is a true race.
    if (applyResult.failureKind.isTermination) {
        // A cancelled/interrupted/timed-out apply may have PARTIALLY modified the worktree
        // before dying, so it must never be re-applied automatically: mark the entry
        // attempted (at-most-once) and keep the stash for the user or an explicit recovery.
        current = current.withStashRestoreAttempted(stash.id)
        log.warn(
            "[fail] stash apply interrupted for $path (${repositoryDirectory.path}): ${applyResult.failureKind}; " +
                "WIP preserved in stash (${stash.message})",
        )
        issues += OperationIssue(
            stage = OperationStage.STASH_RESTORE,
            code = OperationIssueCode.STASH_RESTORE_FAILED,
            repositoryPath = path,
            diagnostic = "restore interrupted by termination; WIP preserved in stash " +
                "(${stash.message}, oid=$oid)",
        )
        return RestoreApplyOutcome(current, stop = true, interrupted = control?.isCanceled == true)
    }
    // A lock created between the earlier check and the apply must surface as
    // the structured lock block, not a generic stash-apply failure.
    val racedLock = git.indexLockFile(repositoryDirectory)
    if (racedLock != null) {
        // The preflight check proved the tree was unlocked, the apply did not terminate,
        // and the follow-up proved the failure was the lock race. No Git apply started,
        // so this entry remains safe to retry.
        current = current.withStashRestoreRetryable(stash.id)
        log.warn(
            "[fail] stash apply blocked by stale index.lock at $racedLock; " +
                "delete it and retry (${stash.message})",
        )
        issues += OperationIssue(
            stage = OperationStage.STASH_RESTORE,
            code = OperationIssueCode.INDEX_LOCK_BLOCKING,
            repositoryPath = path,
            diagnostic = indexLockBlockedDiagnostic(racedLock),
            lockPath = racedLock,
        )
    } else {
        current = current.withStashRestoreAttempted(stash.id)
        log.warn("[fail] stash apply failed for $path (${repositoryDirectory.path}): ${applyResult.diagnostic()}")
        issues += OperationIssue(
            stage = OperationStage.STASH_RESTORE,
            code = OperationIssueCode.STASH_RESTORE_FAILED,
            repositoryPath = path,
            diagnostic = applyResult.diagnostic(),
        )
    }
    return RestoreApplyOutcome(current)
}

/**
 * Best-effort `git stash drop` after a successful apply; false leaves a backup behind.
 * The drop must never fail the restore, so any git failure degrades to a retained backup.
 */
@Suppress("TooGenericExceptionCaught") // a failed drop only retains a backup; it must never fail the restore
private fun dropRestoredStash(
    git: SwitchGitClient,
    repositoryDirectory: File,
    oid: String,
    path: String,
    log: AppLogger,
): Boolean = try {
    val drop = git.stashDrop(repositoryDirectory, oid)
    if (drop.ok) {
        log.info("stash drop ok ($path, oid=$oid)")
        true
    } else {
        log.warn("[fail] stash drop failed for $path (${repositoryDirectory.path}): ${drop.diagnostic()}")
        false
    }
} catch (error: RuntimeException) {
    log.warn("[fail] stash drop failed for $path (${repositoryDirectory.path}): ${error.javaClass.simpleName}: ${error.message}")
    false
}

/**
 * Best-effort `git stash drop` of an APPROVED_DISCARD stash whose repo reached the target;
 * false leaves the backup behind. Never applies the stash — the discard is authorized.
 *
 * The authorization is re-verified against the ACTUAL checked-out HEAD tree: if the target
 * ref moved after the collision validation and the tree no longer conflicts with the
 * approved paths, dropping would permanently discard a file the checkout no longer replaced.
 * The drop runs only when every approved path still structurally collides; on drift or
 * uncertainty the stash is retained and an issue is reported (never a complex partial
 * restore).
 */
@Suppress("TooGenericExceptionCaught") // a failed drop only retains a backup; it must never fail the switch
private fun discardApprovedStash(
    git: SwitchGitClient,
    log: AppLogger,
    path: String,
    stash: TrackedStash,
    repositoryDirectory: File,
    issues: MutableList<OperationIssue>,
): Boolean {
    val oid = stash.oid
    if (oid == null) {
        log.warn("[fail] approved stash drop skipped - identity unavailable for $path (${repositoryDirectory.path}) (${stash.message})")
        return false
    }
    if (!repositoryDirectory.exists() || !git.isGitRepo(repositoryDirectory)) {
        log.warn("[fail] approved stash drop skipped - repository unavailable for $path (${repositoryDirectory.path}) (${stash.message})")
        return false
    }
    val approvedPaths = stash.approvedPaths
    if (approvedPaths.isEmpty()) {
        log.warn("[fail] approved stash drop skipped - approved paths unavailable for $path (${repositoryDirectory.path}) (${stash.message})")
        issues += declinedApprovedDrop(path, stash, "approved paths are unknown; cannot verify the checked-out tree still conflicts")
        return false
    }
    val stillColliding = try {
        git.headStructuralCollisions(repositoryDirectory, approvedPaths.toList()).toSet()
    } catch (error: RuntimeException) {
        log.warn(
            "[fail] approved stash drop skipped - cannot re-verify the checked-out tree for $path " +
                "(${repositoryDirectory.path}): ${error.message} (${stash.message})",
        )
        issues += declinedApprovedDrop(path, stash, "cannot verify the checked-out tree still conflicts")
        return false
    }
    if (stillColliding != approvedPaths) {
        val drifted = approvedPaths - stillColliding
        log.warn(
            "[fail] approved stash drop skipped - ${drifted.size} approved path(s) no longer conflict " +
                "with the checked-out tree for $path; retained as backup (${stash.message})",
        )
        issues += declinedApprovedDrop(
            path,
            stash,
            "no longer conflict with the checked-out tree: ${drifted.sorted().joinToString(", ")}",
        )
        return false
    }
    return dropRestoredStash(git, repositoryDirectory, oid, path, log)
}

private fun declinedApprovedDrop(path: String, stash: TrackedStash, reason: String): OperationIssue =
    OperationIssue(
        stage = OperationStage.STASH_RESTORE,
        code = OperationIssueCode.UNTRACKED_DISCARD_FAILED,
        repositoryPath = path,
        severity = OperationIssueSeverity.ERROR,
        diagnostic = "approved stash retained: $reason (${stash.message})",
    )
