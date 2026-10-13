package com.submodule.branchswitcher.workflow

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers [WriteOperationLauncher]'s own contract: the rejection path, the
 * success-only delivery of [WriteOperationLauncher.launch]'s result, and the
 * completion-handler safety net.
 *
 * Kept free of `Project` on purpose — the launcher sits under every repository
 * mutation entry point, so its gate has to be verifiable without an IDE fixture.
 */
class WriteOperationLauncherTest {

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `a rejected write reports busy and never runs the operation`() = runBlocking {
        var busyCount = 0
        var ran = false
        val launcher = WriteOperationLauncher(scope(), tryAcquireWrite = { null })

        val job = launcher.launch(onBusy = { busyCount++ }) { ran = true }

        assertNull("a rejected write has no job to observe", job)
        assertEquals("the caller must be told why nothing happened", 1, busyCount)
        assertFalse("the operation must not run without the lease", ran)
    }

    @Test
    fun `a rejected write leaves the caller's result hook untouched`() = runBlocking {
        val delivered = mutableListOf<String>()
        val launcher = WriteOperationLauncher(scope(), tryAcquireWrite = { null })

        val job = launcher.launch(
            onBusy = { },
            afterRelease = { delivered.add(it) },
        ) { "should not be produced" }

        // The rejection is synchronous, so anything afterRelease would deliver has
        // already happened by the time launch() returns — no await needed.
        assertNull(job)
        assertEquals("a rejected write must not deliver a result", emptyList<String>(), delivered)
    }

    @Test
    fun `a permitted write delivers the result only after the lease is released`() = runBlocking {
        var leaseHeld = true
        val leaseHeldDuringDelivery = CompletableDeferred<Boolean>()
        val launcher = WriteOperationLauncher(scope(), tryAcquireWrite = {
            AutoCloseable { leaseHeld = false }
        })

        val job = launcher.launch(
            onBusy = { error("the lease was free") },
            afterRelease = { leaseHeldDuringDelivery.complete(leaseHeld) },
        ) { "done" }

        assertFalse(
            "the result must be delivered only once the lease is gone",
            withTimeout(LOAD_TIMEOUT_MILLIS) { leaseHeldDuringDelivery.await() },
        )
        check(job != null) { "the lease was free, so a job must exist" }
        withTimeout(LOAD_TIMEOUT_MILLIS) { job.join() }
    }

    @Test
    fun `a failing operation releases the lease and delivers no result`() = runBlocking {
        val closed = CompletableDeferred<Unit>()
        val delivered = mutableListOf<String>()
        val launcher = WriteOperationLauncher(scope(), tryAcquireWrite = {
            AutoCloseable { closed.complete(Unit) }
        })

        val job = launcher.launch(
            onBusy = { error("the lease was free") },
            afterRelease = { delivered.add(it) },
        ) { throw IllegalStateException("operation boom") }
        val captured = CompletableDeferred<Throwable?>()
        check(job != null) { "the lease was free, so a job must exist" }
        job.invokeOnCompletion(captured::complete)

        withTimeout(LOAD_TIMEOUT_MILLIS) { closed.await() }
        val failure = withTimeout(LOAD_TIMEOUT_MILLIS) { captured.await() }

        assertEquals("a failed operation is delivered through its own result type, not this hook", 0, delivered.size)
        assertEquals(
            "the operation's failure stays observable on the job",
            "operation boom",
            (failure as? IllegalStateException)?.message,
        )
    }

    @Test
    fun `the lease is released exactly once when the job completes`() = runBlocking {
        val closes = AtomicInteger(0)
        val launcher = WriteOperationLauncher(scope(), tryAcquireWrite = {
            AutoCloseable { closes.incrementAndGet() }
        })

        val job = launcher.launch(onBusy = { error("the lease was free") }) { "done" }
        withTimeout(LOAD_TIMEOUT_MILLIS) { job!!.join() }

        assertEquals("the operation path and the completion net must not both close it", 1, closes.get())
    }

    @Test
    fun `a lease is still released when the job never gets to run its body`() = runBlocking {
        // The completion net's real job: a coroutine cancelled while queued never enters
        // the operation block, so its finally never runs and only the net can free the
        // lease. A leaked lease here would wedge every later write in the session.
        val closes = AtomicInteger(0)
        val abandoned = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        abandoned.cancel()

        val launcher = WriteOperationLauncher(abandoned, tryAcquireWrite = {
            AutoCloseable { closes.incrementAndGet() }
        })
        val job = launcher.launch(onBusy = { error("the lease was free") }) { "never runs" }
        check(job != null) { "the lease was free, so a job must exist" }
        withTimeout(LOAD_TIMEOUT_MILLIS) { job.join() }

        assertEquals("a job that never ran must still release the lease", 1, closes.get())
    }

    @Test
    fun `a throwing result hook surfaces as the job's failure`() = runBlocking {
        val launcher = WriteOperationLauncher(scope(), tryAcquireWrite = { AutoCloseable { } })

        val job = launcher.launch(
            onBusy = { error("the lease was free") },
            afterRelease = { throw IllegalArgumentException("hook boom") },
        ) { "done" }
        val captured = CompletableDeferred<Throwable?>()
        check(job != null) { "the lease was free, so a job must exist" }
        job.invokeOnCompletion(captured::complete)

        val failure = withTimeout(LOAD_TIMEOUT_MILLIS) { captured.await() }
        assertTrue(
            "a caller bug in the hook must not be swallowed, was $failure",
            failure is IllegalArgumentException,
        )
    }

    private companion object {
        const val LOAD_TIMEOUT_MILLIS = 10_000L
    }
}
