package com.submodule.branchswitcher.git.impl

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal const val GIT_STDOUT_LIMIT_BYTES = 8 * 1024 * 1024
internal const val GIT_STDERR_TAIL_BYTES = 128 * 1024
internal const val GIT_DRAIN_THREAD_PREFIX = "branch-switcher-git-drain-"
internal const val GIT_EXIT_WATCHER_THREAD_PREFIX = "branch-switcher-git-exit-"

/** Bound on simultaneously running git processes; also bounds concurrent state probes. */
internal const val MAX_CONCURRENT_GIT_PROCESSES = 4

/**
 * Concurrent background probe budget: the shared process pool's cap minus one slot
 * kept free for a foreground switch or recovery. Every background throttle
 * (state refresh, branch discovery) reads this single value.
 */
internal val GIT_PROCESS_BACKGROUND_BUDGET = (MAX_CONCURRENT_GIT_PROCESSES - 1).coerceAtLeast(1)
private const val STREAM_BUFFER_BYTES = 8 * 1024

/** Upper bound on the plugin-unload wait for drain threads to finish their current read. */
private const val GIT_POOL_SHUTDOWN_WAIT_SECONDS = 5L

internal data class GitCapturedOutput(
    val text: String,
    val truncated: Boolean,
)

/** Shared bounded resources used only for Git processes and their output streams. */
internal object GitProcessResources {
    val processPermits = Semaphore(MAX_CONCURRENT_GIT_PROCESSES, true)

    private val threadNumber = AtomicInteger(0)
    val streamExecutor: ExecutorService = Executors.newFixedThreadPool(MAX_CONCURRENT_GIT_PROCESSES * 2) { task ->
        Thread(task, "$GIT_DRAIN_THREAD_PREFIX${threadNumber.incrementAndGet()}").apply {
            isDaemon = true
        }
    }
    val exitWatcherExecutor: ExecutorService = Executors.newFixedThreadPool(MAX_CONCURRENT_GIT_PROCESSES) { task ->
        Thread(task, "$GIT_EXIT_WATCHER_THREAD_PREFIX${threadNumber.incrementAndGet()}").apply {
            isDaemon = true
        }
    }

    /**
     * Gracefully stops both shared pools when the plugin is unloaded.
     *
     * Daemon threads are allowed to finish their current drain; the defer-permit
     * polling loop in [GitProcessRunner.deferPermitReleaseByPolling] tolerates
     * interrupts and keeps polling until the process exits. No production code may
     * start a Git process after this is called (a submit would throw
     * `RejectedExecutionException`); the application service is disposed at plugin
     * unload / IDE exit, after the project service owning [GitOps] is closed.
     */
    @Synchronized
    fun shutdown() {
        streamExecutor.shutdown()
        exitWatcherExecutor.shutdown()
        // Bound the wait so plugin unload cannot hang on a drain stuck in read();
        // the daemon threads are reclaimed by the JVM at process exit regardless.
        awaitQuietly(streamExecutor)
        awaitQuietly(exitWatcherExecutor)
    }

    private fun awaitQuietly(executor: ExecutorService) {
        try {
            executor.awaitTermination(GIT_POOL_SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}

/** Reads process streams without allowing diagnostic output to grow without bound. */
internal class GitOutputDrainer(
    private val executor: ExecutorService = GitProcessResources.streamExecutor,
) {
    fun captureStdout(stream: InputStream, limitExceeded: AtomicBoolean): Future<GitCapturedOutput> =
        executor.submit<GitCapturedOutput> {
            capturePrefix(stream, GIT_STDOUT_LIMIT_BYTES, limitExceeded)
        }

    fun captureStderr(stream: InputStream): Future<GitCapturedOutput> =
        executor.submit<GitCapturedOutput> {
            captureTail(stream, GIT_STDERR_TAIL_BYTES)
        }

    private fun capturePrefix(
        stream: InputStream,
        limitBytes: Int,
        limitExceeded: AtomicBoolean,
    ): GitCapturedOutput {
        val output = ByteArrayOutputStream(minOf(STREAM_BUFFER_BYTES, limitBytes))
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var truncated = false
        var totalBytes = 0L
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                totalBytes += read
                val remaining = limitBytes - output.size()
                if (remaining > 0) output.write(buffer, 0, minOf(read, remaining))
                // "Exceeded" means a cumulative overflow beyond the cap. The cumulative
                // count is the self-documenting form of the overflow test (a read that
                // exactly fills the remaining quota was never flagged, per-read either)
                // and matches captureTail's accounting.
                if (totalBytes > limitBytes) {
                    truncated = true
                    limitExceeded.set(true)
                }
            }
        }
        return GitCapturedOutput(output.toString(StandardCharsets.UTF_8), truncated)
    }

    private fun captureTail(stream: InputStream, limitBytes: Int): GitCapturedOutput {
        val tail = ByteArray(limitBytes)
        val buffer = ByteArray(STREAM_BUFFER_BYTES)
        var totalBytes = 0L
        var size = 0
        var writeIndex = 0
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                totalBytes += read
                val firstPart = minOf(read, limitBytes - writeIndex)
                buffer.copyInto(tail, writeIndex, 0, firstPart)
                val secondPart = read - firstPart
                if (secondPart > 0) buffer.copyInto(tail, 0, firstPart, read)
                writeIndex = (writeIndex + read) % limitBytes
                size = minOf(limitBytes, size + read)
            }
        }

        val bytes = if (size < limitBytes) {
            tail.copyOf(size)
        } else {
            ByteArray(limitBytes).also { ordered ->
                val firstPart = limitBytes - writeIndex
                tail.copyInto(ordered, 0, writeIndex, limitBytes)
                if (writeIndex > 0) tail.copyInto(ordered, firstPart, 0, writeIndex)
            }
        }
        return GitCapturedOutput(String(bytes, StandardCharsets.UTF_8), totalBytes > limitBytes)
    }
}
