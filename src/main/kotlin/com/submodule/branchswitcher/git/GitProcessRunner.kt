package com.submodule.branchswitcher.git

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
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
            return GitResult(commandLabel, -1, "", "cancelled")
        }
        val permitFailure = acquireProcessPermit(cancellation)
        if (permitFailure != null) {
            return GitResult(commandLabel, -1, "", permitFailure)
        }
        var releasePermit = true
        return try {
            val outcome = runWithPermit(workDir, cancellation, commandLabel, args)
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

    @Suppress("TooGenericExceptionCaught")
    private fun runWithPermit(
        workDir: File,
        cancellation: AtomicBoolean,
        commandLabel: String,
        args: List<String>,
    ): ProcessRunOutcome {
        val builder = ProcessBuilder(listOf("git") + args)
            .directory(workDir)
            .redirectErrorStream(false)
        val process = try {
            processStarter(builder)
        } catch (e: Exception) {
            return ProcessRunOutcome(
                GitResult(commandLabel, -1, "", "failed to start: ${e.javaClass.name}: ${e.message}"),
                resourcesStopped = true,
            )
        }

        val stdoutLimitExceeded = AtomicBoolean(false)
        val stdoutFuture = outputDrainer.captureStdout(process.inputStream, stdoutLimitExceeded)
        val stderrFuture = outputDrainer.captureStderr(process.errorStream)

        val deadline = System.nanoTime() +
            TimeUnit.SECONDS.toNanos(effectiveTimeoutSeconds.toLong())
        var exitCode = -1
        var terminationReason: String? = null
        var interrupted = false
        val observedDescendants = linkedMapOf<Long, ProcessHandle>()
        while (true) {
            observeDescendants(process, observedDescendants)
            val finished = try {
                process.waitFor(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                terminationReason = "interrupted"
                break
            }
            if (finished) {
                exitCode = process.exitValue()
                break
            }
            if (cancellation.get()) {
                terminationReason = "cancelled"
                break
            }
            if (System.nanoTime() - deadline >= 0) {
                terminationReason = "timeout after ${effectiveTimeoutSeconds}s"
                break
            }
            if (stdoutLimitExceeded.get()) {
                terminationReason = stdoutLimitMessage()
                break
            }
        }

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
                GitResult(commandLabel, -1, "", "interrupted"),
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
        return completedOutcome(
            GitResult(commandLabel, exitCode, stdout.capture.text.trim(), stderrText.trim()),
            resourcesStopped,
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

        val pendingExits = try {
            observedDescendants.values.filter(::isAlive)
                .mapTo(mutableListOf<CompletableFuture<*>>()) { it.onExit().toCompletableFuture() }
                .also { exits -> if (process.isAlive) exits += process.onExit() }
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
        GitProcessResources.exitWatcherExecutor.execute {
            var interrupted = false
            while (isProcessAlive(process) || observedDescendants.values.any(::isAlive)) {
                try {
                    Thread.sleep(250)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
            processPermits.release()
            if (interrupted) Thread.currentThread().interrupt()
        }
        return ProcessRunOutcome(result, resourcesStopped = false)
    }

    private fun acquireProcessPermit(cancellation: AtomicBoolean): String? {
        val deadline = System.nanoTime() +
            TimeUnit.SECONDS.toNanos(effectiveTimeoutSeconds.toLong())
        while (System.nanoTime() - deadline < 0) {
            if (cancellation.get()) return "cancelled"
            try {
                val remainingNanos = deadline - System.nanoTime()
                val waitNanos = minOf(TimeUnit.MILLISECONDS.toNanos(100), remainingNanos)
                if (waitNanos > 0 && processPermits.tryAcquire(waitNanos, TimeUnit.NANOSECONDS)) return null
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return "interrupted"
            }
        }
        return "process capacity unavailable after ${effectiveTimeoutSeconds}s"
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
                return AwaitedCapture(future.get(5, TimeUnit.SECONDS), interrupted)
            } catch (_: InterruptedException) {
                interrupted = true
            } catch (error: ExecutionException) {
                val cause = error.cause ?: error
                return AwaitedCapture(
                    GitCapturedOutput("", truncated = false),
                    interrupted,
                    "output capture failed: ${cause.javaClass.name}: ${cause.message}",
                )
            } catch (_: TimeoutException) {
                cleanupBlockedCapture()
                future.cancel(true)
                return AwaitedCapture(
                    GitCapturedOutput("", truncated = false),
                    interrupted,
                    "output capture timed out after 5s",
                )
            }
        }
        future.cancel(true)
        return AwaitedCapture(GitCapturedOutput("", truncated = false), interrupted = true)
    }

    private fun stdoutLimitMessage(): String =
        "output limit exceeded: stdout exceeded $GIT_STDOUT_LIMIT_BYTES bytes"

    private data class ProcessTermination(
        val interrupted: Boolean,
        val resourcesStopped: Boolean,
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
            // Graceful first: SIGTERM lets a git write process remove its own index.lock
            // before exiting. A write killed with SIGKILL between creating index.lock and
            // writing the index leaves a stale 0-byte lock that blocks every later git
            // write. Escalate to SIGKILL only for processes still alive after the window.
            descendants.asReversed().forEach { descendant -> signalDescendant(descendant, graceful = true) }
            val gracefulExit = try {
                signalProcess(process, graceful = true)
                process.waitFor(TERMINATION_GRACE_MILLIS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
                false
            }
            if (!gracefulExit || descendants.any(::isAlive)) {
                descendants.asReversed().forEach { descendant -> signalDescendant(descendant, graceful = false) }
                signalProcess(process, graceful = false)
                try {
                    process.waitFor(TERMINATION_HARD_WAIT_MILLIS, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    interrupted = true
                }
            }
        } finally {
            closeProcessStreams(process)
        }
        val parentStopped = !isProcessAlive(process)
        val descendantsStopped = descendants.none(::isAlive)
        return ProcessTermination(interrupted, parentStopped && descendantsStopped)
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
    }
}
