package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.SwitchGitClient
import com.submodule.branchswitcher.git.isTermination
import com.submodule.branchswitcher.log.AppLogger
import java.io.File
import java.nio.file.Path

/**
 * Restores tracked stashes, drops entries that applied cleanly, and retains failed
 * entries so a later recovery can retry them.
 *
 * [cancelled] is polled before every repository so a user cancel stops the loop
 * without marking the interrupted entry as at-most-once: a cancelled apply never
 * started, so the WIP stays safe in the stash and remains retryable.
 */
@Suppress("TooGenericExceptionCaught", "ThrowsCount") // preserve successfully restored entries if a later Git query fails
internal fun restoreTrackedStashes(
    projectRoot: Path,
    git: SwitchGitClient,
    log: AppLogger,
    state: SwitchState,
    selectedPaths: Set<String>? = null,
    cancelled: (() -> Boolean)? = null,
): StashRestoreResult {
    val issues = mutableListOf<OperationIssue>()
    var nextState = state
    var interrupted = false
    try {
        for ((path, stash) in state.stashesSnapshot()) {
            if (cancelled?.invoke() == true) {
                // Stop restoring on cancel; the remaining entries stay tracked and
                // retryable (no apply started for them). Callers use [interrupted]
                // to avoid automatically retrying after an explicit user cancel.
                interrupted = true
                break
            }
            if (selectedPaths != null && path !in selectedPaths) continue
            val repositoryDirectory = resolveGitDir(projectRoot, path)
            val guardIssue = restoreGuard(git, log, path, stash, repositoryDirectory)
            if (guardIssue != null) {
                issues += guardIssue
                continue
            }
            val outcome = applyRestoredStash(
                git,
                log,
                nextState,
                path,
                stash,
                repositoryDirectory,
                issues,
                cancelled,
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
            repositoryPath = lock.repositoryPath,
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
    path: String,
    stash: TrackedStash,
    repositoryDirectory: File,
    issues: MutableList<OperationIssue>,
    cancelled: (() -> Boolean)?,
): RestoreApplyOutcome {
    // restoreGuard only lets this run when the stash has an identity.
    val oid = stash.oid!!
    var current = state
    val applyResult = try {
        git.stashApply(repositoryDirectory, oid)
    } catch (error: IndexLockBlockedException) {
        // WriteGuard can observe a lock after the initial probe and throw
        // before Git starts. This is safe to retry after the lock is removed.
        current = current.withStashRestoreRetryable(path)
        throw SwitchStepException(current, error)
    } catch (error: RuntimeException) {
        // Other exceptions may have reached Git and must remain at-most-once.
        current = current.withStashRestoreAttempted(path)
        throw SwitchStepException(current, error)
    }
    if (applyResult.ok) {
        log.info("stash apply ok (${stash.message}, oid=$oid)")
        // Drop the applied entry so refs/stash does not accumulate one backup
        // per switch. A drop failure is only a leftover backup, not a restore
        // failure, so it is tracked for the manual-recovery notice.
        val dropped = dropRestoredStash(git, repositoryDirectory, oid, path, log)
        return RestoreApplyOutcome(
            if (dropped) current.withStashRestored(path) else current.withRestoredStashBackup(path),
        )
    }
    // Termination takes precedence over a raced lock: a terminated apply may have
    // created its own index.lock before dying, and misreading that lock as "git never
    // started" would mark the entry retryable and double-apply a partially-restored
    // worktree. Only a non-terminated apply failure with a lock present is a true race.
    if (applyResult.failureKind.isTermination) {
        // A cancelled/interrupted apply is a stop signal, not a failed
        // restore: the command died before completing, so the entry stays
        // tracked and retryable and the WIP is preserved in the stash.
        current = current.withStashRestoreRetryable(path)
        log.warn(
            "[fail] stash apply interrupted for $path (${repositoryDirectory.path}): ${applyResult.failureKind}; " +
                "WIP preserved in stash (${stash.message})",
        )
        issues += OperationIssue(
            stage = OperationStage.STASH_RESTORE,
            code = OperationIssueCode.STASH_RESTORE_FAILED,
            repositoryPath = path,
            diagnostic = "restore interrupted by cancellation; WIP preserved in stash " +
                "(${stash.message}, oid=$oid)",
        )
        return RestoreApplyOutcome(current, stop = true, interrupted = cancelled?.invoke() == true)
    }
    // A lock created between the earlier check and the apply must surface as
    // the structured lock block, not a generic stash-apply failure.
    val racedLock = git.indexLockFile(repositoryDirectory)
    if (racedLock != null) {
        // The preflight check proved the tree was unlocked, the apply did not terminate,
        // and the follow-up proved the failure was the lock race. No Git apply started,
        // so this entry remains safe to retry.
        current = current.withStashRestoreRetryable(path)
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
        current = current.withStashRestoreAttempted(path)
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
