package com.submodule.branchswitcher.switch

import com.submodule.branchswitcher.git.GitFailureKind
import com.submodule.branchswitcher.git.SwitchGitClient
import com.submodule.branchswitcher.log.AppLogger
import java.io.File
import java.nio.file.Path

data class StashRestoreResult(
    val state: SwitchState,
    val issues: List<OperationIssue>,
)

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
    try {
        for ((path, stash) in state.stashesSnapshot()) {
            if (cancelled?.invoke() == true) {
                // Stop restoring on cancel; the remaining entries stay tracked and
                // retryable (no apply started for them).
                break
            }
            if (selectedPaths != null && path !in selectedPaths) continue
            val repositoryDirectory = resolveGitDir(projectRoot, path)
            if (stash.restoreAttempted) {
                log.warn("[fail] stash restore not retried for $path; a previous apply may have changed the worktree")
                issues += OperationIssue(
                    stage = OperationStage.STASH_RESTORE,
                    code = OperationIssueCode.STASH_RESTORE_FAILED,
                    repositoryPath = path,
                    severity = OperationIssueSeverity.ERROR,
                    diagnostic = "restore already attempted; recovery backup retained for manual inspection",
                )
                continue
            }
            if (stash.oid == null) {
                log.error("[fail] stash restore skipped - identity unavailable for $path (${stash.message})")
                issues += OperationIssue(
                    stage = OperationStage.STASH_RESTORE,
                    code = OperationIssueCode.STASH_IDENTITY_UNAVAILABLE,
                    repositoryPath = path,
                    severity = OperationIssueSeverity.ERROR,
                )
                continue
            }
            if (!repositoryDirectory.exists() || !git.isGitRepo(repositoryDirectory)) {
                log.warn("[fail] stash apply skipped - repository unavailable for $path (${stash.message})")
                issues += OperationIssue(
                    stage = OperationStage.STASH_RESTORE,
                    code = OperationIssueCode.STASH_REPOSITORY_UNAVAILABLE,
                    repositoryPath = path,
                )
                continue
            }
            val existingLock = git.indexLockFile(repositoryDirectory)
            if (existingLock != null) {
                log.warn(
                    "[fail] stash apply skipped - stale index.lock at $existingLock; " +
                        "delete it and retry (${stash.message})",
                )
                issues += OperationIssue(
                    stage = OperationStage.STASH_RESTORE,
                    code = OperationIssueCode.INDEX_LOCK_BLOCKING,
                    repositoryPath = path,
                    diagnostic = indexLockBlockedDiagnostic(existingLock),
                    lockPath = existingLock,
                )
                continue
            }
            val applyResult = try {
                git.stashApply(repositoryDirectory, stash.oid)
            } catch (error: IndexLockBlockedException) {
                // WriteGuard can observe a lock after the initial probe and throw
                // before Git starts. This is safe to retry after the lock is removed.
                nextState = nextState.withStashRestoreRetryable(path)
                throw SwitchStepException(nextState, error)
            } catch (error: RuntimeException) {
                // Other exceptions may have reached Git and must remain at-most-once.
                nextState = nextState.withStashRestoreAttempted(path)
                throw SwitchStepException(nextState, error)
            }
            if (applyResult.ok) {
                log.info("stash apply ok (${stash.message}, oid=${stash.oid})")
                // Drop the applied entry so refs/stash does not accumulate one backup
                // per switch. A drop failure is only a leftover backup, not a restore
                // failure, so it is tracked for the manual-recovery notice.
                val dropped = dropRestoredStash(git, repositoryDirectory, stash.oid, path, log)
                nextState = if (dropped) {
                    nextState.withStashRestored(path)
                } else {
                    nextState.withRestoredStashBackup(path)
                }
            } else {
                // A lock created between the earlier check and the apply must surface as
                // the structured lock block, not a generic stash-apply failure.
                val racedLock = git.indexLockFile(repositoryDirectory)
                if (racedLock != null) {
                    // The preflight check proved the tree was unlocked, and the
                    // follow-up proved the failure was the lock race. No Git apply
                    // started, so this entry remains safe to retry.
                    nextState = nextState.withStashRestoreRetryable(path)
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
                } else if (applyResult.failureKind == GitFailureKind.CANCELLED ||
                    applyResult.failureKind == GitFailureKind.INTERRUPTED ||
                    applyResult.failureKind == GitFailureKind.TIMEOUT
                ) {
                    // A cancelled/interrupted apply is a stop signal, not a failed
                    // restore: the command died before completing, so the entry stays
                    // tracked and retryable and the WIP is preserved in the stash.
                    nextState = nextState.withStashRestoreRetryable(path)
                    log.warn(
                        "[fail] stash apply interrupted for $path: ${applyResult.failureKind}; " +
                            "WIP preserved in stash (${stash.message})",
                    )
                    issues += OperationIssue(
                        stage = OperationStage.STASH_RESTORE,
                        code = OperationIssueCode.STASH_RESTORE_FAILED,
                        repositoryPath = path,
                        diagnostic = "restore interrupted by cancellation; WIP preserved in stash " +
                            "(${stash.message}, oid=${stash.oid})",
                    )
                    break
                } else {
                    nextState = nextState.withStashRestoreAttempted(path)
                    log.warn("[fail] stash apply failed for $path: ${applyResult.diagnostic()}")
                    issues += OperationIssue(
                        stage = OperationStage.STASH_RESTORE,
                        code = OperationIssueCode.STASH_RESTORE_FAILED,
                        repositoryPath = path,
                        diagnostic = applyResult.diagnostic(),
                    )
                }
            }
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
        return StashRestoreResult(nextState, issues)
    } catch (e: RuntimeException) {
        throw SwitchStepException(nextState, e)
    }
    return StashRestoreResult(nextState, issues)
}

/** Best-effort `git stash drop` after a successful apply; false leaves a backup behind. */
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
        log.warn("[fail] stash drop failed for $path: ${drop.diagnostic()}")
        false
    }
} catch (error: RuntimeException) {
    log.warn("[fail] stash drop failed for $path: ${error.javaClass.simpleName}: ${error.message}")
    false
}
