package com.submodule.branchswitcher.git

import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
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
        return try {
            runWithPermit(workDir, cancellation, commandLabel, args)
        } finally {
            GitProcessResources.processPermits.release()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun runWithPermit(
        workDir: File,
        cancellation: AtomicBoolean,
        commandLabel: String,
        args: List<String>,
    ): GitResult {
        val builder = ProcessBuilder(listOf("git") + args)
            .directory(workDir)
            .redirectErrorStream(false)
        val process = try {
            processStarter(builder)
        } catch (e: Exception) {
            return GitResult(
                commandLabel,
                -1,
                "",
                "failed to start: ${e.javaClass.name}: ${e.message}",
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
        while (true) {
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

        if (terminationReason != null) {
            interrupted = terminateProcess(process) || interrupted
        }

        val stdout = awaitCapture(stdoutFuture)
        val stderr = awaitCapture(stderrFuture)
        interrupted = interrupted || stdout.interrupted || stderr.interrupted
        if (interrupted) {
            Thread.currentThread().interrupt()
            return GitResult(commandLabel, -1, "", "interrupted")
        }
        if (terminationReason != null) {
            return GitResult(commandLabel, -1, "", terminationReason)
        }
        val captureFailure = stdout.failure ?: stderr.failure
        if (captureFailure != null) {
            return GitResult(commandLabel, -1, "", captureFailure)
        }
        if (stdout.capture.truncated) {
            return GitResult(commandLabel, -1, "", stdoutLimitMessage())
        }
        val stderrText = if (stderr.capture.truncated) {
            "[stderr truncated; showing last $GIT_STDERR_TAIL_BYTES bytes]\n${stderr.capture.text}"
        } else {
            stderr.capture.text
        }
        return GitResult(commandLabel, exitCode, stdout.capture.text.trim(), stderrText.trim())
    }

    private fun acquireProcessPermit(cancellation: AtomicBoolean): String? {
        while (true) {
            if (cancellation.get()) return "cancelled"
            try {
                if (GitProcessResources.processPermits.tryAcquire(100, TimeUnit.MILLISECONDS)) return null
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return "interrupted"
            }
        }
    }

    private data class AwaitedCapture(
        val capture: GitCapturedOutput,
        val interrupted: Boolean,
        val failure: String? = null,
    )

    private fun awaitCapture(future: Future<GitCapturedOutput>): AwaitedCapture {
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

    /** Returns true when cleanup itself was interrupted. */
    private fun terminateProcess(process: Process): Boolean {
        val descendants = try {
            process.descendants().use { stream -> stream.toList() }
        } catch (_: UnsupportedOperationException) {
            emptyList()
        } catch (_: SecurityException) {
            emptyList()
        }
        descendants.asReversed().forEach { descendant ->
            try {
                if (descendant.isAlive) descendant.destroyForcibly()
            } catch (_: UnsupportedOperationException) {
                try {
                    descendant.destroy()
                } catch (_: SecurityException) {
                    // Continue with the parent and stream cleanup.
                }
            } catch (_: SecurityException) {
                // Continue with the parent and stream cleanup even when one child is protected.
            }
        }
        return try {
            try {
                process.destroyForcibly()
            } catch (_: UnsupportedOperationException) {
                try {
                    process.destroy()
                } catch (_: SecurityException) {
                    // Stream cleanup below still prevents drain-thread starvation.
                }
            } catch (_: SecurityException) {
                // Stream cleanup below still prevents drain-thread starvation.
            }
            try {
                process.waitFor(5, TimeUnit.SECONDS)
                false
            } catch (_: InterruptedException) {
                true
            }
        } finally {
            closeProcessStreams(process)
        }
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
}
