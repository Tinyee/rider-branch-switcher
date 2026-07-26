package com.submodule.branchswitcher.git

import java.io.File
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

fun safeTimeoutMillis(timeoutSeconds: Int): Int = timeoutSeconds.coerceIn(1, 3600) * 1000

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

        val deadline = System.currentTimeMillis() + safeTimeoutMillis(timeoutSeconds)
        var exitCode: Int
        while (true) {
            val finished = try {
                process.waitFor(100, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                process.destroyForcibly()
                try {
                    process.waitFor(5, TimeUnit.SECONDS)
                } catch (_: InterruptedException) {
                    // The interrupt flag is restored below after cleanup.
                }
                Thread.currentThread().interrupt()
                return GitResult(commandLabel, -1, "", "interrupted")
            }
            if (finished) {
                exitCode = process.exitValue()
                break
            }
            if (cancellation.get()) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                return GitResult(commandLabel, -1, "", "cancelled")
            }
            if (System.currentTimeMillis() > deadline) {
                process.destroyForcibly()
                process.waitFor(5, TimeUnit.SECONDS)
                return GitResult(commandLabel, -1, "", "timeout after ${timeoutSeconds}s")
            }
        }

        val stdout = runCatching { stdoutFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
        val stderr = runCatching { stderrFuture.get(5, TimeUnit.SECONDS) }.getOrDefault("")
        return GitResult(commandLabel, exitCode, stdout.trim(), stderr.trim())
    }
}
