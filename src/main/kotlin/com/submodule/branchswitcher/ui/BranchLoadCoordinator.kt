package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.PresetDiscoveryGitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Cancels both the coroutine and the Git process owned by one branch discovery. */
internal class BranchLoadHandle(
    private val job: Job,
    private val cancelOperation: () -> Unit,
) {
    val isActive: Boolean get() = job.isActive

    fun cancel() {
        cancelOperation()
        job.cancel()
    }

    fun invokeOnCompletion(handler: (Throwable?) -> Unit) {
        job.invokeOnCompletion(handler)
    }
}

private class BranchLoadState {
    private val cancelled = AtomicBoolean(false)
    private val operation = AtomicReference<GitOperationSession?>()

    fun attach(candidate: GitOperationSession) {
        operation.set(candidate)
        if (cancelled.get()) candidate.cancel()
    }

    fun detach(candidate: GitOperationSession) {
        operation.compareAndSet(candidate, null)
    }

    fun cancel() {
        cancelled.set(true)
        operation.get()?.cancel()
    }
}

/**
 * Limits concurrent branch discovery across every preset editor in one Tool Window.
 *
 * Branch discovery starts Git processes and drains their output, so expanding a
 * large preset must not create one active process per submodule.
 */
internal class BranchLoadCoordinator(
    private val scope: CoroutineScope,
    maxConcurrentLoads: Int = DEFAULT_MAX_CONCURRENT_LOADS,
    private val openOperation: () -> GitOperationSession,
) {
    private val permits = Semaphore(maxConcurrentLoads.coerceAtLeast(1))

    fun launch(block: suspend (PresetDiscoveryGitClient) -> Unit): BranchLoadHandle {
        val state = BranchLoadState()
        val job = scope.launch {
            permits.withPermit {
                val operation = openOperation()
                state.attach(operation)
                try {
                    ensureActive()
                    withContext(Dispatchers.IO) { block(operation) }
                } finally {
                    state.detach(operation)
                    operation.close()
                }
            }
        }
        return BranchLoadHandle(job, state::cancel)
    }

    companion object {
        private const val DEFAULT_MAX_CONCURRENT_LOADS = 4
    }
}
