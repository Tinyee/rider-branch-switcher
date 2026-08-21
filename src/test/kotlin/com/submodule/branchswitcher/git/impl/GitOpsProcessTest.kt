package com.submodule.branchswitcher.git.impl

import org.junit.Assert.*
import org.junit.Test
import com.submodule.branchswitcher.git.GitFailureKind
import java.io.File
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * [GitOps] process-lifecycle and stream tests: operation sessions, cancellation,
 * termination escalation, permit capacity, and bounded output capture. Parsing and
 * query scenarios live in [GitOpsTest].
 */
class GitOpsProcessTest : GitOpsTestBase() {

    @Test
    fun `cancelled session rejects its subsequent commands until closed`() {
        val operation = git.openOperation()
        operation.cancel()

        val cancelled = operation.fetch(tmpDir.toFile())
        assertEquals(-1, cancelled.exitCode)
        assertEquals("cancelled", cancelled.stderr)

        operation.close()
        val afterEnd = git.fetch(tmpDir.toFile())
        assertNotEquals("cancelled", afterEnd.stderr)
    }

    @Test
    fun `cancelling one session does not affect another session`() {
        val cancelledOperation = git.openOperation()
        val activeOperation = git.openOperation()
        cancelledOperation.cancel()

        assertEquals("cancelled", cancelledOperation.fetch(tmpDir.toFile()).stderr)
        assertNotEquals("cancelled", activeOperation.fetch(tmpDir.toFile()).stderr)

        cancelledOperation.close()
        activeOperation.close()
    }

    @Test
    fun `session cancel terminates running process and leaves direct commands available`() {
        val runningProcess = ControllableProcess(finished = false)
        var starts = 0
        git = GitOps(timeoutSeconds = 10) {
            starts++
            if (starts == 1) runningProcess else ControllableProcess(finished = true)
        }
        val operation = git.openOperation()

        val resultFuture = CompletableFuture.supplyAsync { operation.fetch(tmpDir.toFile()) }
        assertTrue(runningProcess.waitStarted.await(5, TimeUnit.SECONDS))
        operation.cancel()

        val cancelled = resultFuture.get(5, TimeUnit.SECONDS)
        assertEquals("cancelled", cancelled.stderr)
        assertTrue(runningProcess.destroyed)

        operation.close()
        assertTrue(git.fetch(tmpDir.toFile()).ok)
    }

    @Test
    fun `thread interruption terminates process and remains visible to caller`() {
        val runningProcess = ControllableProcess(finished = false, interruptOnWait = true)
        git = GitOps(timeoutSeconds = 10) { runningProcess }

        try {
            val result = git.fetch(tmpDir.toFile())

            assertEquals(GitFailureKind.INTERRUPTED, result.failureKind)
            assertTrue(runningProcess.destroyed)
            assertTrue("runner must preserve the interruption signal", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `interruption during cancellation cleanup remains visible to caller`() {
        val cancellation = AtomicBoolean(false)
        val runningProcess = ControllableProcess(
            finished = false,
            interruptAfterDestroy = true,
            onWait = { cancellation.set(true) },
        )
        val runner = GitProcessRunner(timeoutSeconds = 10) { runningProcess }

        try {
            val result = runner.run(tmpDir.toFile(), cancellation, "fetch")

            assertEquals(GitFailureKind.INTERRUPTED, result.failureKind)
            assertTrue(runningProcess.destroyed)
            assertTrue("cleanup must preserve the interruption signal", Thread.currentThread().isInterrupted)
        } finally {
            Thread.interrupted()
        }
    }

    @Test
    fun `cancellation terminates descendants before releasing process resources`() {
        val descendantDestroyed = AtomicBoolean(false)
        val descendant = java.lang.reflect.Proxy.newProxyInstance(
            ProcessHandle::class.java.classLoader,
            arrayOf(ProcessHandle::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "pid" -> 1234L
                "isAlive" -> !descendantDestroyed.get()
                "destroyForcibly" -> descendantDestroyed.compareAndSet(false, true)
                else -> null
            }
        } as ProcessHandle
        val cancellation = AtomicBoolean(false)
        val runningProcess = ControllableProcess(
            finished = false,
            descendant = descendant,
            onWait = { cancellation.set(true) },
        )
        val runner = GitProcessRunner(timeoutSeconds = 10) { runningProcess }

        val result = runner.run(tmpDir.toFile(), cancellation, "fetch")

        assertEquals(GitFailureKind.CANCELLED, result.failureKind)
        assertTrue(descendantDestroyed.get())
        assertTrue(runningProcess.destroyed)
    }

    @Test
    fun `cooperative exit on SIGTERM avoids a SIGKILL fallback`() {
        val cancellation = AtomicBoolean(false)
        val runningProcess = ControllableProcess(
            finished = false,
            stopAfterDestroy = true,
            onWait = { cancellation.set(true) },
        )
        val runner = GitProcessRunner(
            timeoutSeconds = 10,
            gracefulTerminationSupported = true,
        ) { runningProcess }

        val result = runner.run(tmpDir.toFile(), cancellation, "fetch")

        assertEquals(GitFailureKind.CANCELLED, result.failureKind)
        assertTrue("SIGTERM must be attempted first", runningProcess.gracefulDestroyRequested)
        assertFalse(
            "a cooperative exit must not be force-killed (git removes its own index.lock)",
            runningProcess.forceDestroyRequested,
        )
    }

    @Test
    fun `stubborn process is escalated to SIGKILL after a SIGTERM grace window`() {
        val cancellation = AtomicBoolean(false)
        val runningProcess = ControllableProcess(
            finished = false,
            stopAfterDestroy = false,
            onWait = { cancellation.set(true) },
        )
        val runner = GitProcessRunner(
            timeoutSeconds = 10,
            gracefulTerminationSupported = true,
        ) { runningProcess }

        val result = runner.run(tmpDir.toFile(), cancellation, "fetch")

        assertEquals(GitFailureKind.CANCELLED, result.failureKind)
        assertTrue("SIGTERM must be attempted first", runningProcess.gracefulDestroyRequested)
        assertTrue("a stubborn process must be force-killed", runningProcess.forceDestroyRequested)
    }

    @Test
    fun `grace window waits for a live descendant after the parent exits`() {
        val descendantDestroyed = AtomicBoolean(false)
        val forceDestroyAt = AtomicBoolean(false)
        val descendant = java.lang.reflect.Proxy.newProxyInstance(
            ProcessHandle::class.java.classLoader,
            arrayOf(ProcessHandle::class.java),
        ) { _, method, _ ->
            when (method.name) {
                "pid" -> 2345L
                "isAlive" -> !descendantDestroyed.get()
                "destroy" -> null
                "destroyForcibly" -> {
                    forceDestroyAt.set(true)
                    descendantDestroyed.set(true)
                    null
                }
                else -> null
            }
        } as ProcessHandle
        val cancellation = AtomicBoolean(false)
        val runningProcess = ControllableProcess(
            finished = false,
            descendant = descendant,
            onWait = { cancellation.set(true) },
        )
        val runner = GitProcessRunner(
            timeoutSeconds = 10,
            gracefulTerminationSupported = true,
        ) { runningProcess }

        val started = System.nanoTime()
        val result = runner.run(tmpDir.toFile(), cancellation, "fetch")
        val elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

        assertEquals(GitFailureKind.CANCELLED, result.failureKind)
        assertTrue(forceDestroyAt.get())
        assertTrue("descendant must receive the full grace window", elapsedMillis >= 1_000)
    }

    @Test
    fun `windows termination skips the unsupported graceful phase`() {
        val cancellation = AtomicBoolean(false)
        val runningProcess = ControllableProcess(finished = false, onWait = { cancellation.set(true) })
        val runner = GitProcessRunner(
            timeoutSeconds = 10,
            gracefulTerminationSupported = false,
        ) { runningProcess }

        val result = runner.run(tmpDir.toFile(), cancellation, "fetch")

        assertEquals(GitFailureKind.CANCELLED, result.failureKind)
        assertFalse(runningProcess.gracefulDestroyRequested)
        assertTrue(runningProcess.forceDestroyRequested)
    }

    @Test
    fun `process capacity wait is bounded and does not start a command`() {
        var starts = 0
        val runner = GitProcessRunner(
            timeoutSeconds = 1,
            processPermits = Semaphore(0),
        ) {
            starts++
            ControllableProcess(finished = true)
        }

        val result = runner.run(tmpDir.toFile(), AtomicBoolean(false), "status")

        assertEquals(GitFailureKind.PROCESS_CAPACITY, result.failureKind)
        assertEquals("process capacity unavailable after 1s", result.stderr)
        assertEquals(0, starts)
    }

    @Test
    fun `permit wait and process execution share a single timeout budget`() {
        val permits = Semaphore(1)
        val runningProcess = ControllableProcess(finished = false, stopAfterDestroy = true)
        val processStarted = CountDownLatch(1)
        val processStartNanos = AtomicLong()
        val runner = GitProcessRunner(timeoutSeconds = 2, processPermits = permits) {
            processStartNanos.set(System.nanoTime())
            processStarted.countDown()
            runningProcess
        }

        // Hold the only permit for ~half the budget: permit acquisition must eat into
        // the process deadline instead of getting a fresh full budget of its own.
        permits.acquire()
        val resultFuture = CompletableFuture.supplyAsync {
            runner.run(tmpDir.toFile(), AtomicBoolean(false), "fetch")
        }
        Thread.sleep(1000)
        permits.release()
        assertTrue(
            "process should start after the permit is released",
            processStarted.await(5, TimeUnit.SECONDS),
        )

        val result = resultFuture.get(5, TimeUnit.SECONDS)

        assertEquals(GitFailureKind.TIMEOUT, result.failureKind)
        assertEquals("timeout after 2s", result.stderr)
        val processRuntimeMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - processStartNanos.get())
        assertTrue(
            "process ran ~1s (2s deadline minus ~1s permit wait), not a fresh ~2s budget; was ${processRuntimeMillis}ms",
            processRuntimeMillis in 500..1500,
        )
    }

    @Test
    fun `unsupported exit future releases capacity only after a stubborn process exits`() {
        val permits = Semaphore(1)
        val cancellation = AtomicBoolean(false)
        val process = ControllableProcess(
            finished = false,
            stopAfterDestroy = false,
            onExitUnsupported = true,
            onWait = { cancellation.set(true) },
        )
        val runner = GitProcessRunner(timeoutSeconds = 1, processPermits = permits) { process }

        val result = runner.run(tmpDir.toFile(), cancellation, "status")

        assertEquals(GitFailureKind.CANCELLED, result.failureKind)
        assertEquals(0, permits.availablePermits())
        process.completeExit()
        repeat(20) {
            if (permits.availablePermits() == 1) return@repeat
            Thread.sleep(50)
        }
        assertEquals(1, permits.availablePermits())
    }

    @Test
    fun `stdout above the capture limit fails instead of returning partial data`() {
        val oversized = ByteArray(GIT_STDOUT_LIMIT_BYTES + 1) { 'x'.code.toByte() }
        val process = ControllableProcess(finished = true, stdout = oversized)
        val runner = GitProcessRunner(timeoutSeconds = 10) { process }

        val result = runner.run(tmpDir.toFile(), AtomicBoolean(false), "status")

        assertEquals(GitFailureKind.OUTPUT_LIMIT, result.failureKind)
        assertTrue(result.stdout.isEmpty())
        assertTrue(result.stderr.contains("stdout exceeded"))
    }

    @Test
    fun `stderr retains a bounded tail on dedicated drain threads`() {
        val suffix = "diagnostic-tail"
        val oversized = ("x".repeat(GIT_STDERR_TAIL_BYTES) + suffix).toByteArray()
        val stdoutThread = java.util.concurrent.atomic.AtomicReference<String>()
        val stderrThread = java.util.concurrent.atomic.AtomicReference<String>()
        val process = ControllableProcess(
            finished = true,
            exitCode = 1,
            stdoutStream = RecordingInputStream("ok".byteInputStream(), stdoutThread),
            stderrStream = RecordingInputStream(oversized.inputStream(), stderrThread),
        )
        val runner = GitProcessRunner(timeoutSeconds = 10) { process }

        val result = runner.run(tmpDir.toFile(), AtomicBoolean(false), "fetch")

        assertEquals(GitFailureKind.GIT_FAILED, result.failureKind)
        assertTrue(result.stderr.startsWith("[stderr truncated"))
        assertTrue(result.stderr.endsWith(suffix))
        assertTrue(stdoutThread.get().startsWith(GIT_DRAIN_THREAD_PREFIX))
        assertTrue(stderrThread.get().startsWith(GIT_DRAIN_THREAD_PREFIX))
    }

    @Test
    fun `stream capture failure is returned instead of silently losing command output`() {
        val failingStream = object : InputStream() {
            override fun read(): Int = throw java.io.IOException("stream unavailable")
        }
        val process = ControllableProcess(finished = true, stdoutStream = failingStream)
        val runner = GitProcessRunner(timeoutSeconds = 10) { process }

        val result = runner.run(tmpDir.toFile(), AtomicBoolean(false), "status")

        assertEquals(GitFailureKind.OUTPUT_CAPTURE, result.failureKind)
        assertTrue(result.stderr.contains("java.io.IOException: stream unavailable"))
    }

    @Test
    fun `permit is released even when a live process never exits`() {
        val permits = Semaphore(1)
        val runner = GitProcessRunner(
            timeoutSeconds = 2,
            processPermits = permits,
            pollDeadlineSeconds = 1,
        ) {
            // Never exits, and destroying it does not stop it: onExit() never completes,
            // so the onExit-based deferral would hold the permit forever without the deadline.
            ControllableProcess(finished = false, stopAfterDestroy = false)
        }

        val result = runner.run(tmpDir.toFile(), AtomicBoolean(false), "stuck")

        assertTrue("the stuck command must report a timeout", result.stderr.startsWith("timeout after"))
        assertTrue(
            "the permit must come back after the bounded deferral, not leak forever",
            permits.tryAcquire(5, TimeUnit.SECONDS),
        )
    }

    @Test
    @Suppress("TooGenericExceptionThrown") // simulating an arbitrary probe failure
    fun `probe failure with an unstoppable process still returns the pool slot`() {
        val permits = Semaphore(1)
        val runner = GitProcessRunner(
            timeoutSeconds = 2,
            processPermits = permits,
            pollDeadlineSeconds = 1,
        ) {
            // Unstoppable process: destroy() cannot confirm it stopped, so terminateProcess
            // reports resourcesStopped=false and the probe-failure path must defer the
            // release through completedOutcome (bounded), never hand the slot back to an
            // orphaned process and never leak it. An immediate synchronous release would
            // both leave the orphan running and drain the pool.
            object : ControllableProcess(finished = false, stopAfterDestroy = false) {
                private var probed = false
                override fun waitFor(timeout: Long, unit: TimeUnit): Boolean {
                    // Only the initial probe fails (as an unqueryable handle would); the
                    // calls terminateProcess makes during its bounded wait stay normal.
                    if (!probed) {
                        probed = true
                        throw RuntimeException("probe boom")
                    }
                    return super.waitFor(timeout, unit)
                }
            }
        }

        val result = runner.run(tmpDir.toFile(), AtomicBoolean(false), "stuck")

        assertTrue("the probe failure must surface as a result", result.stderr.startsWith("process probe failed"))
        assertTrue("capacity must be released after a probe failure", permits.tryAcquire(5, TimeUnit.SECONDS))
    }

    @Test
    fun `stdout exactly at the limit is not treated as exceeded`() {
        val exact = ByteArray(GIT_STDOUT_LIMIT_BYTES) { 'x'.code.toByte() }
        val process = ControllableProcess(finished = true, stdout = exact)
        val runner = GitProcessRunner(timeoutSeconds = 10) { process }

        val result = runner.run(tmpDir.toFile(), AtomicBoolean(false), "status")

        assertEquals("exactly-at-limit output is not truncated", GIT_STDOUT_LIMIT_BYTES, result.stdout.length)
        assertFalse(result.stderr.contains("stdout exceeded"))
    }
}
