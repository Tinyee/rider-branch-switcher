package com.submodule.branchswitcher.workflow

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/** Owns the write-lease lifecycle shared by every repository mutation entry point. */
class WriteOperationLauncher(
    private val scope: CoroutineScope,
    private val tryAcquireWrite: () -> AutoCloseable?,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    /** Returns null and calls [onBusy] when another write already owns the lease. */
    fun launch(
        onBusy: () -> Unit,
        operation: suspend () -> Unit,
    ): Job? {
        val acquiredLease = tryAcquireWrite()
        if (acquiredLease == null) {
            onBusy()
            return null
        }

        val writeLease = CloseOnce(acquiredLease)
        val job = scope.launch(dispatcher) {
            try {
                operation()
            } finally {
                writeLease.close()
            }
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
