package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.switch.CheckpointEntry
import com.submodule.branchswitcher.switch.OperationIssue
import com.submodule.branchswitcher.switch.OperationIssueCode
import com.submodule.branchswitcher.switch.OperationIssueSeverity
import com.submodule.branchswitcher.switch.OperationStage
import com.submodule.branchswitcher.switch.SwitchExecutionResult
import com.submodule.branchswitcher.switch.SwitchExecutionStatus
import com.submodule.branchswitcher.switch.SwitchState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the lock guidance merged into a failed-recovery notification. The automatic rollback's
 * own index.lock failures live in the recovery issues, not in the execution issues, so a
 * presenter that only reads [SwitchExecutionResult.issues] withholds the one instruction that
 * lets the user unblock the retry — while still offering the retry button that fails on the
 * very same lock.
 */
class SwitchResultPresenterLockDetailTest {

    @Test
    fun `a lock held by the rollback alone still reaches the notification`() {
        val detail = lockBlockedDetail(execution(issues = emptyList()), listOf(lockIssue(".")))

        assertTrue("the rollback's own lock must be reported, got: '$detail'", detail.isNotEmpty())
        assertTrue("the message must name the repository", detail.contains(Bundle.msg("label.main.repo")))
        assertTrue("the message must name the lock file", detail.contains("index.lock"))
    }

    @Test
    fun `a lock from the switch itself is reported as before`() {
        val detail = lockBlockedDetail(execution(issues = listOf(lockIssue("SubA"))), emptyList())

        assertTrue("a switch-stage lock must still be reported, got: '$detail'", detail.contains("SubA"))
    }

    @Test
    fun `both lock sources are reported, not just the first`() {
        val detail = lockBlockedDetail(
            execution(issues = listOf(lockIssue("SubA"))),
            listOf(lockIssue("SubB")),
        )

        assertTrue("the switch-stage lock must survive the merge", detail.contains("SubA"))
        assertTrue("the recovery-stage lock must survive the merge", detail.contains("SubB"))
    }

    @Test
    fun `a recovery failure without a lock adds no spurious lock line`() {
        val unrelated = OperationIssue(
            stage = OperationStage.RECOVERY,
            code = OperationIssueCode.RECOVERY_FAILED,
            repositoryPath = "SubA",
            severity = OperationIssueSeverity.ERROR,
            diagnostic = "reset --hard failed",
        )

        val detail = lockBlockedDetail(execution(issues = emptyList()), listOf(unrelated))

        assertFalse("only INDEX_LOCK_BLOCKING produces a lock line, got: '$detail'", detail.contains("index.lock"))
    }

    /**
     * The end the user actually reads: a recovery blocked by a lock must arrive in the composed
     * notification with its instruction intact, not merely exist as a helper's return value.
     */
    @Test
    fun `a rollback blocked by a lock reaches the composed notification`() {
        val exec = execution(issues = emptyList())
        val message = partialFailureMessage(
            "Work",
            "",
            lockBlockedDetail(exec, listOf(lockIssue("."))),
        )

        assertTrue("the composed message must carry the lock file", message.contains("index.lock"))
        assertTrue("the composed message must carry the delete-and-retry instruction", message.contains("retry"))
    }

    private fun execution(issues: List<OperationIssue>): SwitchExecutionResult =
        SwitchExecutionResult(
            status = SwitchExecutionStatus.FAILED,
            checkpoint = mapOf("." to CheckpointEntry("abc123", "main")),
            state = SwitchState(),
            issues = issues,
        )

    private fun lockIssue(path: String): OperationIssue =
        OperationIssue(
            stage = OperationStage.RECOVERY,
            code = OperationIssueCode.INDEX_LOCK_BLOCKING,
            repositoryPath = path,
            severity = OperationIssueSeverity.ERROR,
            lockPath = "/repo/$path/.git/index.lock",
        )
}
