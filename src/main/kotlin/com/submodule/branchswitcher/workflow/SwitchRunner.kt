package com.submodule.branchswitcher.workflow

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
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
    suspend fun execute(
        title: String,
        request: ResolvedSwitchRequest,
        log: AppLogger,
        progress: (ProgressIndicator) -> ProgressIndicator = { it },
        beforeExecute: (ProgressIndicator) -> Boolean = { true },
    ): SwitchRunResult {
        var cancelled = false
        var execution: SwitchExecutionResult? = null
        var recovery: SwitchRecoveryResult? = null

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
                val initConfirm: ((String) -> Boolean)? = { path ->
                    val result = java.util.concurrent.atomic.AtomicInteger(com.intellij.openapi.ui.Messages.NO)
                    com.intellij.openapi.application.ApplicationManager.getApplication()
                        .invokeAndWait {
                            result.set(com.intellij.openapi.ui.Messages.showYesNoDialog(
                                Bundle.msg("dialog.init.submodule", path),
                                Bundle.msg("dialog.init.title"),
                                com.intellij.openapi.ui.Messages.getQuestionIcon(),
                            ))
                        }
                    result.get() == com.intellij.openapi.ui.Messages.YES
                }
                SwitchExecutor(
                    root,
                    log,
                    operation,
                    cancelHandle,
                    progHandle,
                    cancellationClassifier = platformCancellationClassifier,
                    onConfirmSubmoduleInit = initConfirm,
                ).execute(request)
            },
        )
        when (backgroundResult) {
            is GitBackgroundResult.Completed -> {
                execution = backgroundResult.value
                if (execution == null) cancelled = true
            }
            is GitBackgroundResult.Cancelled -> {
                execution = backgroundResult.value
                log.info("[cancelled] switch cancelled by user")
                cancelled = true
            }
            is GitBackgroundResult.Failed -> {
                val error = backgroundResult.error
                log.error("switch: ${error.javaClass.simpleName}: ${error.message}")
            }
        }

        if (execution?.cancelled == true) cancelled = true
        val cancelledExecution = execution
        if (cancelled && cancelledExecution != null) {
            val recovered = recoverCancelledSwitch(cancelledExecution, log)
            execution = recovered.first
            recovery = recovered.second
        }

        return SwitchRunResult(cancelled = cancelled, execution = execution, recovery = recovery)
    }

    @Suppress("TooGenericExceptionCaught") // cancellation recovery must return a report instead of escaping
    private fun recoverCancelledSwitch(
        execution: SwitchExecutionResult,
        log: AppLogger,
    ): Pair<SwitchExecutionResult, SwitchRecoveryResult> {
        val operation = gitClient.openOperation()
        return try {
            val recovery = SwitchRecoveryExecutor(root, log, operation)
            val rollbackOk = execution.checkpoint.isNullOrEmpty() || recovery.rollback(execution)
            val restore = recovery.restoreTrackedStashes(execution)
            execution.copy(state = restore.state) to SwitchRecoveryResult(rollbackOk, restore.failures)
        } catch (e: RuntimeException) {
            log.error("cancel recovery: ${e.javaClass.simpleName}: ${e.message}")
            execution to SwitchRecoveryResult(
                rollbackOk = false,
                stashFailures = mapOf("." to "recovery exception"),
            )
        } finally {
            operation.close()
        }
    }
}
