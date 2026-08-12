package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.MAX_CONCURRENT_GIT_PROCESSES
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.logFailure
import com.submodule.branchswitcher.log.newOperationContext
import com.submodule.branchswitcher.log.withContext
import com.submodule.branchswitcher.switch.CancellationClassifier
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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Owns one cancellable repository-state read and suppresses superseded UI deliveries. */
class RepositoryStateRefreshCoordinator(
    private val scope: CoroutineScope,
    private val openOperation: () -> GitOperationSession,
    private val detector: RepositoryStateDetector,
    private val log: AppLogger,
    private val deliver: ((() -> Unit) -> Unit),
    private val worker: CoroutineDispatcher = Dispatchers.IO,
    private val cancellationClassifier: CancellationClassifier = CancellationClassifier.DEFAULT,
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
                    // same bound so we never even start coroutines beyond it.
                    // Keep one process slot available for a foreground switch or recovery.
                    val probePermits = Semaphore((MAX_CONCURRENT_GIT_PROCESSES - 1).coerceAtLeast(1))
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
                    } finally {
                        clearIfCurrent(state)
                    }
                }
            } catch (error: Exception) {
                if (isCurrent(state) && !cancellationClassifier.isCancellation(error)) {
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
        private val cancelled = AtomicBoolean(false)
        private val operation = AtomicReference<GitOperationSession?>()
        private val job = AtomicReference<Job?>()

        fun attach(candidate: GitOperationSession) {
            operation.set(candidate)
            if (cancelled.get()) candidate.cancel()
        }

        fun attach(candidate: Job) {
            job.set(candidate)
            if (cancelled.get()) candidate.cancel()
        }

        fun detach(candidate: GitOperationSession) {
            operation.compareAndSet(candidate, null)
        }

        fun cancel() {
            cancelled.set(true)
            operation.get()?.cancel()
            job.get()?.cancel()
        }
    }
}
