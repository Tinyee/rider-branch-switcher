package com.submodule.branchswitcher.workflow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.git.GitOperationProvider
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.platform.GitBackgroundResult
import com.submodule.branchswitcher.platform.GitBackgroundRunner
import com.submodule.branchswitcher.platform.ProgressCancellationHandle
import com.submodule.branchswitcher.platform.ProgressIndicatorHandle
import com.submodule.branchswitcher.platform.platformCancellationClassifier
import com.submodule.branchswitcher.switch.SwitchExecutionResult
import com.submodule.branchswitcher.switch.SwitchExecutor
import com.submodule.branchswitcher.switch.SwitchRecoveryExecutor
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

data class SwitchRunResult(
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
    private val project: Project,
    private val root: Path,
    private val gitClient: GitOperationProvider,
    private val taskRunner: TaskBridge.TaskRunner = TaskBridge.TaskRunner.DEFAULT,
) {
    /**
     * Runs one resolved switch request inside the shared background lifecycle.
     *
     * [beforeExecute] may stop before mutation, for example when a shortcut
     * confirmation is declined. Cancellation after mutation triggers a fresh
     * Git session for rollback and pending stash restoration.
     */
    suspend fun execute(
        title: String,
        request: ResolvedSwitchRequest,
        log: AppLogger,
        progress: (ProgressIndicator) -> ProgressIndicator = { it },
        beforeExecute: (ProgressIndicator) -> Boolean = { true },
    ): SwitchRunResult {
        val backgroundResult = GitBackgroundRunner(project, gitClient, taskRunner).run(
            title,
            task@{ indicator, operation ->
                indicator.isIndeterminate = true
                if (!beforeExecute(indicator)) {
                    return@task null
                }
                val wrapped = progress(indicator)
                val cancelHandle = ProgressCancellationHandle(wrapped)
                val progHandle = ProgressIndicatorHandle(wrapped)
                SwitchExecutor(
                    root,
                    log,
                    operation,
                    cancelHandle,
                    progHandle,
                    cancellationClassifier = platformCancellationClassifier,
                    onConfirmSubmoduleInit = ::confirmSubmoduleInitialization,
                ).execute(request)
            },
        )

        val backgroundOutcome = interpretBackgroundResult(backgroundResult, log)
        var cancelled = backgroundOutcome.cancelled
        var execution = backgroundOutcome.execution
        var recovery: SwitchRecoveryResult? = null

        if (execution?.cancelled == true) {
            cancelled = true
        }
        if (cancelled && execution != null) {
            val cancelledRecovery = recoverCancelledSwitch(execution, log)
            execution = cancelledRecovery.execution
            recovery = cancelledRecovery.recovery
        }

        return SwitchRunResult(cancelled = cancelled, execution = execution, recovery = recovery)
    }

    private fun interpretBackgroundResult(
        result: GitBackgroundResult<SwitchExecutionResult?>,
        log: AppLogger,
    ): BackgroundSwitchOutcome {
        return when (result) {
            is GitBackgroundResult.Completed -> BackgroundSwitchOutcome(
                cancelled = result.value == null,
                execution = result.value,
            )
            is GitBackgroundResult.Cancelled -> {
                log.info("[cancelled] switch cancelled by user")
                BackgroundSwitchOutcome(cancelled = true, execution = result.value)
            }
            is GitBackgroundResult.Failed -> {
                val error = result.error
                log.error("switch: ${error.javaClass.simpleName}: ${error.message}")
                BackgroundSwitchOutcome(cancelled = false, execution = null)
            }
        }
    }

    private fun confirmSubmoduleInitialization(path: String): Boolean {
        val answer = AtomicInteger(Messages.NO)
        ApplicationManager.getApplication().invokeAndWait {
            answer.set(
                Messages.showYesNoDialog(
                    Bundle.msg("dialog.init.submodule", path),
                    Bundle.msg("dialog.init.title"),
                    Messages.getQuestionIcon(),
                ),
            )
        }
        return answer.get() == Messages.YES
    }

    @Suppress("TooGenericExceptionCaught") // cancellation recovery must return a report instead of escaping
    private fun recoverCancelledSwitch(
        execution: SwitchExecutionResult,
        log: AppLogger,
    ): CancelledSwitchRecovery {
        val operation = gitClient.openOperation()
        return try {
            val recovery = SwitchRecoveryExecutor(root, log, operation)
            val outcome = recovery.recover(execution)
            CancelledSwitchRecovery(
                execution = execution.copy(state = outcome.stashRestore.state),
                recovery = SwitchRecoveryResult(
                    rollbackOk = outcome.rollbackOk,
                    stashFailures = outcome.stashRestore.failures,
                ),
            )
        } catch (e: RuntimeException) {
            log.error("cancel recovery: ${e.javaClass.simpleName}: ${e.message}")
            CancelledSwitchRecovery(
                execution = execution,
                recovery = SwitchRecoveryResult(
                    rollbackOk = false,
                    stashFailures = mapOf("." to "recovery exception"),
                ),
            )
        } finally {
            operation.close()
        }
    }
}
