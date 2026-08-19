package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.OperationContext
import com.submodule.branchswitcher.log.logFailure
import com.submodule.branchswitcher.log.newOperationContext
import com.submodule.branchswitcher.log.withContext
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.operation.GitOperationResult
import com.submodule.branchswitcher.operation.GitOperationRunner
import com.submodule.branchswitcher.switch.CancellationClassifier
import com.submodule.branchswitcher.switch.OperationIssue
import com.submodule.branchswitcher.switch.OperationIssueCode
import com.submodule.branchswitcher.switch.OperationIssueSeverity
import com.submodule.branchswitcher.switch.OperationStage
import com.submodule.branchswitcher.switch.SwitchExecutionResult
import com.submodule.branchswitcher.switch.SwitchExecutionStatus
import com.submodule.branchswitcher.switch.SwitchExecutor
import com.submodule.branchswitcher.switch.SwitchRecoveryExecutor
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
    private val cancellationClassifier: CancellationClassifier = CancellationClassifier.DEFAULT,
    private val preApprovedSubmoduleInit: Set<String> = emptySet(),
) {
    /** Runs one request; cancellation after mutation uses a fresh session for recovery. */
    suspend fun execute(
        title: String,
        request: ResolvedSwitchRequest,
        log: AppLogger,
        operationContext: OperationContext = newOperationContext("switch"),
    ): SwitchRunResult = withContext(Dispatchers.IO) {
        executeOnWorker(title, request, log, operationContext)
    }

    private suspend fun executeOnWorker(
        title: String,
        request: ResolvedSwitchRequest,
        log: AppLogger,
        operationContext: OperationContext,
    ): SwitchRunResult {
        val operationId = operationContext.id
        val operationLog = log.withContext(operationContext.inPhase("execute"))
        val preset = request.preset
        val options = request.options
        operationLog.activity(
            "operation started: root=${projectRoot.toAbsolutePath().normalize()}, " +
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
            operationLog.logGitRuntime(operation, projectRoot.toFile(), cancellationClassifier)
            SwitchExecutor(
                projectRoot,
                operationLog,
                operation,
                indicator,
                indicator,
                cancellationClassifier = cancellationClassifier,
                preApprovedSubmoduleInit = preApprovedSubmoduleInit,
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
            val recovered = recoverSwitch(executionResult, recoveryLog)
            executionResult = recovered.execution
            recoveryResult = recovered.recovery
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
    private fun recoverSwitch(
        execution: SwitchExecutionResult,
        log: AppLogger,
    ): RecoveredSwitchOutcome {
        // The cancelled GitOperationSession rejects every later command, and the
        // completed session is closed once the background runner returns. Recovery
        // therefore always requires a fresh operation session.
        val recoveryOperation = try {
            operations.openOperation()
        } catch (e: RuntimeException) {
            log.logFailure("cancel recovery session could not be opened", e)
            return RecoveredSwitchOutcome(
                execution = execution,
                recovery = SwitchRecoveryResult(
                    rollbackOk = false,
                    issues = listOf(recoveryIssue(OperationIssueCode.RECOVERY_SESSION_UNAVAILABLE)),
                ),
            )
        }
        return try {
            val recoveryExecutor = SwitchRecoveryExecutor(projectRoot, log, recoveryOperation)
            val recoveryOutcome = recoveryExecutor.recover(execution)
            RecoveredSwitchOutcome(
                execution = execution.copy(state = recoveryOutcome.stashRestore.state),
                recovery = SwitchRecoveryResult(
                    rollbackOk = recoveryOutcome.rollbackOk,
                    issues = recoveryOutcome.issues,
                ),
            )
        } catch (e: RuntimeException) {
            log.logFailure("recovery failed", e)
            RecoveredSwitchOutcome(
                execution = execution,
                recovery = SwitchRecoveryResult(
                    rollbackOk = false,
                    issues = listOf(recoveryIssue(OperationIssueCode.RECOVERY_FAILED)),
                ),
            )
        } finally {
            recoveryOperation.close()
        }
    }

    private fun recoveryIssue(code: OperationIssueCode) = OperationIssue(
        stage = OperationStage.RECOVERY,
        code = code,
        repositoryPath = ".",
        severity = OperationIssueSeverity.ERROR,
    )
}
