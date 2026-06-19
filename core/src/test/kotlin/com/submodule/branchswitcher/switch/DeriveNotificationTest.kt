package com.submodule.branchswitcher.switch

import org.junit.Assert.*
import org.junit.Test

/** Unit tests for the pure [deriveNotification] decision function. */
class DeriveNotificationTest {

    private fun result(
        succeeded: List<String> = emptyList(),
        branchExists: List<String> = emptyList(),
        skipped: List<String> = emptyList(),
        dirty: List<String> = emptyList(),
        branchMismatch: List<String> = emptyList(),
        preflightError: List<String> = emptyList(),
        checkpointFailed: List<String> = emptyList(),
        failed: Map<String, String> = emptyMap(),
        checkpoint: Map<String, DeriveCheckpointEntry> = emptyMap(),
        cancelled: Boolean = false,
    ) = DeriveResult(succeeded, branchExists, skipped, dirty, branchMismatch, preflightError, checkpointFailed, failed, checkpoint, cancelled)

    @Test
    fun `cancelled with rollback failures returns ROLLBACK_FAILED`() {
        val d = deriveNotification(cancelled = true, result(succeeded = listOf("a")), rollbackFailureCount = 2, "feat")
        assertTrue(d is DeriveNotification.Failure)
        d as DeriveNotification.Failure
        assertEquals(DeriveNotification.Reason.ROLLBACK_FAILED, d.reason)
        assertEquals("feat", d.branchName)
        assertEquals(2, d.count)
    }

    @Test
    fun `cancelled with clean rollback is silent`() {
        val d = deriveNotification(cancelled = true, result(succeeded = listOf("a")), rollbackFailureCount = 0, "feat")
        assertTrue(d is DeriveNotification.Silent)
    }

    @Test
    fun `null result returns UNEXPECTED`() {
        val d = deriveNotification(cancelled = false, null, rollbackFailureCount = 0, "feat")
        assertTrue(d is DeriveNotification.Failure)
        d as DeriveNotification.Failure
        assertEquals(DeriveNotification.Reason.UNEXPECTED, d.reason)
        assertEquals("feat", d.branchName)
        assertEquals(0, d.count)
    }

    @Test
    fun `preflight blocked returns Blocked with category counts`() {
        val d = deriveNotification(cancelled = false,
            result(branchExists = listOf("a"), dirty = listOf("b", "c")),
            rollbackFailureCount = 0, "feat")
        assertTrue(d is DeriveNotification.Blocked)
        val b = d as DeriveNotification.Blocked
        assertEquals(1, b.branchExistsCount)
        assertEquals(0, b.skippedCount)
        assertEquals(2, b.dirtyCount)
        assertEquals(0, b.branchMismatchCount)
        assertEquals(0, b.preflightErrorCount)
        assertEquals(0, b.checkpointFailedCount)
    }

    @Test
    fun `preflight blocked reports every blocked category count`() {
        val d = deriveNotification(cancelled = false,
            result(
                branchExists = listOf("exists"),
                skipped = listOf("skipped-a", "skipped-b"),
                dirty = listOf("dirty"),
                branchMismatch = listOf("mismatch-a", "mismatch-b", "mismatch-c"),
                preflightError = listOf("error"),
                checkpointFailed = listOf("checkpoint-a", "checkpoint-b"),
            ),
            rollbackFailureCount = 0, "feat")
        assertTrue(d is DeriveNotification.Blocked)
        val b = d as DeriveNotification.Blocked
        assertEquals(1, b.branchExistsCount)
        assertEquals(2, b.skippedCount)
        assertEquals(1, b.dirtyCount)
        assertEquals(3, b.branchMismatchCount)
        assertEquals(1, b.preflightErrorCount)
        assertEquals(2, b.checkpointFailedCount)
    }

    @Test
    fun `checkpoint blocked returns Blocked with checkpoint count`() {
        val d = deriveNotification(cancelled = false,
            result(checkpointFailed = listOf("a", "b")),
            rollbackFailureCount = 0, "feat")
        assertTrue(d is DeriveNotification.Blocked)
        val b = d as DeriveNotification.Blocked
        assertEquals(0, b.branchExistsCount)
        assertEquals(0, b.skippedCount)
        assertEquals(0, b.dirtyCount)
        assertEquals(0, b.branchMismatchCount)
        assertEquals(0, b.preflightErrorCount)
        assertEquals(2, b.checkpointFailedCount)
    }

    @Test
    fun `all ok returns Success`() {
        val r = result(succeeded = listOf("a", "b", "c"))
        val d = deriveNotification(cancelled = false, r, rollbackFailureCount = 0, "feat")
        assertTrue(d is DeriveNotification.Success)
        d as DeriveNotification.Success
        assertEquals("feat", d.branchName)
        assertEquals(3, d.repoCount)
    }

    @Test
    fun `partial with rollback failures returns ROLLBACK_FAILED`() {
        val d = deriveNotification(cancelled = false,
            result(succeeded = listOf("a"), failed = mapOf("b" to "err")),
            rollbackFailureCount = 1, "feat")
        assertTrue(d is DeriveNotification.Failure)
        d as DeriveNotification.Failure
        assertEquals(DeriveNotification.Reason.ROLLBACK_FAILED, d.reason)
        assertEquals("feat", d.branchName)
        assertEquals(1, d.count)
    }

    @Test
    fun `partial without rollback failures returns PARTIAL`() {
        val d = deriveNotification(cancelled = false,
            result(succeeded = listOf("a"), failed = mapOf("b" to "err")),
            rollbackFailureCount = 0, "feat")
        assertTrue(d is DeriveNotification.Failure)
        d as DeriveNotification.Failure
        assertEquals(DeriveNotification.Reason.PARTIAL, d.reason)
        assertEquals("feat", d.branchName)
        assertEquals(1, d.count)
    }

}
