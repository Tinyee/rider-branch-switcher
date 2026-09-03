package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.git.GitOperationSession
import com.submodule.branchswitcher.git.GitResult
import com.submodule.branchswitcher.git.GitWorkflowClient
import com.submodule.branchswitcher.git.PresetDiscoveryGitClient
import com.submodule.branchswitcher.git.SubmoduleRegistration
import com.submodule.branchswitcher.git.impl.GIT_PROCESS_BACKGROUND_BUDGET
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.operation.SessionCancelGuard
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

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

/**
 * Limits concurrent branch discovery across every preset editor in one Tool Window.
 *
 * Branch discovery starts Git processes and drains their output, so expanding a
 * large preset must not create one active process per submodule.
 */
internal class BranchLoadCoordinator(
    private val scope: CoroutineScope,
    maxConcurrentLoads: Int = GIT_PROCESS_BACKGROUND_BUDGET,
    private val openOperation: () -> GitOperationSession,
) {
    private val permits = Semaphore(maxConcurrentLoads.coerceAtLeast(1))
    private val activeLoads = ConcurrentLinkedQueue<BranchLoadHandle>()
    private val closed = AtomicBoolean(false)

    fun launch(block: suspend (PresetDiscoveryGitClient) -> Unit): BranchLoadHandle =
        launchInternal { operation -> block(operation) }

    /**
     * Runs one read-only discovery query in the background and delivers its result
     * to [onResult]. Shares the same concurrency limit and close-cancellation as
     * branch loads, so a discovery started from an action handler never blocks the
     * EDT and is cancelled when the Tool Window closes.
     */
    @Suppress("TooGenericExceptionCaught") // every probe failure is delivered to the UI callback, never lost
    fun <T> discover(
        block: (PresetDiscoveryGitClient) -> T,
        onResult: (Result<T>) -> Unit,
    ): BranchLoadHandle = launchInternal { operation ->
        try {
            val value = block(operation)
            if (!closed.get()) onResult(Result.success(value))
        } catch (error: CancellationException) {
            // A cancelled discovery is normal coroutine cancellation, not a
            // query failure: deliver nothing and unwind (mirrors launch()).
            throw error
        } catch (error: Exception) {
            if (!closed.get()) onResult(Result.failure(error))
            // JVM Errors (e.g. OutOfMemoryError) are deliberately not caught: they must
            // propagate to the coroutine machinery instead of being delivered as a query failure.
        }
    }

    /**
     * Fetches and relists one directory. [onResult] is invoked on the coordinator's
     * background dispatcher; a non-ok fetch yields [BranchRefreshResult.succeeded] == false
     * rather than throwing, so callers keep the previous list. Cancellation propagates.
     */
    @Suppress("TooGenericExceptionCaught") // a non-ok fetch is reported as a result, never thrown
    fun refresh(dir: File, onResult: (Result<BranchRefreshResult>) -> Unit): BranchLoadHandle =
        launchInternal { operation ->
            val result = try {
                val fetched = operation.fetch(dir)
                if (fetched.ok) {
                    Result.success(BranchRefreshResult(operation.listAllBranches(dir), succeeded = true))
                } else {
                    Result.success(BranchRefreshResult(emptyList(), succeeded = false, failure = fetched))
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Result.failure(error)
            }
            if (!closed.get()) onResult(result)
        }

    /**
     * Sequentially fetches each [dirs] entry in one operation. [onResult] is invoked on the
     * coordinator's background dispatcher with the split outcome; failures carry the failing
     * [GitResult] so callers can log `diagnostic()`.
     */
    @Suppress("TooGenericExceptionCaught") // per-directory fetch failures are collected, never thrown
    fun refreshAll(dirs: List<File>, onResult: (Result<RefreshAllOutcome>) -> Unit): BranchLoadHandle =
        launchInternal { operation ->
            try {
                val succeeded = mutableListOf<File>()
                val failures = mutableListOf<Pair<File, GitResult>>()
                for (dir in dirs) {
                    currentCoroutineContext().ensureActive()
                    val fetched = operation.fetch(dir)
                    if (fetched.ok) succeeded += dir else failures += dir to fetched
                }
                if (!closed.get()) onResult(Result.success(RefreshAllOutcome(succeeded, failures)))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (!closed.get()) onResult(Result.failure(error))
            }
        }

    /**
     * Sequentially refreshes every registered submodule in one operation: fetch when checked
     * out, then always re-list fresh `ls-remote` heads (cache invalidated first) and union
     * them with the local branches. [onResult] is invoked on the coordinator's background
     * dispatcher with the split outcome; a submodule that lists no branches (neither local
     * nor remote) lands in [SubmoduleRefreshOutcome.failedPaths]. Cancellation propagates.
     */
    @Suppress("TooGenericExceptionCaught") // a non-ok submodule refresh is reported as a result, never thrown
    fun refreshRemoteSubmodules(
        root: File,
        registrations: List<SubmoduleRegistration>,
        cache: RemoteBranchCache,
        log: AppLogger,
        onResult: (Result<SubmoduleRefreshOutcome>) -> Unit,
    ): BranchLoadHandle = launchInternal { operation ->
        try {
            val succeeded = linkedMapOf<String, List<String>>()
            val failedPaths = mutableListOf<String>()
            for (registration in registrations) {
                currentCoroutineContext().ensureActive()
                val dir = File(root, registration.path)
                val branches = listSubmoduleRefreshUnion(
                    operation,
                    dir,
                    SubmoduleSource(root, registration.path, registration.url),
                    cache,
                    log,
                )
                if (branches == null) failedPaths += registration.path else succeeded[registration.path] = branches
            }
            if (!closed.get()) onResult(Result.success(SubmoduleRefreshOutcome(succeeded, failedPaths)))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (!closed.get()) onResult(Result.failure(error))
        }
    }

    /**
     * Runs one discovery under the shared concurrency limit, attached to a fresh
     * Git operation session that is cancelled when the Tool Window closes. The block
     * executes on the IO dispatcher; a closed coordinator skips execution entirely.
     */
    private fun launchInternal(block: suspend (GitWorkflowClient) -> Unit): BranchLoadHandle {
        val state = SessionCancelGuard()
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
        val handle = BranchLoadHandle(job, state::cancel)
        activeLoads.add(handle)
        job.invokeOnCompletion { activeLoads.remove(handle) }
        return handle
    }

    /**
     * Cancels every pending and in-flight discovery when the owning Tool Window
     * closes. Without this, branch probes keep running on the project scope and
     * keep their Git processes (and the disposed editor UI) alive until they finish.
     */
    fun close() {
        closed.set(true)
        activeLoads.forEach(BranchLoadHandle::cancel)
        activeLoads.clear()
    }
}

/** Outcome of one explicit `fetch --prune` + relist for a single repository directory. */
internal data class BranchRefreshResult(
    val branches: List<String>,
    val succeeded: Boolean,
    val failure: GitResult? = null,
)

/** Outcome of a sequential fetch pass over several submodule directories. */
internal data class RefreshAllOutcome(
    val succeeded: List<File>,
    val failures: List<Pair<File, GitResult>>,
)

/** Outcome of a sequential refresh pass over several registered submodules. */
internal data class SubmoduleRefreshOutcome(
    /** path → deduplicated union of local and `ls-remote` branch names. */
    val succeeded: Map<String, List<String>>,
    /** paths whose refresh listed no branches (neither local nor remote). */
    val failedPaths: List<String>,
)
