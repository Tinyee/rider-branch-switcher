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
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.switch.SwitchExecutor
import com.submodule.branchswitcher.switch.SwitchPreflight
import com.submodule.branchswitcher.switch.refreshVcsRepos
import kotlinx.coroutines.CancellationException
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
            var cancelled = false
            try {
                // Run preflight + switch as a background task
                TaskBridge.runBackground(project, "Switching to ${preset.name}", true) { indicator ->
                    indicator.isIndeterminate = true
                    val preflight = SwitchPreflight(service.gitClient)
                    val probeResult = preflight.probe(root, preset, indicator)
                    val missingDirs = probeResult.filter { !it.exists }
                    val missingBranches = probeResult.filter { it.branchMissing }
                    // Show preflight warnings and confirm before proceeding
                    if (missingDirs.isNotEmpty() || missingBranches.isNotEmpty()) {
                        val warnings = mutableListOf<String>()
                        if (missingDirs.isNotEmpty()) {
                            warnings += "Directory missing: ${missingDirs.joinToString(", ") { it.label }}"
                        }
                        if (missingBranches.isNotEmpty()) {
                            warnings += "Branch not found: ${missingBranches.joinToString(", ") { it.label }}"
                        }
                        val confirmed = booleanArrayOf(false)
                        com.intellij.openapi.application.ApplicationManager.getApplication().invokeAndWait {
                            confirmed[0] = com.intellij.openapi.ui.Messages.showYesNoDialog(
                                project,
                                warnings.joinToString("\n\n") + "\n\nContinue anyway?",
                                Bundle.msg("dialog.switch.title"),
                                com.intellij.openapi.ui.Messages.getWarningIcon(),
                            ) == com.intellij.openapi.ui.Messages.YES
                        }
                        if (!confirmed[0]) {
                            logLines += "[warn] switch cancelled by user due to preflight warnings"
                            cancelled = true
                            return@runBackground
                        }
                    }
                    val executor = SwitchExecutor(root, { logLines += it }, service.gitClient, indicator)
                    ok = executor.execute(preset, SwitchOptions(
                        dirty = service.dirtyAction,
                        fetchFirst = service.fetchFirst,
                        pull = service.pullAfterSwitch,
                        confirmBeforeInit = service.confirmBeforeInit,
                    ))
                }
            } catch (e: CancellationException) {
                logLines += "[warn] switch cancelled by user"
                cancelled = true
            } catch (e: Exception) {
                logLines += "[error] ${e.javaClass.simpleName}: ${e.message}"
                ok = false
            }
            // Resumed on EDT via TaskBridge.onFinished — wrap UI ops
            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                if (cancelled) {
                    return@invokeLater
                }
                if (ok) {
                    Notifier.info(project, Bundle.msg("switch.complete"), Bundle.msg("notify.switch.complete.msg", preset.name))
                } else {
                    val detail = logLines.filter { it.contains("[fail]") || it.contains("[fatal]") || it.contains("[warn]") || it.contains("[error]") }
                        .take(3).joinToString("\n")
                    Notifier.error(project, Bundle.msg("switch.failed"),
                        if (detail.isNotEmpty()) Bundle.msg("notify.switch.partial.msg", preset.name) + ":\n" + detail
                        else Bundle.msg("notify.switch.partial.msg", preset.name))
                }
                project.messageBus.syncPublisher(BranchSwitchListener.TOPIC).onBranchSwitched()
                service.scope.launch(Dispatchers.IO) {
                    refreshVcsRepos(project, root, preset.submodules.keys)
                }
            }
        }
    }
}
