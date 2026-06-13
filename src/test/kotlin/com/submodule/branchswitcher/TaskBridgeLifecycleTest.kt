package com.submodule.branchswitcher

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.util.AbstractProgressIndicatorBase
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Direct lifecycle tests for [TaskBridge.runBackground].
 *
 * Covers the contract:
 *  - Normal completion -> onFinished once, result Unit.
 *  - Block exception -> onFinished still once, exception propagated.
 *  - User cancel -> onCancel once + onFinished once.
 *  - Parent cancel before onRun -> block never executes, onCancel fires.
 *  - Parent cancel during onRun -> indicator cancelled.
 *  - Callbacks are idempotent under multiple triggers.
 */
class TaskBridgeLifecycleTest {

    /** Minimal [ProgressIndicator] that supports cancel/isCanceled. */
    private class FakeIndicator : AbstractProgressIndicatorBase() {
        override fun cancel() {
            super.cancel()
        }

        override fun isCanceled(): Boolean = super.isCanceled()
    }

    /**
     * Test fake that records the callbacks so tests control exactly when
     * each lifecycle event fires — no [com.intellij.openapi.progress.Task.Backgroundable] involved.
     */
    private class FakeTaskRunner : TaskBridge.TaskRunner {
        var onRun: ((ProgressIndicator) -> Unit)? = null
            private set
        var onFinish: (() -> Unit)? = null
            private set
        var onCancelCb: (() -> Unit)? = null
            private set

        override fun run(
            project: com.intellij.openapi.project.Project?,
            title: String,
            canBeCancelled: Boolean,
            onRun: (ProgressIndicator) -> Unit,
            onFinished: () -> Unit,
            onCancel: () -> Unit,
        ) {
            this.onRun = onRun
            this.onFinish = onFinished
            this.onCancelCb = onCancel
        }

        fun simulateRun(indicator: ProgressIndicator) {
            requireNotNull(onRun) { "onRun not captured — did runBackground actually invoke runner.run()?" }
            onRun!!(indicator)
        }

        fun simulateCancel() {
            requireNotNull(onCancelCb) { "onCancel not captured" }
            onCancelCb!!()
        }

        fun simulateFinish() {
            requireNotNull(onFinish) { "onFinish not captured" }
            onFinish!!()
        }
    }

    private lateinit var runner: FakeTaskRunner
    private var cancelCallCount = 0
    private var finishCallCount = 0

    @Before
    fun setUp() {
        runner = FakeTaskRunner()
        cancelCallCount = 0
        finishCallCount = 0
    }

    // -- Scenario 1: normal completion -------------------------------------

    @Test
    fun `normal completion invokes onFinished once and returns Unit`() = runBlocking {
        var blockRan = false

        val job = launch {
            TaskBridge.runBackground(
                runner, null, "test", true,
                onCancel = { cancelCallCount++ },
                onFinished = { finishCallCount++ },
            ) {
                blockRan = true
            }
        }

        yield() // let the launched coroutine enter suspendCancellableCoroutine
        assertNotNull("onRun should be captured", runner.onRun)

        runner.simulateRun(FakeIndicator())
        assertTrue("block should have run", blockRan)
        assertEquals("onFinished must not fire before simulateFinish", 0, finishCallCount)

        runner.simulateFinish()
        job.join()

        assertEquals("onFinished must fire exactly once", 1, finishCallCount)
        assertEquals("onCancel must not fire on normal completion", 0, cancelCallCount)
        assertTrue("job should be completed", job.isCompleted)
    }

    // -- Scenario 2: block throws exception --------------------------------

    @Test
    @Suppress("TooGenericExceptionThrown")
    fun `block exception invokes onFinished and propagates error`() = runBlocking {
        var caught: Throwable? = null

        val job = launch {
            try {
                TaskBridge.runBackground(
                    runner, null, "test", true,
                    onCancel = { cancelCallCount++ },
                    onFinished = { finishCallCount++ },
                ) {
                    throw IllegalStateException("block failed")
                }
            } catch (e: Exception) {
                caught = e
            }
        }

        yield() // let the launched coroutine enter suspendCancellableCoroutine
        assertNotNull("onRun should be captured", runner.onRun)

        runner.simulateRun(FakeIndicator())
        // Block threw -> completeContinuation(Result.failure(ex)) already called
        // onFinished() fires next
        runner.simulateFinish()
        job.join()

        assertEquals("onFinished must fire once after exception", 1, finishCallCount)
        assertEquals("onCancel must not fire", 0, cancelCallCount)
        val error = caught
        assertTrue("exception must propagate to caller", error is IllegalStateException && error.message == "block failed")
    }

    // -- Scenario 3: user cancel (dialog cancel button) --------------------

    @Test
    fun `user cancel invokes onCancel then onFinished`() = runBlocking {
        var blockRan = false

        val job = launch {
            TaskBridge.runBackground(
                runner, null, "test", true,
                onCancel = { cancelCallCount++ },
                onFinished = { finishCallCount++ },
            ) {
                blockRan = true
            }
        }

        yield() // let the launched coroutine enter suspendCancellableCoroutine
        assertNotNull("onRun should be captured", runner.onRun)

        runner.simulateRun(FakeIndicator())
        assertTrue("block should have run", blockRan)

        runner.simulateCancel()
        assertEquals("onCancel must fire after simulateCancel", 1, cancelCallCount)

        runner.simulateFinish()
        assertEquals("onFinished must fire after simulateFinish", 1, finishCallCount)
        assertEquals("onCancel count must remain 1", 1, cancelCallCount)

        job.join()
        assertTrue("job should be cancelled", job.isCancelled)
    }

    // -- Scenario 4: parent cancel before onRun starts --------------------

    @Test
    fun `parent cancel before task run prevents block execution`() = runBlocking {
        var blockRan = false

        val job = launch {
            TaskBridge.runBackground(
                runner, null, "test", true,
                onCancel = { cancelCallCount++ },
                onFinished = { finishCallCount++ },
            ) {
                blockRan = true
            }
        }

        // Coroutine has suspended inside suspendCancellableCoroutine; runner has the callbacks.
        yield() // let the launched coroutine enter suspendCancellableCoroutine
        assertNotNull("onRun should be captured", runner.onRun)

        job.cancel()
        // invokeOnCancellation fires: cancelled=true, cancelCallback invoked

        runner.simulateRun(FakeIndicator())
        assertFalse("block must NOT run when cancelled before onRun", blockRan)

        runner.simulateFinish()
        assertEquals("onCancel must fire once", 1, cancelCallCount)
        assertEquals("onFinished must fire once", 1, finishCallCount)

        job.join()
    }

    // -- Scenario 5: parent cancel during onRun ---------------------------

    @Test
    fun `parent cancel during task run cancels indicator`() = runBlocking {
        val indicator = FakeIndicator()
        val blockStarted = CountDownLatch(1)
        val letBlockFinish = CountDownLatch(1)

        val job = launch {
            TaskBridge.runBackground(
                runner, null, "test", true,
                onCancel = { cancelCallCount++ },
                onFinished = { finishCallCount++ },
            ) {
                blockStarted.countDown()
                letBlockFinish.await(5, TimeUnit.SECONDS)
            }
        }

        yield() // let the launched coroutine enter suspendCancellableCoroutine
        assertNotNull("onRun should be captured", runner.onRun)

        // Run block in a separate thread so we can cancel while it blocks
        val runnerThread = Thread({
            runner.simulateRun(indicator)
        }, "test-runner").also { it.start() }

        assertTrue("block should have started", blockStarted.await(5, TimeUnit.SECONDS))
        assertFalse("indicator must not be cancelled yet", indicator.isCanceled)

        job.cancel()
        // invokeOnCancellation -> cancelled=true, cancelCallback, indicator.cancel()

        assertTrue("indicator must be cancelled after job.cancel()", indicator.isCanceled)
        assertEquals("onCancel must fire once", 1, cancelCallCount)

        letBlockFinish.countDown()
        runnerThread.join(5000)

        runner.simulateFinish()
        assertEquals("onFinished must fire once", 1, finishCallCount)

        job.join()
    }

    // -- Scenario 6: callbacks are idempotent ------------------------------

    @Test
    fun `callbacks execute at most once under multiple triggers`() = runBlocking {
        val job = launch {
            TaskBridge.runBackground(
                runner, null, "test", true,
                onCancel = { cancelCallCount++ },
                onFinished = { finishCallCount++ },
            ) { /* no-op block */ }
        }

        yield() // let the launched coroutine enter suspendCancellableCoroutine
        assertNotNull("onRun should be captured", runner.onRun)

        runner.simulateRun(FakeIndicator())

        // Simulate multiple cancel triggers
        runner.simulateCancel()
        job.cancel() // invokeOnCancellation tries cancelCallback again
        runner.simulateCancel()

        assertEquals("onCancel must fire exactly once", 1, cancelCallCount)

        // Simulate multiple finish triggers
        runner.simulateFinish()
        runner.simulateFinish()

        assertEquals("onFinished must fire exactly once", 1, finishCallCount)

        job.join()
    }

    // -- Scenario 7: runner throws synchronously ----------------------------

    @Test
    @Suppress("TooGenericExceptionThrown")
    fun `runner throws exception propagates to caller`() = runBlocking {
        val ex = RuntimeException("runner failed")
        val throwingRunner = object : TaskBridge.TaskRunner {
            override fun run(
                project: com.intellij.openapi.project.Project?,
                title: String,
                canBeCancelled: Boolean,
                onRun: (ProgressIndicator) -> Unit,
                onFinished: () -> Unit,
                onCancel: () -> Unit,
            ) {
                throw ex
            }
        }

        var caught: Throwable? = null
        try {
            TaskBridge.runBackground(
                throwingRunner, null, "test", true,
                onCancel = { cancelCallCount++ },
                onFinished = { finishCallCount++ },
            ) { /* never runs */ }
        } catch (e: Exception) {
            caught = e
        }

        assertTrue("runner exception must propagate", caught is RuntimeException && caught.message == "runner failed")
        assertEquals("onFinished must not fire when runner throws", 0, finishCallCount)
        assertEquals("onCancel must not fire when runner throws", 0, cancelCallCount)
    }

    // -- Scenario 8: onCancel callback throws -------------------------------

    @Test
    @Suppress("TooGenericExceptionThrown")
    fun `onCancel callback exception is contained and cancellation completes`() = runBlocking {
        val job = launch {
            TaskBridge.runBackground(
                runner, null, "test", true,
                onCancel = {
                    cancelCallCount++
                    throw IllegalStateException("cancel callback failed")
                },
                onFinished = { finishCallCount++ },
            ) { /* no-op */ }
        }

        yield() // let the launched coroutine enter suspendCancellableCoroutine
        assertNotNull("onRun should be captured", runner.onRun)

        runner.simulateRun(FakeIndicator())
        runner.simulateCancel()

        assertEquals("onCancel must fire once despite throwing", 1, cancelCallCount)

        runner.simulateFinish()
        assertEquals("onFinished must fire once", 1, finishCallCount)

        job.join()
        assertTrue("job must be cancelled", job.isCancelled)
    }

    // -- Scenario 9: onFinished callback throws -----------------------------

    @Test
    @Suppress("TooGenericExceptionThrown")
    fun `onFinished callback exception is contained and continuation completes`() = runBlocking {
        var blockRan = false
        val job = launch {
            TaskBridge.runBackground(
                runner, null, "test", true,
                onCancel = { cancelCallCount++ },
                onFinished = {
                    finishCallCount++
                    throw IllegalStateException("finish callback failed")
                },
            ) {
                blockRan = true
            }
        }

        yield() // let the launched coroutine enter suspendCancellableCoroutine
        assertNotNull("onRun should be captured", runner.onRun)

        runner.simulateRun(FakeIndicator())
        assertTrue("block should have run", blockRan)

        runner.simulateFinish()
        assertEquals("onFinished must fire once despite throwing", 1, finishCallCount)

        job.join()
        assertTrue("job must complete normally", job.isCompleted)
    }
}
