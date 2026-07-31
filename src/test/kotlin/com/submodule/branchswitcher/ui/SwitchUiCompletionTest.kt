package com.submodule.branchswitcher.ui

import kotlinx.coroutines.Job
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SwitchUiCompletionTest {
    @Test
    fun `background failure schedules cleanup exactly once`() {
        val scheduled = mutableListOf<() -> Unit>()
        var cleanupCount = 0
        val failure = IllegalStateException("refresh failed")
        var reportedFailure: Throwable? = null
        val completion = SwitchUiCompletion(scheduled::add) { cleanupCount++ }
        val job = Job()

        completion.completeWhenFailed(job) { reportedFailure = it }
        job.completeExceptionally(failure)

        assertEquals(0, cleanupCount)
        assertEquals(1, scheduled.size)
        assertTrue(reportedFailure === failure)
        scheduled.single().invoke()
        completion.completeAfter { }
        assertEquals(1, cleanupCount)
    }

    @Test
    fun `presentation failure still performs cleanup`() {
        var cleanupCount = 0
        val completion = SwitchUiCompletion({ it() }) { cleanupCount++ }
        var thrown: Throwable? = null

        try {
            completion.completeAfter { throw IllegalArgumentException("presentation failed") }
        } catch (error: Throwable) {
            thrown = error
        }

        assertTrue(thrown is IllegalArgumentException)
        assertEquals(1, cleanupCount)
    }
}
