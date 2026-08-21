package com.submodule.branchswitcher.switch

/**
 * Platform-agnostic cancellation handle for the switch pipeline.
 * Allows pure-JVM tests to inject a fake cancellation check without depending on IntelliJ.
 *
 * Part of the workflow-level cancellation trio (handle + cancelled lambda + classifier).
 * For session-owned cancellation — cancelling a live Git process even when the request
 * arrived before the session was attached — use `SessionCancelGuard` (operation package).
 */
interface CancellationHandle {
    /** Throws if the operation has been cancelled. */
    fun checkCanceled()

    /** True when the user has requested cancellation. */
    val isCanceled: Boolean
}
