package com.submodule.branchswitcher.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.BranchSwitchListener
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.Strings
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.Preset
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
            Messages.showInfoMessage(project, "暂无预设，请先在 Branch Switcher 工具窗口创建", Strings.pluginTitle)
            return
        }
        val names = presets.map { it.name }.toTypedArray()
        @Suppress("DEPRECATION")
        val choice = Messages.showChooseDialog(
            "选择要切换到的 Preset:",
            "切到 Preset",
            names,
            names[0],
            null,
        )
        if (choice == -1) return
        val preset = presets[choice]
        executeSwitch(project, service, preset)
    }

    private fun executeSwitch(project: Project, service: BranchSwitcherService, preset: Preset) {
        val root = project.basePath?.let { Paths.get(it) } ?: return
        val task = object : Task.Backgroundable(project, "Switching to ${preset.name}", true) {
            var ok = false
            private val logLines = mutableListOf<String>()
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                // Run preflight and check for blockers
                val preflight = SwitchPreflight(service.gitClient)
                val probeResult = preflight.probe(root, preset, indicator)
                val missingDirs = probeResult.filter { !it.exists }
                val missingBranches = probeResult.filter { it.branchMissing }
                if (missingDirs.isNotEmpty()) {
                    val names = missingDirs.joinToString(", ") { it.label }
                    logLines += "[warn] 目录缺失: $names"
                }
                if (missingBranches.isNotEmpty()) {
                    val names = missingBranches.joinToString(", ") { it.label }
                    logLines += "[warn] 分支不存在 (本地/远端): $names"
                }
                // Execute switch with log capture
                val executor = SwitchExecutor(root, { logLines += it }, service.gitClient, indicator)
                ok = executor.execute(preset, SwitchOptions(DirtyAction.Stash, fetchFirst = service.fetchFirst, pull = service.pullAfterSwitch))
            }
            override fun onFinished() {
                if (ok) {
                    Notifier.info(project, Strings.switchComplete, Strings.switchCompleteMsg.format(preset.name))
                } else {
                    val detail = logLines.filter { it.contains("[fail]") || it.contains("[fatal]") || it.contains("[warn]") }
                        .take(3).joinToString("\n")
                    Notifier.error(project, Strings.switchFailed,
                        if (detail.isNotEmpty()) "${Strings.switchPartialMsg.format(preset.name)}:\n$detail"
                        else Strings.switchPartialMsg.format(preset.name))
                }
                project.messageBus.syncPublisher(BranchSwitchListener.TOPIC).onBranchSwitched()
                // Refresh VCS
                ApplicationManager.getApplication().executeOnPooledThread {
                    val dirs = mutableListOf(root.toFile())
                    preset.submodules.keys.forEach { dirs += root.resolve(it).toFile() }
                    val lfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                    val mgr = git4idea.repo.GitRepositoryManager.getInstance(project)
                    for (dir in dirs) {
                        val vf = lfs.refreshAndFindFileByIoFile(dir) ?: continue
                        vf.refresh(false, true)
                        mgr.getRepositoryForRoot(vf)?.update()
                    }
                }
            }
        }
        ProgressManager.getInstance().run(task)
    }
}
