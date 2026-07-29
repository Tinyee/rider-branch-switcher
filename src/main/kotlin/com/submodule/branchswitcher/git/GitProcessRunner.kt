package com.submodule.branchswitcher.git

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal fun safeTimeoutSeconds(timeoutSeconds: Int): Int =
    timeoutSeconds.coerceIn(1, 3600)

/**
 * Runs one Git process with bounded execution and cooperative cancellation.
 */
internal class GitProcessRunner(
    private val timeoutSeconds: Int,
    private val processStarter: (ProcessBuilder) -> Process,
) {
    /** Executes `git [args]` in [workDir], polling for cancellation and timeout every 100ms. */
    @Suppress("TooGenericExceptionCaught") // injected process starters must be converted to structured failures
    fun run(
        workDir: File,
        cancellation: AtomicBoolean,
        vararg args: String,
    ): GitResult {
        val commandLabel = "git ${args.joinToString(" ")}"
        if (cancellation.get()) {
            return GitResult(commandLabel, -1, "", "cancelled")
        }
        val builder = ProcessBuilder("git", *args)
            .directory(workDir)
            .redirectErrorStream(false)
        val process = try {
            processStarter(builder)
        } catch (e: Exception) {
            return GitResult(commandLabel, -1, "", "failed to start: ${e.message}")
        }

        // Drain both pipes concurrently so verbose commands cannot block on a full pipe buffer.
        val stdoutFuture = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }
        val stderrFuture = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }
        }

        val effectiveTimeoutSeconds = safeTimeoutSeconds(timeoutSeconds)
        val deadline = System.nanoTime() +
            TimeUnit.SECONDS.toNanos(effectiveTimeoutSeconds.toLong())
        var exitCode: Int
        while (true) {
            val finished = try {
                process.waitFor(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                terminateProcess(process)
                Thread.currentThread().interrupt()
                return GitResult(commandLabel, -1, "", "interrupted")
            }
            if (finished) {
                exitCode = process.exitValue()
                break
            }
            if (cancellation.get()) {
                return terminateWithResult(process, commandLabel, "cancelled")
            }
            if (System.nanoTime() - deadline >= 0) {
                return terminateWithResult(
                    process,
                    commandLabel,
                    "timeout after ${effectiveTimeoutSeconds}s",
                )
            }
        }

        val stdout = runCatching { stdoutFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
        val stderr = runCatching { stderrFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
        return GitResult(commandLabel, exitCode, stdout.trim(), stderr.trim())
    }

    private fun terminateWithResult(
        process: Process,
        commandLabel: String,
        reason: String,
    ): GitResult {
        val interrupted = terminateProcess(process)
        if (interrupted) {
            Thread.currentThread().interrupt()
        }
        return GitResult(commandLabel, -1, "", if (interrupted) "interrupted" else reason)
    }

    /** Returns true when cleanup itself was interrupted. */
    private fun terminateProcess(process: Process): Boolean {
        process.destroyForcibly()
        return try {
            process.waitFor(5, TimeUnit.SECONDS)
            false
        } catch (_: InterruptedException) {
            true
        }
    }
}
