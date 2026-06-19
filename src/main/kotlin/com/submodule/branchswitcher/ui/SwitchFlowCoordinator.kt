package com.submodule.branchswitcher.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.switch.SwitchPreflight
import com.submodule.branchswitcher.switch.ProgressCancellationHandle
import com.submodule.branchswitcher.switch.SwitchExecutor
import com.submodule.branchswitcher.switch.SwitchRunner
import com.submodule.branchswitcher.switch.SwitchRunResult
import com.submodule.branchswitcher.switch.platformCancellationClassifier
import com.submodule.branchswitcher.switch.refreshVcsRepos
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Shared switch orchestration for ToolWindow and keyboard shortcut entries.
 *
 * Each entry point owns its own preflight UI (preview dialog vs simple confirm),
 * but preflight logic, force warnings, and post-execution tail are shared here.
 */
class SwitchFlowCoordinator(
    private val project: Project,
    private val service: BranchSwitcherService,
) {
    private fun uiLater(block: () -> Unit) {
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) block()
        }
    }

    /** Shared preflight: probes all repos in [preset]. */
    suspend fun preflight(root: Path, preset: Preset): List<PreflightRow> {
        val git = service.gitClient
        return TaskBridge.runModal(project, Bundle.msg("progress.preflight"), true) { indicator ->
            indicator.isIndeterminate = false
            SwitchPreflight(git, Bundle.msg("preflight.probe.error.suffix"), platformCancellationClassifier)
                .probe(root, preset, ProgressCancellationHandle(indicator)) { idx, total, label ->
                    indicator.text2 = label
                    indicator.fraction = idx.toDouble() / total
                }
        }
    }

    /** Show force warning dialog. Returns true if user confirms (or no warning needed). */
    fun showForceWarning(preset: Preset, probeResult: List<PreflightRow>): Boolean {
        val request = service.resolveSwitchRequest(preset)
        if (!shouldShowForceWarning(request, probeResult)) return true
        val confirmed = booleanArrayOf(false)
        ApplicationManager.getApplication().invokeAndWait {
            confirmed[0] = Messages.showYesNoDialog(
                project, Bundle.msg("dialog.force.confirm.msg", preset.name),
                Bundle.msg("dialog.force.confirm.title"), Messages.getWarningIcon(),
            ) == Messages.YES
        }
        return confirmed[0]
    }

    /** Show missing-dir / missing-branch warnings. Returns true if user proceeds. */
    fun showPreflightWarnings(probeResult: List<PreflightRow>): Boolean {
        val missingDirs = probeResult.filter { !it.exists }
        val missingBranches = probeResult.filter { it.branchMissing }
        if (missingDirs.isEmpty() && missingBranches.isEmpty()) return true
        val warnings = mutableListOf<String>()
        if (missingDirs.isNotEmpty())
            warnings += Bundle.msg("preflight.warn.dir.missing", missingDirs.joinToString(", ") { it.label })
        if (missingBranches.isNotEmpty())
            warnings += Bundle.msg("preflight.warn.branch.not.found", missingBranches.joinToString(", ") { it.label })
        val confirmed = booleanArrayOf(false)
        ApplicationManager.getApplication().invokeAndWait {
            confirmed[0] = Messages.showYesNoDialog(
                project,
                warnings.joinToString("\n\n") + "\n\n" + Bundle.msg("preflight.warn.continue"),
                Bundle.msg("dialog.switch.title"), Messages.getWarningIcon(),
            ) == Messages.YES
        }
        return confirmed[0]
    }

    /**
     * Execute switch + handle post-switch notification, telemetry, and VCS refresh.
     */
    fun executeAndNotify(
        root: Path,
        request: ResolvedSwitchRequest,
        log: AppLogger,
        onSuccess: (() -> Unit)? = null,
        onFailure: ((SwitchRunResult) -> Unit)? = null,
        onFinished: (() -> Unit)? = null,
    ) {
        val preset = request.preset
        if (!service.tryStartWrite()) {
            uiLater {
                Notifier.warn(project, Bundle.msg("notify.write.busy"), Bundle.msg("notify.write.busy.msg"))
                onFinished?.invoke()
            }
            return
        }
        service.scope.launch(Dispatchers.Default) {
            try {
                val result = SwitchRunner(project, root, service.gitClient).execute(
                    title = Bundle.msg("progress.switching"), request = request, log = log,
                )
                uiLater {
                    if (result.cancelled) { onFinished?.invoke(); return@uiLater }
                    if (result.ok) {
                        service.telemetry.incrementSwitch()
                        service.addHistory(preset.name, preset.id)
                        onSuccess?.invoke()
                        Notifier.info(project, Bundle.msg("switch.complete"),
                            Bundle.msg("notify.switch.complete.msg", preset.name))
                    } else {
                        service.telemetry.incrementError()
                        onFailure?.invoke(result)
                        val executor = result.executor
                        if (executor?.getCheckpoint() != null) {
                            Notifier.rollbackAction(project, Bundle.msg("switch.failed"),
                                Bundle.msg("notify.switch.partial.msg", preset.name) +
                                    Bundle.msg("notify.switch.rollback.hint")
                            ) { rollbackSwitch(executor) }
                        } else {
                            Notifier.error(project, Bundle.msg("switch.failed"),
                                Bundle.msg("notify.switch.partial.msg", preset.name))
                        }
                    }
                    onFinished?.invoke()
                    refreshVcsRepos(project, root, preset.submodules.keys)
                }
            } finally {
                service.endWrite()
            }
        }
    }

    private fun rollbackSwitch(executor: SwitchExecutor) {
        if (!service.tryStartWrite()) {
            uiLater { Notifier.warn(project, Bundle.msg("notify.write.busy"), Bundle.msg("notify.write.busy.msg")) }
            return
        }
        val gitClient = service.gitClient
        service.scope.launch(Dispatchers.Default) {
            try {
                var rollbackOk = false
                gitClient.beginOperation()
                try {
                    TaskBridge.runBackground(project, Bundle.msg("progress.rollback"), true,
                        block = { indicator ->
                            indicator.isIndeterminate = true
                            indicator.text = Bundle.msg("progress.rollback")
                            rollbackOk = executor.rollback()
                        },
                        onCancel = { gitClient.cancel() },
                        onFinished = { gitClient.endOperation() },
                    )
                } catch (_: CancellationException) {}
                catch (_: com.intellij.openapi.progress.ProcessCanceledException) {}
                catch (_: RuntimeException) {}
                uiLater {
                    if (rollbackOk) Notifier.info(project, Bundle.msg("rollback.complete"),
                        Bundle.msg("notify.rollback.complete.msg"))
                    else Notifier.error(project, Bundle.msg("rollback.failed"),
                        Bundle.msg("notify.rollback.partial.msg"))
                }
            } finally {
                service.endWrite()
            }
        }
    }
}
