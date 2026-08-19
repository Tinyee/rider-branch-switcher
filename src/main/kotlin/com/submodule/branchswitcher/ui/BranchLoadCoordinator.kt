package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.MAX_CONCURRENT_GIT_PROCESSES
import com.submodule.branchswitcher.git.PresetDiscoveryGitClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentLinkedQueue
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
    private val activeJobs = ConcurrentLinkedQueue<Job>()
    private val closed = AtomicBoolean(false)

    fun launch(block: suspend (PresetDiscoveryGitClient) -> Unit): BranchLoadHandle {
        val state = BranchLoadState()
        val job = scope.launch {
            permits.withPermit {
                if (closed.get()) return@withPermit
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
        activeJobs.add(job)
        job.invokeOnCompletion { activeJobs.remove(job) }
        return BranchLoadHandle(job, state::cancel)
    }

    /**
     * Runs one read-only discovery query in the background and delivers its result
     * to [onResult]. Shares the same concurrency limit and close-cancellation as
     * branch loads, so a discovery started from an action handler never blocks the
     * EDT and is cancelled when the Tool Window closes.
     */
    fun <T> discover(
        block: (PresetDiscoveryGitClient) -> T,
        onResult: (Result<T>) -> Unit,
    ): BranchLoadHandle {
        val state = BranchLoadState()
        val job = scope.launch {
            permits.withPermit {
                if (closed.get()) return@withPermit
                val operation = openOperation()
                state.attach(operation)
                try {
                    ensureActive()
                    val value = withContext(Dispatchers.IO) { block(operation) }
                    if (!closed.get()) onResult(Result.success(value))
                } catch (error: CancellationException) {
                    // A cancelled discovery is normal coroutine cancellation, not a
                    // query failure: deliver nothing and unwind (mirrors launch()).
                    throw error
                } catch (error: Throwable) {
                    if (!closed.get()) onResult(Result.failure(error))
                } finally {
                    state.detach(operation)
                    operation.close()
                }
            }
        }
        activeJobs.add(job)
        job.invokeOnCompletion { activeJobs.remove(job) }
        return BranchLoadHandle(job, state::cancel)
    }

    /**
     * Cancels every pending and in-flight discovery when the owning Tool Window
     * closes. Without this, branch probes keep running on the project scope and
     * keep their Git processes (and the disposed editor UI) alive until they finish.
     */
    fun close() {
        closed.set(true)
        activeJobs.forEach(Job::cancel)
        activeJobs.clear()
    }

    companion object {
        // Leave one global Git-process permit free for a foreground switch or
        // recovery. The shared process pool is capped at MAX_CONCURRENT_GIT_PROCESSES;
        // if branch discovery claimed every slot, a concurrent switch would have to
        // wait for a permit until a discovery command finishes or times out.
        // RepositoryStateRefreshCoordinator makes the same reservation.
        private const val DEFAULT_MAX_CONCURRENT_LOADS = MAX_CONCURRENT_GIT_PROCESSES - 1
    }
}
