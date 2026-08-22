package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.OperationContext
import com.submodule.branchswitcher.log.logFailure
import com.submodule.branchswitcher.log.newOperationContext
import com.submodule.branchswitcher.log.withContext
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.operation.GitOperationResult
import com.submodule.branchswitcher.operation.GitOperationRunner
import com.submodule.branchswitcher.switch.OperationIssue
import com.submodule.branchswitcher.switch.OperationIssueCode
import com.submodule.branchswitcher.switch.recoveryIssue
import com.submodule.branchswitcher.switch.SwitchExecutionResult
import com.submodule.branchswitcher.switch.SwitchExecutionStatus
import com.submodule.branchswitcher.switch.SwitchExecutor
import com.submodule.branchswitcher.switch.SwitchRecoveryExecutor
import com.submodule.branchswitcher.switch.SwitchRecoveryOutcome
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Path

data class SwitchRunResult(
    val operationId: String,
    val cancelled: Boolean,
    val execution: SwitchExecutionResult?,
    val recovery: SwitchRecoveryResult? = null,
) {
    val ok: Boolean get() = !cancelled && execution?.ok == true
}

data class SwitchRecoveryResult(
    val rollbackOk: Boolean,
    val issues: List<OperationIssue>,
) {
    val ok: Boolean get() = rollbackOk && issues.isEmpty()
}

private data class BackgroundSwitchOutcome(
    val cancelled: Boolean,
    val execution: SwitchExecutionResult?,
)

private data class RecoveredSwitchOutcome(
    val execution: SwitchExecutionResult,
    val recovery: SwitchRecoveryResult,
)

/**
 * Shared execution path for all switch entry points.
 *
 * UI layers may still own preview/confirmation, but operation lifecycle,
 * cancellation and SwitchExecutor invocation must stay centralized here.
 */
class SwitchRunner(
    private val projectRoot: Path,
    private val operations: GitOperationRunner,
    private val preApprovedSubmoduleInit: Set<String> = emptySet(),
    private val collisionDiscards: Map<String, Set<String>> = emptyMap(),
) {
    /** Runs one request; cancellation after mutation uses a fresh session for recovery. */
    suspend fun execute(
        title: String,
        request: ResolvedSwitchRequest,
        log: AppLogger,
        operationContext: OperationContext = newOperationContext("switch"),
        recoveryTitle: String,
        stashRestoreTitle: String,
    ): SwitchRunResult = withContext(Dispatchers.IO) {
        executeOnWorker(title, request, log, operationContext, recoveryTitle, stashRestoreTitle)
    }

    private suspend fun executeOnWorker(
        title: String,
        request: ResolvedSwitchRequest,
        log: AppLogger,
        operationContext: OperationContext,
        recoveryTitle: String,
        stashRestoreTitle: String,
    ): SwitchRunResult {
        val operationId = operationContext.id
        val operationLog = log.withContext(operationContext.inPhase("execute"))
        val preset = request.preset
        val options = request.options
        operationLog.activity(
            "operation started: root=${projectRoot.fileName?.toString() ?: projectRoot.toString()}, " +
                "preset='${preset.name}', targets=${preset.targets().size}",
        )
        operationLog.info(
            "options: dirty=${options.dirty}, fetchFirst=${options.fetchFirst}, " +
                "pull=${options.pull}, confirmBeforeInit=${options.confirmBeforeInit}",
        )
        preset.targets().forEach { target ->
            operationLog.info("requested target: path=${target.path}, branch=${target.branch}")
        }
        val backgroundResult = operations.run(title) { indicator, operation ->
            indicator.isIndeterminate = true
            operationLog.logGitRuntime(operation, projectRoot.toFile())
            SwitchExecutor(
                projectRoot,
                operationLog,
                operation,
                indicator,
                indicator,
                preApprovedSubmoduleInit = preApprovedSubmoduleInit,
                collisionDiscards = collisionDiscards,
            ).execute(request)
        }

        val backgroundOutcome = interpretBackgroundResult(backgroundResult, operationLog)
        var wasCancelled = backgroundOutcome.cancelled
        var executionResult = backgroundOutcome.execution
        var recoveryResult: SwitchRecoveryResult? = null

        if (executionResult?.cancelled == true) {
            wasCancelled = true
        }
        // Recovery runs for cancellations and for FAILED results that recorded a
        // checkpoint: repositories may already be mutated before the failure, and the
        // stash restore is deferred until after the rollback so the trees stay clean.
        val needsRecovery = wasCancelled ||
            (executionResult != null &&
                executionResult.checkpoint != null &&
                executionResult.status == SwitchExecutionStatus.FAILED)
        if (needsRecovery && executionResult != null) {
            val recoveryLog = log.withContext(operationContext.inPhase("recovery"))
            val recovered = recoverSwitch(executionResult, recoveryLog, recoveryTitle)
            executionResult = recovered.execution
            recoveryResult = recovered.recovery
        }

        // A SUCCESS/PARTIAL switch whose end-of-pipeline stash restore left retryable
        // entries (a stale index.lock race, an interrupted apply) gets one automatic
        // stash-only retry: the repositories are already at their targets, so this never
        // rolls anything back. An explicit user cancel during the restore is recorded on
        // the execution and suppresses the retry.
        if (executionResult != null && needsStashRetry(executionResult)) {
            executionResult = retryStashRestore(executionResult, log, operationContext, stashRestoreTitle)
        }

        val result = SwitchRunResult(
            operationId = operationId,
            cancelled = wasCancelled,
            execution = executionResult,
            recovery = recoveryResult,
        )
        operationLog.activity(
            "operation finished: cancelled=${result.cancelled}, " +
                "status=${result.execution?.status ?: "unavailable"}, " +
                "issues=${result.execution?.issues?.size ?: 0}, " +
                "recovery=${result.recovery?.let { if (it.ok) "ok" else "failed" } ?: "not-needed"}",
        )
        return result
    }

    private fun interpretBackgroundResult(
        result: GitOperationResult<SwitchExecutionResult>,
        log: AppLogger,
    ): BackgroundSwitchOutcome {
        return when (result) {
            is GitOperationResult.Completed -> BackgroundSwitchOutcome(
                cancelled = false,
                execution = result.value,
            )
            is GitOperationResult.Cancelled -> {
                log.info("[cancelled] switch cancelled by user")
                BackgroundSwitchOutcome(cancelled = true, execution = result.value)
            }
            is GitOperationResult.Failed -> {
                val error = result.error
                log.logFailure("switch workflow failed", error)
                BackgroundSwitchOutcome(cancelled = false, execution = null)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // recovery must return a report instead of escaping
    private suspend fun recoverSwitch(
        execution: SwitchExecutionResult,
        log: AppLogger,
        recoveryTitle: String,
    ): RecoveredSwitchOutcome {
        // Recovery runs in its own background task so a slow rollback is visible and
        // cancellable: GitBackgroundRunner owns the fresh session's open/cancel/close,
        // and the executor's cancellation lambda stops it between repositories.
        val recoveryResult = operations.run(recoveryTitle) { indicator, operation ->
            indicator.isIndeterminate = true
            indicator.text = recoveryTitle
            SwitchRecoveryExecutor(
                projectRoot,
                log,
                operation,
                operationControl = indicator,
            ).recover(execution)
        }
        return when (recoveryResult) {
            is GitOperationResult.Completed -> toRecoveredOutcome(execution, recoveryResult.value)
            is GitOperationResult.Cancelled -> {
                val outcome = recoveryResult.value
                if (outcome != null) {
                    toRecoveredOutcome(execution, outcome)
                } else {
                    RecoveredSwitchOutcome(
                        execution = execution,
                        recovery = SwitchRecoveryResult(
                            rollbackOk = false,
                            issues = listOf(recoveryIssue(".", OperationIssueCode.RECOVERY_FAILED)),
                        ),
                    )
                }
            }
            is GitOperationResult.Failed -> {
                log.logFailure("switch recovery failed", recoveryResult.error)
                RecoveredSwitchOutcome(
                    execution = execution,
                    recovery = SwitchRecoveryResult(
                        rollbackOk = false,
                        issues = listOf(recoveryIssue(".", OperationIssueCode.RECOVERY_FAILED)),
                    ),
                )
            }
        }
    }

    private fun toRecoveredOutcome(
        execution: SwitchExecutionResult,
        outcome: SwitchRecoveryOutcome,
    ): RecoveredSwitchOutcome = RecoveredSwitchOutcome(
        execution = execution.copy(state = outcome.stashRestore.state),
        recovery = SwitchRecoveryResult(
            rollbackOk = outcome.rollbackOk,
            issues = outcome.issues,
        ),
    )

    /**
     * True when a completed switch left stash entries that were never marked attempted
     * (a lock race or an interrupted apply) and the restore was not stopped by a user
     * cancel. These are exactly the entries a retry can make progress on; an apply that
     * failed with Git refusing is already marked attempted and must not be re-applied.
     */
    private fun needsStashRetry(execution: SwitchExecutionResult): Boolean =
        (execution.status == SwitchExecutionStatus.SUCCESS ||
            execution.status == SwitchExecutionStatus.PARTIAL) &&
            !execution.stashRestoreInterrupted &&
            execution.state.stashesSnapshot().any { !it.restoreAttempted }

    /**
     * One stash-only retry for a SUCCESS/PARTIAL switch: restores the entries the inline
     * restore could not apply without rolling any repository back. Runs in its own
     * background task so it is visible and cancellable; at-most-once is preserved because
     * [com.submodule.branchswitcher.switch.restoreTrackedStashes] skips attempted entries.
     */
    private suspend fun retryStashRestore(
        execution: SwitchExecutionResult,
        log: AppLogger,
        operationContext: OperationContext,
        title: String,
    ): SwitchExecutionResult {
        val retryLog = log.withContext(operationContext.inPhase("stash-restore"))
        val retryResult = operations.run(title) { indicator, operation ->
            indicator.isIndeterminate = true
            indicator.text = title
            val executor = SwitchRecoveryExecutor(
                projectRoot,
                retryLog,
                operation,
                operationControl = indicator,
            )
            executor.retryStashRestore(execution)
        }
        return when (retryResult) {
            is GitOperationResult.Completed -> {
                val restore = retryResult.value
                execution.copy(
                    state = restore.state,
                    issues = execution.issues + restore.issues,
                    stashRestoreInterrupted = restore.interrupted,
                )
            }
            is GitOperationResult.Cancelled -> {
                // The user cancelled the retry; leave the result as-is so the remaining
                // WIP stays tracked and visible instead of being silently dropped.
                execution
            }
            is GitOperationResult.Failed -> {
                log.logFailure("[stash restore] retry failed", retryResult.error)
                execution
            }
        }
    }
}
