package com.submodule.branchswitcher.git

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

internal const val GIT_STDOUT_LIMIT_BYTES = 8 * 1024 * 1024
internal const val GIT_STDERR_TAIL_BYTES = 128 * 1024
internal const val GIT_DRAIN_THREAD_PREFIX = "branch-switcher-git-drain-"
internal const val GIT_EXIT_WATCHER_THREAD_PREFIX = "branch-switcher-git-exit-"

private const val MAX_CONCURRENT_GIT_PROCESSES = 4
private const val STREAM_BUFFER_BYTES = 8 * 1024

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
        stream.use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val remaining = limitBytes - output.size()
                if (remaining > 0) output.write(buffer, 0, minOf(read, remaining))
                if (read > remaining) {
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
