package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.operation.SessionCancelGuard
import com.submodule.branchswitcher.log.logFailure
import com.submodule.branchswitcher.log.newOperationContext
import com.submodule.branchswitcher.log.withContext
import com.submodule.branchswitcher.switch.OperationCancelledException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference

/** Owns one cancellable repository-state read and suppresses superseded UI deliveries. */
class RepositoryStateRefreshCoordinator(
    private val scope: CoroutineScope,
    private val openOperation: () -> GitOperationSession,
    private val detector: RepositoryStateDetector,
    private val log: AppLogger,
    private val deliver: ((() -> Unit) -> Unit),
    /** Concurrent background probe budget; one git-process slot is already reserved for a foreground switch or recovery. */
    private val gitProcessBudget: Int,
    private val worker: CoroutineDispatcher = Dispatchers.IO,
) : AutoCloseable {
    private val lock = Any()
    private var active: RefreshState? = null
    private var closed = false

    @Suppress("TooGenericExceptionCaught") // session open and repository probes share one lifecycle boundary
    fun refresh(
        root: Path,
        paths: Collection<String>,
        onSnapshot: (RepositoryStateSnapshot) -> Unit,
    ) {
        val request = detector.begin(root, paths)
        val state = RefreshState()
        val previous = synchronized(lock) {
            if (closed) return
            active.also { active = state }
        }
        previous?.cancel()
        val operationLog = log.withContext(newOperationContext("state-refresh").inPhase("detect"))
        val job = scope.launch {
            var operation: GitOperationSession? = null
            var deliveryScheduled = false
            try {
                val openedOperation = openOperation()
                operation = openedOperation
                state.attach(openedOperation)
                ensureActive()
                val snapshot = withContext(worker) {
                    // Probe repositories concurrently so a multi-submodule project does not
                    // pay one process-spawn latency per repository in sequence. The git-process
                    // pool's shared semaphore is the real cap; this local throttle reuses the
                    // same reserved budget so we never even start coroutines beyond it.
                    val probePermits = Semaphore(gitProcessBudget.coerceAtLeast(1))
                    coroutineScope {
                        val probes = request.paths.map { path ->
                            async { probePermits.withPermit { detector.probe(request, path, openedOperation) } }
                        }.map { it.await() }
                        detector.assembleSnapshot(request, probes)
                    }
                }
                ensureActive()
                deliveryScheduled = true
                deliver {
                    try {
                        if (isCurrent(state, snapshot)) onSnapshot(snapshot)
                    } catch (t: Throwable) {
                        // onSnapshot runs on the EDT inside the scheduled runnable; an
                        // exception here would escape as an uncaught EDT error, so log it
                        // against the refresh operation instead.
                        operationLog.logFailure("repository state delivery failed", t)
                    } finally {
                        clearIfCurrent(state)
                    }
                }
            } catch (error: OperationCancelledException) {
                // A superseded/cancelled refresh is not a failure worth logging.
            } catch (error: Exception) {
                if (isCurrent(state)) {
                    operationLog.logFailure("repository state refresh failed", error)
                }
            } finally {
                operation?.let {
                    state.detach(it)
                    it.close()
                }
                if (!deliveryScheduled) clearIfCurrent(state)
            }
        }
        state.attach(job)
    }

    override fun close() {
        val state = synchronized(lock) {
            if (closed) return
            closed = true
            detector.invalidate()
            active.also { active = null }
        }
        state?.cancel()
    }

    private fun isCurrent(state: RefreshState, snapshot: RepositoryStateSnapshot? = null): Boolean =
        synchronized(lock) {
            !closed && active === state && (snapshot == null || detector.isLatest(snapshot))
        }

    private fun clearIfCurrent(state: RefreshState) {
        synchronized(lock) {
            if (active === state) active = null
        }
    }

    private class RefreshState {
        private val guard = SessionCancelGuard()
        private val job = AtomicReference<Job?>()

        fun attach(candidate: GitOperationSession) = guard.attach(candidate)

        fun detach(candidate: GitOperationSession) = guard.detach(candidate)

        fun attach(candidate: Job) {
            job.set(candidate)
            if (guard.isCancelled()) candidate.cancel()
        }

        fun cancel() {
            guard.cancel()
            job.get()?.cancel()
        }
    }
}
