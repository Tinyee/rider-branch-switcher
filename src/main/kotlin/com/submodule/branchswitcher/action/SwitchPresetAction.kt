package com.submodule.branchswitcher.action

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.BranchSwitchListener
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.log.createStringAppender
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.service.BranchSwitcherService
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
        service.loadPresets()
        val presets = service.presets
        if (presets.isEmpty()) {
            Messages.showInfoMessage(project, Bundle.msg("action.no.presets"), Bundle.msg("plugin.title"))
            return
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
        val root = project.basePath?.let { java.nio.file.Paths.get(it) } ?: return
        val logLines = mutableListOf<String>()
        val collector = createStringAppender { logLines += it }
        val coordinator = SwitchFlowCoordinator(project, service)
        service.scope.launch(Dispatchers.Default) {
            try {
                val probeResult = coordinator.preflight(root, preset)
                if (!coordinator.showForceWarning(preset, probeResult)) {
                    logLines += "[warn] switch cancelled by user — Force dirty strategy declined"
                    return@launch
                }
                if (!coordinator.showPreflightWarnings(probeResult)) {
                    logLines += "[warn] switch cancelled by user due to preflight warnings"
                    return@launch
                }
                val request = service.resolveSwitchRequest(preset)
                coordinator.executeAndNotify(root, request, collector) {
                    project.messageBus.syncPublisher(BranchSwitchListener.TOPIC).onBranchSwitched()
                }
            } catch (_: kotlinx.coroutines.CancellationException) {
                // user cancelled
            }
        }
    }
}
