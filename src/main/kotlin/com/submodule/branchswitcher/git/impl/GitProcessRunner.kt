package com.submodule.branchswitcher.git.impl

import com.submodule.branchswitcher.git.GIT_STDERR_CANCELLED
import com.submodule.branchswitcher.git.GIT_STDERR_CAPACITY_PREFIX
import com.submodule.branchswitcher.git.GIT_STDERR_INTERRUPTED
import com.submodule.branchswitcher.git.GIT_STDERR_OUTPUT_CAPTURE_PREFIX
import com.submodule.branchswitcher.git.GIT_STDERR_OUTPUT_LIMIT_PREFIX
import com.submodule.branchswitcher.git.GIT_STDERR_START_FAILED_PREFIX
import com.submodule.branchswitcher.git.GIT_STDERR_TIMEOUT_PREFIX
import com.submodule.branchswitcher.git.GitResult
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal fun safeTimeoutSeconds(timeoutSeconds: Int): Int =
    timeoutSeconds.coerceIn(1, 3600)

/**
 * Runs one Git process with bounded execution and cooperative cancellation.
 */
internal class GitProcessRunner(
    timeoutSeconds: Int,
    private val outputDrainer: GitOutputDrainer = GitOutputDrainer(),
    private val processPermits: Semaphore = GitProcessResources.processPermits,
    private val gracefulTerminationSupported: Boolean = !isWindowsRuntime(),
    private val pollDeadlineSeconds: Long = POLL_DEADLINE_SECONDS,
    private val processStarter: (ProcessBuilder) -> Process,
) {
    val effectiveTimeoutSeconds: Int = safeTimeoutSeconds(timeoutSeconds)

    /** Executes `git [args]` in [workDir], polling for cancellation and timeout every 100ms. */
    @Suppress("TooGenericExceptionCaught") // injected process starters must be converted to structured failures
    fun run(
        workDir: File,
        cancellation: AtomicBoolean,
        vararg args: String,
    ): GitResult = run(workDir, cancellation, args.asList())

    fun run(
        workDir: File,
        cancellation: AtomicBoolean,
        args: List<String>,
    ): GitResult {
        val commandLabel = "git ${args.joinToString(" ")}"
        if (cancellation.get()) {
            return GitResult(commandLabel, -1, "", GIT_STDERR_CANCELLED)
        }
        // One budget for the whole command: permit acquisition and process execution
        // share a single deadline, so the configured timeout is an end-to-end cap
        // rather than being doubled when the process pool is saturated.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(effectiveTimeoutSeconds.toLong())
        val permitFailure = acquireProcessPermit(cancellation, deadline)
        if (permitFailure != null) {
            return GitResult(commandLabel, -1, "", permitFailure)
        }
        var releasePermit = true
        return try {
            val outcome = runWithPermit(workDir, cancellation, commandLabel, args, deadline)
            releasePermit = outcome.resourcesStopped
            outcome.result
        } finally {
            if (releasePermit) processPermits.release()
        }
    }

    private data class ProcessRunOutcome(
        val result: GitResult,
        val resourcesStopped: Boolean,
    )

    private data class ProcessWait(
        val exitCode: Int,
        val terminationReason: String?,
        val interrupted: Boolean,
    )

    /** Polls [process] until it exits, is cancelled, reaches [deadline], or exceeds the stdout cap. */
    private fun awaitProcessExit(
        process: Process,
        cancellation: AtomicBoolean,
        stdoutLimitExceeded: AtomicBoolean,
        deadline: Long,
        observedDescendants: MutableMap<Long, ProcessHandle>,
    ): ProcessWait {
        var exitCode = -1
        var terminationReason: String? = null
        var interrupted = false
        while (true) {
            observeDescendants(process, observedDescendants)
            val finished = try {
                process.waitFor(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                terminationReason = GIT_STDERR_INTERRUPTED
                break
            }
            if (finished) {
                exitCode = process.exitValue()
                break
            }
            if (cancellation.get()) {
                terminationReason = GIT_STDERR_CANCELLED
                break
            }
            if (System.nanoTime() - deadline >= 0) {
                terminationReason = GIT_STDERR_TIMEOUT_PREFIX + "${effectiveTimeoutSeconds}s"
                break
            }
            if (stdoutLimitExceeded.get()) {
                terminationReason = stdoutLimitMessage()
                break
            }
        }
        return ProcessWait(exitCode, terminationReason, interrupted)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runWithPermit(
        workDir: File,
        cancellation: AtomicBoolean,
        commandLabel: String,
        args: List<String>,
        deadline: Long,
    ): ProcessRunOutcome {
        val builder = ProcessBuilder(listOf("git") + args)
            .directory(workDir)
            .redirectErrorStream(false)
        val process = try {
            processStarter(builder)
        } catch (e: Exception) {
            return ProcessRunOutcome(
                GitResult(commandLabel, -1, "", GIT_STDERR_START_FAILED_PREFIX + "${e.javaClass.name}: ${e.message}"),
                resourcesStopped = true,
            )
        }

        val stdoutLimitExceeded = AtomicBoolean(false)
        val stdoutFuture = try {
            outputDrainer.captureStdout(process.inputStream, stdoutLimitExceeded)
        } catch (error: RuntimeException) {
            return drainSchedulingFailure(process, commandLabel, "stdout", error)
        }
        val stderrFuture = try {
            outputDrainer.captureStderr(process.errorStream)
        } catch (error: RuntimeException) {
            return drainSchedulingFailure(process, commandLabel, "stderr", error)
        }

        val observedDescendants = linkedMapOf<Long, ProcessHandle>()
        val wait = try {
            awaitProcessExit(
                process,
                cancellation,
                stdoutLimitExceeded,
                deadline,
                observedDescendants,
            )
        } catch (error: RuntimeException) {
            // An unexpected probe failure while the process may still be running must not
            // hand the pool slot back for an orphaned process: stop it first, then route
            // through completedOutcome so a process that cannot be confirmed stopped still
            // returns its slot once it actually exits (bounded), exactly like the other
            // error outcomes. Releasing synchronously here would leak the permit for an
            // unstoppable process, deadlocking the pool after a few such failures.
            val termination = terminateProcess(process, observedDescendants)
            return completedOutcome(
                GitResult(commandLabel, -1, "", "process probe failed: ${error.javaClass.name}: ${error.message}"),
                termination.resourcesStopped,
                process,
                observedDescendants,
            )
        }
        val exitCode = wait.exitCode
        val terminationReason = wait.terminationReason
        var interrupted = wait.interrupted

        var resourcesStopped = true
        if (terminationReason != null) {
            val termination = terminateProcess(process, observedDescendants)
            interrupted = termination.interrupted || interrupted
            resourcesStopped = termination.resourcesStopped
        }

        var captureCleanupPerformed = false
        val cleanupBlockedCapture = {
            if (!captureCleanupPerformed) {
                captureCleanupPerformed = true
                val termination = terminateProcess(process, observedDescendants)
                interrupted = termination.interrupted || interrupted
                resourcesStopped = termination.resourcesStopped && resourcesStopped
            }
        }
        val stdout = awaitCapture(stdoutFuture, cleanupBlockedCapture)
        val stderr = awaitCapture(stderrFuture, cleanupBlockedCapture)
        interrupted = interrupted || stdout.interrupted || stderr.interrupted
        if (interrupted) {
            Thread.currentThread().interrupt()
            return completedOutcome(
                GitResult(commandLabel, -1, "", GIT_STDERR_INTERRUPTED),
                resourcesStopped,
                process,
                observedDescendants,
            )
        }
        if (terminationReason != null) {
            return completedOutcome(
                GitResult(commandLabel, -1, "", terminationReason),
                resourcesStopped,
                process,
                observedDescendants,
            )
        }
        val captureFailure = stdout.failure ?: stderr.failure
        if (captureFailure != null) {
            return completedOutcome(
                GitResult(commandLabel, -1, "", captureFailure),
                resourcesStopped,
                process,
                observedDescendants,
            )
        }
        if (stdout.capture.truncated) {
            return completedOutcome(
                GitResult(commandLabel, -1, "", stdoutLimitMessage()),
                resourcesStopped,
                process,
                observedDescendants,
            )
        }
        val stderrText = if (stderr.capture.truncated) {
            "[stderr truncated; showing last $GIT_STDERR_TAIL_BYTES bytes]\n${stderr.capture.text}"
        } else {
            stderr.capture.text
        }
        // The drain threads already closed stdout/stderr via `use {}`; close stdin too
        // so a long-lived process never leaks its input pipe to GC (macOS FD limit).
        closeProcessStreams(process)
        // Trim only the trailing line terminators: a leading blank, a trailing space, or a
        // trailing tab can belong to a real output record (e.g. a file named " leading.txt"
        // or "trailing.txt "), while the CR/LF that ends a line is always command noise.
        // NUL is never trimmed — a NUL-delimited raw path (ls-files -z / ls-tree -z) that
        // itself ends in whitespace must survive byte-for-byte.
        return completedOutcome(
            GitResult(commandLabel, exitCode, stdout.capture.text.trimEnd('\r', '\n'), stderrText.trim()),
            resourcesStopped,
            process,
            observedDescendants,
        )
    }

    /**
     * A drain task could not even be scheduled (the shared executor is shutting down
     * during plugin unload). The process was already started, so stop it before
     * returning: a released permit must never correspond to a process that is still
     * running. [completedOutcome] keeps the permit held until the process tree exits.
     */
    private fun drainSchedulingFailure(
        process: Process,
        commandLabel: String,
        drain: String,
        error: RuntimeException,
    ): ProcessRunOutcome {
        val observedDescendants = linkedMapOf<Long, ProcessHandle>()
        val termination = terminateProcess(process, observedDescendants)
        return completedOutcome(
            GitResult(commandLabel, -1, "", GIT_STDERR_OUTPUT_CAPTURE_PREFIX + "failed: $drain: ${error.javaClass.name}: ${error.message}"),
            termination.resourcesStopped,
            process,
            observedDescendants,
        )
    }

    /** Defers permit release until every still-live process is actually gone. */
    @Suppress("SpreadOperator") // CompletableFuture.allOf exposes only a Java vararg API.
    private fun completedOutcome(
        result: GitResult,
        resourcesStopped: Boolean,
        process: Process,
        observedDescendants: Map<Long, ProcessHandle>,
    ): ProcessRunOutcome {
        if (resourcesStopped) return ProcessRunOutcome(result, resourcesStopped = true)

        // Bound the deferral exactly like the polling fallback: a process that never
        // exits (e.g. stuck in an uninterruptible D-state) must not hold a pool slot
        // forever, or four such processes deadlock the whole pool. Each onExit future
        // races the deadline and the permit is released either way.
        val pendingExits = try {
            observedDescendants.values.filter(::isAlive)
                .mapTo(mutableListOf<CompletableFuture<*>>()) {
                    it.onExit().toCompletableFuture().orTimeout(pollDeadlineSeconds, TimeUnit.SECONDS)
                }
                .also { exits ->
                    if (process.isAlive) {
                        exits += process.onExit().toCompletableFuture().orTimeout(pollDeadlineSeconds, TimeUnit.SECONDS)
                    }
                }
        } catch (_: UnsupportedOperationException) {
            return deferPermitReleaseByPolling(result, process, observedDescendants)
        } catch (_: SecurityException) {
            return deferPermitReleaseByPolling(result, process, observedDescendants)
        }
        if (pendingExits.isEmpty()) return ProcessRunOutcome(result, resourcesStopped = true)

        return try {
            CompletableFuture.allOf(*pendingExits.toTypedArray()).whenComplete { _, _ ->
                processPermits.release()
            }
            ProcessRunOutcome(result, resourcesStopped = false)
        } catch (_: UnsupportedOperationException) {
            deferPermitReleaseByPolling(result, process, observedDescendants)
        } catch (_: SecurityException) {
            deferPermitReleaseByPolling(result, process, observedDescendants)
        }
    }

    private fun deferPermitReleaseByPolling(
        result: GitResult,
        process: Process,
        observedDescendants: Map<Long, ProcessHandle>,
    ): ProcessRunOutcome {
        val watcherScheduled = try {
            GitProcessResources.exitWatcherExecutor.execute {
                var interrupted = false
                // isAlive() reports true for handles that cannot be queried, so the
                // loop must be bounded: an unqueryable handle must not hold a permit
                // (and a watcher thread) forever.
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(pollDeadlineSeconds)
                while (System.nanoTime() - deadline < 0 &&
                    (isProcessAlive(process) || observedDescendants.values.any(::isAlive))
                ) {
                    try {
                        Thread.sleep(250)
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
                processPermits.release()
                if (interrupted) Thread.currentThread().interrupt()
            }
            true
        } catch (_: RejectedExecutionException) {
            // The exit watcher is already shutting down (plugin unload) and cannot hold
            // the permit until the process exits. Stop the process here so the
            // synchronous release below does not leave a live process running.
            false
        }
        if (watcherScheduled) return ProcessRunOutcome(result, resourcesStopped = false)
        terminateProcess(process, observedDescendants.toMutableMap())
        return ProcessRunOutcome(result, resourcesStopped = true)
    }

    private fun acquireProcessPermit(cancellation: AtomicBoolean, deadline: Long): String? {
        while (System.nanoTime() - deadline < 0) {
            if (cancellation.get()) return GIT_STDERR_CANCELLED
            try {
                val remainingNanos = deadline - System.nanoTime()
                val waitNanos = minOf(TimeUnit.MILLISECONDS.toNanos(100), remainingNanos)
                if (waitNanos > 0 && processPermits.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)) return null
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return GIT_STDERR_INTERRUPTED
            }
        }
        return GIT_STDERR_CAPACITY_PREFIX + "${effectiveTimeoutSeconds}s"
    }

    private data class AwaitedCapture(
        val capture: GitCapturedOutput,
        val interrupted: Boolean,
        val failure: String? = null,
    )

    private fun awaitCapture(
        future: Future<GitCapturedOutput>,
        cleanupBlockedCapture: () -> Unit,
    ): AwaitedCapture {
        var interrupted = false
        repeat(2) {
            try {
                // A value recovered on the retry is complete and trustworthy: do not
                // carry a transient interrupt flag that would make the caller discard
                // the real capture and report the command as "interrupted". But the
                // interrupt state must still be restored on the calling thread so the
                // cancellation is not silently lost.
                val capture = future.get(5, TimeUnit.SECONDS)
                if (interrupted) Thread.currentThread().interrupt()
                return AwaitedCapture(capture, interrupted = false)
            } catch (_: InterruptedException) {
                interrupted = true
            } catch (error: ExecutionException) {
                val cause = error.cause ?: error
                return AwaitedCapture(
                    GitCapturedOutput("", truncated = false),
                    interrupted,
                    GIT_STDERR_OUTPUT_CAPTURE_PREFIX + "failed: ${cause.javaClass.name}: ${cause.message}",
                )
            } catch (_: TimeoutException) {
                cleanupBlockedCapture()
                future.cancel(true)
                return AwaitedCapture(
                    GitCapturedOutput("", truncated = false),
                    interrupted,
                    GIT_STDERR_OUTPUT_CAPTURE_PREFIX + "timed out after 5s",
                )
            }
        }
        future.cancel(true)
        return AwaitedCapture(GitCapturedOutput("", truncated = false), interrupted = true)
    }

    private fun stdoutLimitMessage(): String =
        GIT_STDERR_OUTPUT_LIMIT_PREFIX + "stdout exceeded $GIT_STDOUT_LIMIT_BYTES bytes"

    private data class ProcessTermination(
        val interrupted: Boolean,
        val resourcesStopped: Boolean,
    )

    private data class TreeWaitResult(
        val stopped: Boolean,
        val interrupted: Boolean,
    )

    private fun observeDescendants(process: Process, observed: MutableMap<Long, ProcessHandle>) {
        val descendants = try {
            process.descendants().use { stream -> stream.toList() }
        } catch (_: UnsupportedOperationException) {
            emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
        descendants.forEach { descendant ->
            val pid = try {
                descendant.pid()
            } catch (_: UnsupportedOperationException) {
                return@forEach
            } catch (_: SecurityException) {
                return@forEach
            }
            observed[pid] = descendant
        }
    }

    private fun terminateProcess(
        process: Process,
        observedDescendants: MutableMap<Long, ProcessHandle>,
    ): ProcessTermination {
        observeDescendants(process, observedDescendants)
        val descendants = observedDescendants.values.toList()
        var interrupted = false
        try {
            if (gracefulTerminationSupported) {
                // Graceful first: SIGTERM lets a git write process remove its own
                // index.lock before exiting. Wait for the whole observed tree; a
                // parent exiting does not mean a nested git writer has stopped.
                descendants.asReversed().forEach { descendant -> signalDescendant(descendant, graceful = true) }
                signalProcess(process, graceful = true)
                val gracefulExit = waitForTree(process, descendants, TERMINATION_GRACE_MILLIS)
                interrupted = gracefulExit.interrupted
                if (!gracefulExit.stopped) {
                    descendants.asReversed().forEach { descendant -> signalDescendant(descendant, graceful = false) }
                    signalProcess(process, graceful = false)
                    val hardExit = waitForTree(process, descendants, TERMINATION_HARD_WAIT_MILLIS)
                    interrupted = interrupted || hardExit.interrupted
                }
            } else {
                // Process.destroy() is a hard TerminateProcess-style operation on
                // Windows, not a cooperative SIGTERM. Do not burn a fake grace
                // window or describe the action as graceful.
                descendants.asReversed().forEach { descendant -> signalDescendant(descendant, graceful = false) }
                signalProcess(process, graceful = false)
                val hardExit = waitForTree(process, descendants, TERMINATION_HARD_WAIT_MILLIS)
                interrupted = hardExit.interrupted
            }
        } finally {
            closeProcessStreams(process)
        }
        val parentStopped = !isProcessAlive(process)
        val descendantsStopped = descendants.none(::isAlive)
        return ProcessTermination(interrupted, parentStopped && descendantsStopped)
    }

    private fun waitForTree(
        process: Process,
        descendants: List<ProcessHandle>,
        timeoutMillis: Long,
    ): TreeWaitResult {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        var interrupted = false
        while (true) {
            if (!isProcessAlive(process) && descendants.none(::isAlive)) {
                try {
                    // Preserve an interruption raised by a process implementation during
                    // the final wait, even if it reported itself stopped immediately.
                    process.waitFor(0, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
                return TreeWaitResult(stopped = true, interrupted = interrupted)
            }
            val remainingNanos = deadline - System.nanoTime()
            if (remainingNanos <= 0) return TreeWaitResult(stopped = false, interrupted = interrupted)
            try {
                if (isProcessAlive(process)) {
                    process.waitFor(minOf(TimeUnit.NANOSECONDS.toMillis(remainingNanos), 50L), TimeUnit.MILLISECONDS)
                } else {
                    Thread.sleep(minOf(TimeUnit.NANOSECONDS.toMillis(remainingNanos), 10L))
                }
            } catch (_: InterruptedException) {
                interrupted = true
                break
            }
        }
        return TreeWaitResult(stopped = false, interrupted = interrupted)
    }

    /** Best-effort SIGTERM/SIGKILL for one git process, tolerating unsupported/protected processes. */
    private fun signalProcess(process: Process, graceful: Boolean) {
        try {
            if (graceful) process.destroy() else process.destroyForcibly()
        } catch (_: UnsupportedOperationException) {
            try {
                if (graceful) process.destroyForcibly() else process.destroy()
            } catch (_: SecurityException) {
                // Stream cleanup below still prevents drain-thread starvation.
            }
        } catch (_: SecurityException) {
            // Stream cleanup below still prevents drain-thread starvation.
        }
    }

    /** Best-effort SIGTERM/SIGKILL for one descendant process handle. */
    private fun signalDescendant(descendant: ProcessHandle, graceful: Boolean) {
        try {
            if (!descendant.isAlive) return
            if (graceful) descendant.destroy() else descendant.destroyForcibly()
        } catch (_: RuntimeException) {
            // A process handle may reject signaling (unsupported / protected / a
            // primitive-return proxy); the caller's aliveness pass still escalates it.
        }
    }

    private fun isAlive(process: ProcessHandle): Boolean = try {
        process.isAlive
    } catch (_: UnsupportedOperationException) {
        true
    } catch (_: SecurityException) {
        true
    }

    private fun isProcessAlive(process: Process): Boolean = try {
        process.isAlive
    } catch (_: UnsupportedOperationException) {
        true
    } catch (_: SecurityException) {
        true
    }

    private fun closeProcessStreams(process: Process) {
        listOf(process.inputStream, process.errorStream, process.outputStream).forEach { stream ->
            try {
                stream.close()
            } catch (_: Exception) {
                // Process termination is already authoritative; stream cleanup is best effort.
            }
        }
    }

    private companion object {
        /** Cooperative-exit window after SIGTERM before escalating to SIGKILL. */
        const val TERMINATION_GRACE_MILLIS = 1500L

        /** Bounded wait for a SIGKILLed process to actually exit. */
        const val TERMINATION_HARD_WAIT_MILLIS = 5000L

        /** Upper bound on the exit-watcher polling loop before a permit is force-released. */
        const val POLL_DEADLINE_SECONDS = 60L

        fun isWindowsRuntime(): Boolean =
            System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
    }
}
