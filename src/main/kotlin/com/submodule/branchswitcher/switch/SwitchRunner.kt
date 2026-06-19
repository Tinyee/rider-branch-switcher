package com.submodule.branchswitcher.switch

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.git.GitClient
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import kotlinx.coroutines.CancellationException
import java.nio.file.Path

data class SwitchRunResult(
    val ok: Boolean,
    val cancelled: Boolean,
    val executor: SwitchExecutor?,
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
    private val gitClient: GitClient,
    private val taskRunner: TaskBridge.TaskRunner = TaskBridge.TaskRunner.DEFAULT,
) {
    @Suppress("TooGenericExceptionCaught")
    suspend fun execute(
        title: String,
        request: ResolvedSwitchRequest,
        log: AppLogger,
        progress: (ProgressIndicator) -> ProgressIndicator = { it },
        beforeExecute: (ProgressIndicator) -> Boolean = { true },
    ): SwitchRunResult {
        var ok = false
        var cancelled = false
        var executor: SwitchExecutor? = null

        gitClient.beginOperation()
        try {
            TaskBridge.runBackground(taskRunner, project, title, true,
                block = { indicator ->
                    indicator.isIndeterminate = true
                    if (!beforeExecute(indicator)) {
                        cancelled = true
                        return@runBackground
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
                    val currentExecutor = SwitchExecutor(root, log, gitClient, cancelHandle, progHandle,
                        onConfirmSubmoduleInit = initConfirm)
                    executor = currentExecutor
                    ok = currentExecutor.execute(request)
                },
                onCancel = { gitClient.cancel() },
                onFinished = { gitClient.endOperation() },
            )
        } catch (_: CancellationException) {
            log.info("[cancelled] switch cancelled by user")
            cancelled = true
        } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
            log.info("[cancelled] switch cancelled via IDE progress")
            cancelled = true
        } catch (e: RuntimeException) {
            // Boundary catch: convert unexpected switch failures into a result so UI callers
            // can notify consistently without leaking coroutine failures.
            log.error("switch: ${e.javaClass.simpleName}: ${e.message}")
            ok = false
        }

        return SwitchRunResult(ok = ok, cancelled = cancelled, executor = executor)
    }
}
