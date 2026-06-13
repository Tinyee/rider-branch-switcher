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
        checkpoint: Map<String, CheckpointEntry> = emptyMap(),
        cancelled: Boolean = false,
    ) = DeriveBranchExecutor.DeriveResult(succeeded, branchExists, skipped, dirty, branchMismatch, preflightError, checkpointFailed, failed, checkpoint, cancelled)

    @Test
    fun `cancelled with rollback failures returns ROLLBACK_FAILED`() {
        val d = deriveNotification(cancelled = true, result(succeeded = listOf("a")), rollbackFailureCount = 2, "feat")
        assertTrue(d is DeriveNotification.Failure)
        assertEquals(DeriveNotification.Reason.ROLLBACK_FAILED, (d as DeriveNotification.Failure).reason)
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
        assertEquals(DeriveNotification.Reason.UNEXPECTED, (d as DeriveNotification.Failure).reason)
    }

    @Test
    fun `preflight blocked returns Blocked with category counts`() {
        val d = deriveNotification(cancelled = false,
            result(branchExists = listOf("a"), dirty = listOf("b", "c")),
            rollbackFailureCount = 0, "feat")
        assertTrue(d is DeriveNotification.Blocked)
        val b = d as DeriveNotification.Blocked
        assertEquals(1, b.branchExistsCount)
        assertEquals(2, b.dirtyCount)
        assertEquals(0, b.skippedCount)
    }

    @Test
    fun `checkpoint blocked returns Blocked with checkpoint count`() {
        val d = deriveNotification(cancelled = false,
            result(checkpointFailed = listOf("a", "b")),
            rollbackFailureCount = 0, "feat")
        assertTrue(d is DeriveNotification.Blocked)
        assertEquals(2, (d as DeriveNotification.Blocked).checkpointFailedCount)
    }

    @Test
    fun `all ok returns Success`() {
        val r = result(succeeded = listOf("a", "b", "c"))
        val d = deriveNotification(cancelled = false, r, rollbackFailureCount = 0, "feat")
        assertTrue(d is DeriveNotification.Success)
        assertEquals(3, (d as DeriveNotification.Success).repoCount)
    }

    @Test
    fun `partial with rollback failures returns ROLLBACK_FAILED`() {
        val d = deriveNotification(cancelled = false,
            result(succeeded = listOf("a"), failed = mapOf("b" to "err")),
            rollbackFailureCount = 1, "feat")
        assertTrue(d is DeriveNotification.Failure)
        assertEquals(DeriveNotification.Reason.ROLLBACK_FAILED, (d as DeriveNotification.Failure).reason)
    }

    @Test
    fun `partial without rollback failures returns PARTIAL`() {
        val d = deriveNotification(cancelled = false,
            result(succeeded = listOf("a"), failed = mapOf("b" to "err")),
            rollbackFailureCount = 0, "feat")
        assertTrue(d is DeriveNotification.Failure)
        assertEquals(DeriveNotification.Reason.PARTIAL, (d as DeriveNotification.Failure).reason)
    }

    @Test
    fun `branch name validation accepts valid git branch shorthands`() {
        listOf("feature/test", "release-1.2", "user_name/topic").forEach {
            assertTrue("Expected valid branch name: $it", isValidBranchName(it))
        }
    }

    @Test
    fun `branch name validation rejects invalid git branch shorthands`() {
        listOf("", " ", "-feature", "/feature", "feature/", "feature//test", ".hidden",
            "feature/.hidden", "feature.", "feature.lock/test", "feature/test.lock",
            "feature..test", "feature@{test", "feature test", "feature\u0001test").forEach {
            assertFalse("Expected invalid branch name: $it", isValidBranchName(it))
        }
    }
}
