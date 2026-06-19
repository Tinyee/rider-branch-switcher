package com.submodule.branchswitcher.switch

/**
 * Platform-agnostic cancellation handle for the switch pipeline.
 * Allows pure-JVM tests to inject a fake cancellation check without depending on IntelliJ.
 */
interface CancellationHandle {
    /** Throws if the operation has been cancelled. */
    fun checkCanceled()

    /** True when the user has requested cancellation. */
    val isCanceled: Boolean
}
