package com.submodule.branchswitcher.switch

/** Structured notification decision for derive-branch; pure data, no UI dependency. */
sealed class DeriveNotification {
    /** Show success info. */
    data class Success(val branchName: String, val repoCount: Int) : DeriveNotification()
    /** Warn with a structured reason. */
    data class Failure(val reason: Reason, val branchName: String, val count: Int) : DeriveNotification()
    /** Warn with per-category counts (preflight blocked). */
    data class Blocked(
        val branchExistsCount: Int,
        val skippedCount: Int,
        val dirtyCount: Int,
        val branchMismatchCount: Int,
        val preflightErrorCount: Int,
        val checkpointFailedCount: Int,
    ) : DeriveNotification()
    /** No notification (cancelled with clean rollback). */
    data object Silent : DeriveNotification()

    enum class Reason { PARTIAL, ROLLBACK_FAILED, UNEXPECTED }
}

/**
 * Maps (cancelled, result, rollbackFailureCount) to a notification decision.
 *
 * Rules (first match wins):
 * 1. cancelled + rollback failures -> Failure(ROLLBACK_FAILED)
 * 2. cancelled + clean rollback -> Silent
 * 3. result null -> Failure(UNEXPECTED)
 * 4. preflight-blocked -> Blocked
 * 5. checkpoint-blocked -> Blocked
 * 6. all ok -> Success
 * 7. rollback failures -> Failure(ROLLBACK_FAILED)
 * 8. partial -> Failure(PARTIAL)
 */
fun deriveNotification(
    cancelled: Boolean,
    result: DeriveResult?,
    rollbackFailureCount: Int,
    branchName: String,
): DeriveNotification {
    if (cancelled && rollbackFailureCount > 0)
        return DeriveNotification.Failure(DeriveNotification.Reason.ROLLBACK_FAILED, branchName, rollbackFailureCount)
    if (cancelled) return DeriveNotification.Silent

    if (result == null)
        return DeriveNotification.Failure(DeriveNotification.Reason.UNEXPECTED, branchName, 0)

    if (result.preflightBlocked)
        return DeriveNotification.Blocked(result.branchExists.size, result.skipped.size, result.dirty.size,
            result.branchMismatch.size, result.preflightError.size, result.checkpointFailed.size)

    if (result.checkpointBlocked)
        return DeriveNotification.Blocked(0, 0, 0, 0, 0, result.checkpointFailed.size)

    if (result.allOk)
        return DeriveNotification.Success(branchName, result.actualCreated)

    if (rollbackFailureCount > 0)
        return DeriveNotification.Failure(DeriveNotification.Reason.ROLLBACK_FAILED, branchName, rollbackFailureCount)

    return DeriveNotification.Failure(DeriveNotification.Reason.PARTIAL, branchName, result.failed.size)
}
