package com.submodule.branchswitcher.ui

import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.switch.OperationIssue
import com.submodule.branchswitcher.switch.OperationIssueCode
import com.submodule.branchswitcher.switch.OperationIssueSeverity
import com.submodule.branchswitcher.switch.OperationStage
import com.submodule.branchswitcher.switch.SwitchExecutionResult
import com.submodule.branchswitcher.switch.SwitchExecutionStatus
import com.submodule.branchswitcher.switch.SwitchState
import com.submodule.branchswitcher.workflow.SwitchRecoveryResult
import com.submodule.branchswitcher.workflow.SwitchRunResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision table behind [SwitchResultPresenter]'s public methods.
 *
 * `SwitchResultPresenterTest` and `SwitchResultPresenterLockDetailTest` are named after this
 * class but only exercise the file-level string helpers at the bottom of it. The methods
 * themselves — which outcome gets which notification, and the rules that decide — had no
 * caller-side coverage at all, because the class took a `Project`. [SwitchResultSink] removes
 * that: this drives every branch with no platform and no fixture.
 */
class SwitchResultPresenterDecisionTest {

    // ── the failure that must NOT offer a rollback ────────────────────────────

    @Test
    fun `a switch blocked by an index lock reports the lock and offers no rollback`() {
        // The rule: a stale index.lock blocks the switch before any mutation, so there is
        // nothing to roll back. Offering the action would invite a retry that cannot work.
        val delivered = RecordingSink()
        val presenter = SwitchResultPresenter(delivered)
        var rollbackOffered = false

        presenter.presentSwitchResult(
            preset = PRESET,
            runResult = failure(issues = listOf(lockIssue())),
            onSuccess = null,
            onRollback = { rollbackOffered = true },
            operationId = "op-1",
        )

        assertEquals("only the error is delivered", listOf("error"), delivered.kinds)
        assertTrue(
            "the lock line must reach the user, was: ${delivered.lastContent}",
            delivered.lastContent!!.contains("index.lock"),
        )
        assertEquals("a lock-blocked switch must not offer a rollback action", 0, delivered.rollbackActions)
        assertEquals("...and must not invoke the caller's rollback either", false, rollbackOffered)
    }

    // ── the failure that MUST offer one ───────────────────────────────────────

    @Test
    fun `a failed switch with a checkpoint offers the rollback action`() {
        val delivered = RecordingSink()
        val presenter = SwitchResultPresenter(delivered)

        presenter.presentSwitchResult(
            preset = PRESET,
            runResult = failure(checkpoint = mapOf("." to CHECKPOINT_ENTRY)),
            onSuccess = null,
            onRollback = {},
            operationId = "op-2",
        )

        assertEquals("the rollback action is the delivery here", 1, delivered.rollbackActions)
        assertEquals("no plain error is shown alongside it", 0, delivered.kinds.count { it == "error" })
    }

    @Test
    fun `a failed switch with no checkpoint is a plain error`() {
        // Nothing was recorded, so there is nothing to roll back to.
        val delivered = RecordingSink()
        val presenter = SwitchResultPresenter(delivered)

        presenter.presentSwitchResult(
            preset = PRESET,
            runResult = failure(checkpoint = null),
            onSuccess = null,
            onRollback = {},
            operationId = "op-3",
        )

        assertEquals(0, delivered.rollbackActions)
        assertEquals(listOf("error"), delivered.kinds)
    }

    // ── the four-way dispatch at the top ──────────────────────────────────────

    @Test
    fun `a successful switch records history and reports completion`() {
        val delivered = RecordingSink()
        val presenter = SwitchResultPresenter(delivered)
        var onSuccessRan = false

        presenter.presentSwitchResult(
            preset = PRESET,
            runResult = SwitchRunResult(
                operationId = "op-4",
                cancelled = false,
                execution = execution(),
            ),
            onSuccess = { onSuccessRan = true },
            onRollback = {},
            operationId = "op-4",
        )

        assertEquals("a completed switch belongs in the panel's history", 1, delivered.history.size)
        assertTrue("the caller's success hook runs", onSuccessRan)
        assertTrue("a clean switch is informational", delivered.kinds.contains("info"))
    }

    @Test
    fun `a cancelled switch reports only when a recovery outcome exists`() {
        val delivered = RecordingSink()
        val presenter = SwitchResultPresenter(delivered)

        // No recovery recorded: a bare cancellation is not worth a balloon, because the
        // user is the one who cancelled and the panel returns to its previous state.
        presenter.presentSwitchResult(
            preset = PRESET,
            runResult = SwitchRunResult("op-5", cancelled = true, execution = null, recovery = null),
            onSuccess = null,
            onRollback = {},
            operationId = "op-5",
        )
        assertEquals("a bare cancellation delivers nothing", emptyList<String>(), delivered.kinds)

        // With a recovery: the outcome matters, because the rollback may have been partial.
        presenter.presentSwitchResult(
            preset = PRESET,
            runResult = SwitchRunResult(
                operationId = "op-6",
                cancelled = true,
                execution = execution(),
                recovery = SwitchRecoveryResult(rollbackOk = true, issues = emptyList()),
            ),
            onSuccess = null,
            onRollback = {},
            operationId = "op-6",
        )
        assertEquals("a cancelled-but-recovered switch is reported", listOf("info"), delivered.kinds)
    }

    @Test
    fun `an automatically recovered failure refreshes the panel instead of offering a retry`() {
        val delivered = RecordingSink()
        val presenter = SwitchResultPresenter(delivered)
        var onSuccessRan = false

        presenter.presentSwitchResult(
            preset = PRESET,
            runResult = SwitchRunResult(
                operationId = "op-7",
                cancelled = false,
                execution = execution(status = SwitchExecutionStatus.FAILED),
                recovery = SwitchRecoveryResult(rollbackOk = true, issues = emptyList()),
            ),
            onSuccess = { onSuccessRan = true },
            onRollback = {},
            operationId = "op-7",
        )

        assertTrue("a successful auto-recovery refreshes the panel like a success", onSuccessRan)
        assertEquals("...and offers no manual retry, because the state is already back", 0, delivered.rollbackActions)
        assertTrue(delivered.kinds.contains("info"))
    }

    // ── rollback presentation ─────────────────────────────────────────────────

    @Test
    fun `a rollback result reports success and failure on different severities`() {
        val delivered = RecordingSink()
        val presenter = SwitchResultPresenter(delivered)

        presenter.presentRollbackResult(execution(), succeeded = true, operationId = "op-8")
        assertEquals(listOf("info"), delivered.kinds)

        presenter.presentRollbackResult(execution(), succeeded = false, operationId = "op-g")
        assertEquals(listOf("info", "error"), delivered.kinds)
    }

    // ── busy rejection ────────────────────────────────────────────────────────

    @Test
    fun `a busy rejection names the lease holder when one is recorded`() {
        val delivered = RecordingSink(holder = "write-abc123")
        val presenter = SwitchResultPresenter(delivered)
        val lines = mutableListOf<String>()

        presenter.rejectBusyWrite(com.submodule.branchswitcher.log.createStringAppender { lines.add(it) })

        assertTrue(
            "the log should name who holds the gate, was: $lines",
            lines.any { it.contains("write-abc123") },
        )
        assertEquals(listOf("warn"), delivered.kinds)
    }

    @Test
    fun `a busy rejection with no recorded holder says so without a dangling label`() {
        val delivered = RecordingSink(holder = null)
        val presenter = SwitchResultPresenter(delivered)
        val lines = mutableListOf<String>()

        presenter.rejectBusyWrite(com.submodule.branchswitcher.log.createStringAppender { lines.add(it) })

        assertTrue("the rejection is still logged", lines.any { it.contains("operation rejected") })
        assertTrue(
            "no empty '(held by )' tail, was: $lines",
            lines.none { it.contains("held by )") },
        )
    }

    private companion object {
        val PRESET = Preset(name = "Work", main = "main", submodules = emptyMap())
        val CHECKPOINT_ENTRY = com.submodule.branchswitcher.switch.CheckpointEntry(
            sha = "abc1234",
            branch = "main",
        )

        fun execution(
            status: SwitchExecutionStatus = SwitchExecutionStatus.SUCCESS,
            checkpoint: Map<String, com.submodule.branchswitcher.switch.CheckpointEntry>? = null,
            issues: List<OperationIssue> = emptyList(),
        ) = SwitchExecutionResult(
            status = status,
            checkpoint = checkpoint,
            state = SwitchState(),
            issues = issues,
        )

        /** A non-recovered failure: status FAILED, so SwitchRunResult.ok is false. */
        fun failure(
            checkpoint: Map<String, com.submodule.branchswitcher.switch.CheckpointEntry>? = null,
            issues: List<OperationIssue> = emptyList(),
        ) = SwitchRunResult(
            operationId = "op",
            cancelled = false,
            execution = execution(SwitchExecutionStatus.FAILED, checkpoint, issues),
            recovery = null,
        )

        fun lockIssue() = OperationIssue(
            stage = OperationStage.PRE_MUTATION,
            code = OperationIssueCode.INDEX_LOCK_BLOCKING,
            repositoryPath = ".",
            severity = OperationIssueSeverity.ERROR,
            lockPath = "/repo/.git/index.lock",
        )
    }

    /** Records what the presenter delivered, standing in for the balloon and the history. */
    private class RecordingSink(
        private val holder: String? = null,
    ) : SwitchResultSink {
        override val currentWriteHolder: String? get() = holder

        val kinds = mutableListOf<String>()
        val history = mutableListOf<String>()
        var rollbackActions = 0
        var lastContent: String? = null

        override fun info(title: String, content: String, operationId: String) = record("info", content)
        override fun warn(title: String, content: String, operationId: String) = record("warn", content)
        override fun error(title: String, content: String, operationId: String) = record("error", content)

        override fun rollbackAction(title: String, content: String, onRollback: () -> Unit, operationId: String) {
            record("rollbackAction", content)
            rollbackActions++
        }

        override fun recordHistory(presetName: String, presetId: String) {
            history.add(presetName)
        }

        private fun record(kind: String, content: String) {
            kinds.add(kind)
            lastContent = content
        }
    }
}
