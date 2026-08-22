package com.submodule.branchswitcher.switch

/**
 * Platform-agnostic cancellation control for a workflow. Allows pure-JVM tests to inject a
 * fake cancellation check without depending on IntelliJ.
 *
 * [checkCancelled] throws [OperationCancelledException] when the user cancelled; platform
 * adapters convert their own cancellation types into that exception at this boundary.
 * [isCanceled] is polled where cancellation must not abort the work (e.g. stopping a stash
 * restore without failing the switch).
 *
 * For session-owned cancellation — cancelling a live Git process even when the request
 * arrived before the session was attached — use `SessionCancelGuard` (operation package).
 */
interface OperationControl {
    /** Throws [OperationCancelledException] if the operation has been cancelled. */
    fun checkCancelled()

    /** True when the user has requested cancellation. */
    val isCanceled: Boolean
}
