package com.submodule.branchswitcher.platform

import org.junit.Assert.assertEquals
import org.junit.Test

class GitTaskOutcomeTest {
    @Test
    fun `completed value survives both cancellation race orderings`() {
        val completionFirst = GitTaskOutcome<String>()
        completionFirst.recordCompletion("switched")
        completionFirst.recordCancellation()

        val cancellationFirst = GitTaskOutcome<String>()
        cancellationFirst.recordCancellation()
        cancellationFirst.recordCompletion("switched")

        val expected = GitBackgroundResult.Cancelled("switched")
        assertEquals(expected, completionFirst.result())
        assertEquals(expected, cancellationFirst.result())
    }

    @Test
    fun `normal completion remains completed`() {
        val outcome = GitTaskOutcome<String?>()

        outcome.recordCompletion(null)

        assertEquals(GitBackgroundResult.Completed(null), outcome.result())
    }
}
