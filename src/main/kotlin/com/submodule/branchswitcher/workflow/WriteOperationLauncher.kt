package com.submodule.branchswitcher.workflow

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the write-lease lifecycle shared by every repository mutation entry point.
 *
 * [afterRelease] is the post-success delivery hook: it runs once, only when
 * [operation] completed normally, and only after the write lease has been released.
 * On the exception path (operation threw or was cancelled) the lease is still
 * released but [afterRelease] is intentionally skipped — failures are delivered by
 * the caller through the operation's own result type, not through this hook. A
 * throwing [afterRelease] is a caller bug; it propagates as the job's failure so
 * completion handlers (and the coroutine exception handler) see it rather than it
 * being silently swallowed.
 */
class WriteOperationLauncher(
    private val scope: CoroutineScope,
    private val tryAcquireWrite: () -> AutoCloseable?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Returns null and calls [onBusy] when another write already owns the lease. */
    fun <T> launch(
        onBusy: () -> Unit,
        afterRelease: suspend (T) -> Unit = {},
        operation: suspend () -> T,
    ): Job? {
        val acquiredLease = tryAcquireWrite()
        if (acquiredLease == null) {
            onBusy()
            return null
        }

        val writeLease = CloseOnce(acquiredLease)
        val job = scope.launch(dispatcher) {
            val result = try {
                operation()
            } finally {
                writeLease.close()
            }
            // Success-only delivery: the lease is already released, and a throwing
            // afterRelease surfaces as this job's failure (see class KDoc).
            afterRelease(result)
        }
        job.invokeOnCompletion { writeLease.close() }
        return job
    }
}

private class CloseOnce(
    private val delegate: AutoCloseable,
) : AutoCloseable {
    private val closed = AtomicBoolean(false)

    override fun close() {
        if (closed.compareAndSet(false, true)) delegate.close()
    }
}
