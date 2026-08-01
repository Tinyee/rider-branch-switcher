package com.submodule.branchswitcher.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.BranchSwitchListener
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.ToolWindowLogger
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.platform.gitRootPath
import com.submodule.branchswitcher.presentation.ShortcutPresetLoadDecision
import com.submodule.branchswitcher.presentation.shortcutPresetLoadDecision
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.ui.invokeLaterIfAlive
import com.submodule.branchswitcher.ui.SwitchFlowCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SwitchPresetAction : AnAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.service<BranchSwitcherService>()
        service.scope.launch {
            val loadResult = service.loadPresets()
            val presets = service.presets.toList()
            project.invokeLaterIfAlive {
                choosePreset(project, service, loadResult, presets)
            }
        }
    }

    private fun choosePreset(
        project: Project,
        service: BranchSwitcherService,
        loadResult: Result<*>,
        presets: List<Preset>,
    ) {
        when (shortcutPresetLoadDecision(loadResult.isSuccess, presets.size)) {
            ShortcutPresetLoadDecision.LoadFailed -> {
                Notifier.error(project, Bundle.msg("preset.load.failed"),
                    loadResult.exceptionOrNull()?.message ?: Bundle.msg("dialog.import.failed"))
                return
            }
            ShortcutPresetLoadDecision.NoPresets -> {
                Messages.showInfoMessage(project, Bundle.msg("action.no.presets"), Bundle.msg("plugin.title"))
                return
            }
            ShortcutPresetLoadDecision.Ready -> Unit
        }
        val names = presets.map { it.name }.toTypedArray()
        val choice = Messages.showDialog(
            project,
            Bundle.msg("action.select.preset"),
            Bundle.msg("action.switch.preset"),
            names,
            0,
            null,
        )
        if (choice == -1) return
        val preset = presets[choice]
        executeSwitch(project, service, preset)
    }

    private fun executeSwitch(project: Project, service: BranchSwitcherService, preset: Preset) {
        val root = project.gitRootPath() ?: run {
            Notifier.error(project, Bundle.msg("plugin.title"), Bundle.msg("git.root.not.found"))
            return
        }
        val collector = actionLogger(project)
        val coordinator = SwitchFlowCoordinator(project, service)
        service.scope.launch(Dispatchers.Default) {
            try {
                val probeResult = coordinator.preflight(root, preset, collector)
                if (!coordinator.showForceWarning(preset, probeResult)) {
                    collector.warn("switch cancelled by user - Force dirty strategy declined")
                    return@launch
                }
                if (!coordinator.showPreflightWarnings(probeResult)) {
                    collector.warn("switch cancelled by user due to preflight warnings")
                    return@launch
                }
                val request = service.resolveSwitchRequest(preset)
                coordinator.executeAndNotify(root, request, collector) {
                    project.messageBus.syncPublisher(BranchSwitchListener.TOPIC).onBranchSwitched()
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // user cancelled
            } catch (e: Exception) {
                collector.error("shortcut switch failed", e)
                project.invokeLaterIfAlive {
                    Notifier.error(project, Bundle.msg("notify.preflight.failed"),
                        Bundle.msg("notify.preflight.failed.msg", e.javaClass.simpleName, e.message ?: ""))
                }
            }
        }
    }

    private fun actionLogger(project: Project): AppLogger = ToolWindowLogger { entry ->
        project.messageBus.syncPublisher(BranchSwitchListener.TOPIC).onLog(entry)
    }
}
