package com.submodule.branchswitcher.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.switch.SwitchExecutor
import com.submodule.branchswitcher.switch.SwitchPreflight
import java.nio.file.Paths

class SwitchPresetAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val service = project.service<BranchSwitcherService>()
        service.loadPresets()
        val presets = service.presets
        if (presets.isEmpty()) {
            Messages.showInfoMessage(project, "暂无预设，请先在 Branch Switcher 工具窗口创建", "Branch Switcher")
            return
        }
        // Show a popup list of presets
        val names = presets.map { it.name }.toTypedArray()
        val popup = JBPopupFactory.getInstance()
            .createPopupChooserBuilder(names.toList())
            .setTitle("切到 Preset")
            .setItemChosenCallback { name ->
                val preset = presets.find { it.name == name } ?: return@setItemChosenCallback
                executeSwitch(project, service, preset)
            }
            .createPopup()
        popup.showInFocusCenter()
    }

    private fun executeSwitch(project: com.intellij.openapi.project.Project, service: BranchSwitcherService, preset: com.submodule.branchswitcher.model.Preset) {
        val root = project.basePath?.let { Paths.get(it) } ?: return
        val task = object : Task.Backgroundable(project, "Switching to ${preset.name}", true) {
            override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                indicator.isIndeterminate = true
                // Run preflight
                val preflight = SwitchPreflight(service.gitClient)
                val rows = preflight.probe(root, preset, indicator)
                // Direct switch
                val executor = SwitchExecutor(root, { }, service.gitClient, indicator)
                executor.execute(preset, SwitchOptions(DirtyAction.Stash, fetchFirst = service.fetchFirst, pull = service.pullAfterSwitch))
            }
        }
        ProgressManager.getInstance().run(task)
    }
}
