package com.submodule.branchswitcher.workflow

import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.newOperationId
import com.submodule.branchswitcher.log.withContext
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.operation.GitOperationResult
import com.submodule.branchswitcher.operation.GitOperationRunner
import com.submodule.branchswitcher.switch.CancellationClassifier
import com.submodule.branchswitcher.switch.SwitchExecutionResult
import com.submodule.branchswitcher.switch.SwitchExecutor
import com.submodule.branchswitcher.switch.SwitchRecoveryExecutor
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
    val stashFailures: Map<String, String>,
) {
    val ok: Boolean get() = rollbackOk && stashFailures.isEmpty()
}

private data class BackgroundSwitchOutcome(
    val cancelled: Boolean,
    val execution: SwitchExecutionResult?,
)

private data class CancelledSwitchRecovery(
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
    private val confirmSubmoduleInitialization: (String) -> Boolean,
) {
    /** Runs one request; cancellation after mutation uses a fresh session for recovery. */
    suspend fun execute(
        title: String,
        request: ResolvedSwitchRequest,
        log: AppLogger,
    ): SwitchRunResult {
        val operationId = newOperationId("switch")
        val operationLog = log.withContext(operationId)
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
            operationLog.logGitRuntime(operation, projectRoot.toFile())
            SwitchExecutor(
                projectRoot,
                operationLog,
                operation,
                indicator,
                indicator,
                cancellationClassifier = cancellationClassifier,
                onConfirmSubmoduleInit = confirmSubmoduleInitialization,
            ).execute(request)
        }

        val backgroundOutcome = interpretBackgroundResult(backgroundResult, operationLog)
        var wasCancelled = backgroundOutcome.cancelled
        var executionResult = backgroundOutcome.execution
        var recoveryResult: SwitchRecoveryResult? = null

        if (executionResult?.cancelled == true) {
            wasCancelled = true
        }
        if (wasCancelled && executionResult != null) {
            val cancelledRecovery = recoverCancelledSwitch(executionResult, operationLog)
            executionResult = cancelledRecovery.execution
            recoveryResult = cancelledRecovery.recovery
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
                "failures=${result.execution?.failures?.size ?: 0}, " +
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
                log.error("switch workflow failed", error)
                BackgroundSwitchOutcome(cancelled = false, execution = null)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught") // cancellation recovery must return a report instead of escaping
    private fun recoverCancelledSwitch(
        execution: SwitchExecutionResult,
        log: AppLogger,
    ): CancelledSwitchRecovery {
        // GitOperationSession remains cancelled after cancel() and rejects every
        // later command. Recovery therefore requires a new session after the
        // background runner has closed the cancelled one.
        val recoveryOperation = try {
            operations.openOperation()
        } catch (e: RuntimeException) {
            log.error("cancel recovery session could not be opened", e)
            return CancelledSwitchRecovery(
                execution = execution,
                recovery = SwitchRecoveryResult(
                    rollbackOk = false,
                    stashFailures = mapOf("." to "could not open recovery session"),
                ),
            )
        }
        return try {
            val recoveryExecutor = SwitchRecoveryExecutor(projectRoot, log, recoveryOperation)
            val recoveryOutcome = recoveryExecutor.recover(execution)
            CancelledSwitchRecovery(
                execution = execution.copy(state = recoveryOutcome.stashRestore.state),
                recovery = SwitchRecoveryResult(
                    rollbackOk = recoveryOutcome.rollbackOk,
                    stashFailures = recoveryOutcome.stashRestore.failures,
                ),
            )
        } catch (e: RuntimeException) {
            log.error("cancel recovery failed", e)
            CancelledSwitchRecovery(
                execution = execution,
                recovery = SwitchRecoveryResult(
                    rollbackOk = false,
                    stashFailures = mapOf("." to "recovery exception"),
                ),
            )
        } finally {
            recoveryOperation.close()
        }
    }
}
