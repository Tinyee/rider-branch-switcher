package com.submodule.branchswitcher.action

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.BranchSwitchListener
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.switch.SwitchExecutor
import com.submodule.branchswitcher.switch.SwitchPreflight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SwitchPresetAction : AnAction() {
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
        @Suppress("DEPRECATION")
        val choice = Messages.showChooseDialog(
            Bundle.msg("action.select.preset"),
            Bundle.msg("action.switch.preset"),
            names,
            names[0],
            null,
        )
        if (choice == -1) return
        val preset = presets[choice]
        executeSwitch(project, service, preset)
    }

    private fun executeSwitch(project: Project, service: BranchSwitcherService, preset: Preset) {
        val root = project.basePath?.let { java.nio.file.Paths.get(it) } ?: return
        service.scope.launch(Dispatchers.Default) {
            val logLines = mutableListOf<String>()
            var ok = false
            try {
                // Run preflight + switch as a background task
                TaskBridge.runBackground(project, "Switching to ${preset.name}", true) { indicator ->
                    indicator.isIndeterminate = true
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
                    val executor = SwitchExecutor(root, { logLines += it }, service.gitClient, indicator)
                    ok = executor.execute(preset, SwitchOptions(DirtyAction.Stash, fetchFirst = service.fetchFirst, pull = service.pullAfterSwitch))
                }
            } catch (_: Exception) {
                ok = false
            }
            // Resumed on EDT via TaskBridge.onFinished
            if (ok) {
                Notifier.info(project, Bundle.msg("switch.complete"), Bundle.msg("notify.switch.complete.msg", preset.name))
            } else {
                val detail = logLines.filter { it.contains("[fail]") || it.contains("[fatal]") || it.contains("[warn]") }
                    .take(3).joinToString("\n")
                Notifier.error(project, Bundle.msg("switch.failed"),
                    if (detail.isNotEmpty()) Bundle.msg("notify.switch.partial.msg", preset.name) + ":\n" + detail
                    else Bundle.msg("notify.switch.partial.msg", preset.name))
            }
            project.messageBus.syncPublisher(BranchSwitchListener.TOPIC).onBranchSwitched()
            // Refresh VCS
            service.scope.launch(Dispatchers.IO) {
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
}
