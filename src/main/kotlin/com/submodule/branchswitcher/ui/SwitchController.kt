package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.switch.SwitchExecutor
import com.submodule.branchswitcher.switch.SwitchPreflight
import com.submodule.branchswitcher.switch.refreshVcsRepos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Path
import javax.swing.JProgressBar
import javax.swing.SwingUtilities

/**
 * Handles all switch-related operations: preflight preview, execute, rollback,
 * derive branch, undo, and VCS refresh. All async via [service.scope].
 */
class SwitchController(
    private val project: Project,
    private val service: BranchSwitcherService,
    private val gitRoot: () -> Path?,
    private val log: (String) -> Unit,
    private val editors: () -> List<PresetEditor>,
    private val onStateChanged: () -> Unit,
    private val progressBar: JProgressBar,
) {

    fun runSwitch(preset: Preset) {
        val root = gitRoot() ?: return
        service.scope.launch(Dispatchers.Default) {
            val probeResult = TaskBridge.runModal(project, "Inspecting branches", true) { indicator ->
                indicator.isIndeterminate = false
                SwitchPreflight(service.gitClient).probe(root, preset, indicator)
            }
            // Resumed on caller thread after modal closes
            val app = com.intellij.openapi.application.ApplicationManager.getApplication()
            app.invokeLater {
                if (SwitchPreviewDialog.showAndConfirm(project, preset, probeResult)) {
                    executeSwitch(root, preset)
                }
            }
        }
    }

    fun executeSwitch(root: Path, preset: Preset) {
        val opts = SwitchOptions(
            dirty = service.dirtyAction,
            pull = service.pullAfterSwitch,
            fetchFirst = service.fetchFirst,
            confirmBeforeInit = service.confirmBeforeInit,
        )

        setSwitchInProgress(true)
        service.scope.launch(Dispatchers.Default) {
            var ok = false
            var rollbackExecutor: SwitchExecutor? = null
            try {
                TaskBridge.runBackground(project, "Switching branches", true) { indicator ->
                    indicator.isIndeterminate = true
                    val wrapped = object : ProgressIndicator by indicator {
                        override fun setFraction(fraction: Double) {
                            indicator.fraction = fraction
                            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                                progressBar.isIndeterminate = false
                                progressBar.value = (fraction * 100).toInt()
                            }
                        }
                        override fun setText2(text: String?) {
                            indicator.text2 = text
                            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                                progressBar.string = text ?: Bundle.msg("tooltip.progress.switching")
                            }
                        }
                        override fun setText(text: String?) {
                            indicator.text = text
                            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                                progressBar.string = text ?: Bundle.msg("tooltip.progress.switching")
                            }
                        }
                        override fun setIndeterminate(indeterminate: Boolean) {
                            indicator.isIndeterminate = indeterminate
                            com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                                progressBar.isIndeterminate = indeterminate
                            }
                        }
                    }
                    val executor = SwitchExecutor(root, log, service.gitClient, wrapped)
                    rollbackExecutor = executor
                    ok = executor.execute(preset, opts)
                }
            } catch (e: Exception) {
                log("[error] switch: ${e.javaClass.simpleName}: ${e.message}")
                ok = false
            }
            // Resumed on EDT via TaskBridge.onFinished
            setSwitchInProgress(false)
            service.addHistory(preset.name)
            if (ok) {
                Notifier.info(project, Bundle.msg("switch.complete"), Bundle.msg("notify.switch.complete.msg", preset.name))
            } else {
                val executor = rollbackExecutor
                if (executor?.getCheckpoint() != null) {
                    Notifier.rollbackAction(project, Bundle.msg("switch.failed"),
                        Bundle.msg("notify.switch.partial.msg", preset.name) + "。可回滚到切换前的 HEAD。") {
                        rollbackSwitch(executor)
                    }
                } else {
                    Notifier.error(project, Bundle.msg("switch.failed"),
                        Bundle.msg("notify.switch.partial.msg", preset.name))
                }
            }
            refreshVcs(root, preset)
        }
    }

    fun rollbackSwitch(executor: SwitchExecutor) {
        service.scope.launch(Dispatchers.Default) {
            var rollbackOk = false
            try {
                TaskBridge.runBackground(project, "Rolling back", true) { indicator ->
                    indicator.isIndeterminate = true
                    rollbackOk = executor.rollback()
                }
            } catch (e: Exception) {
                log("[error] rollback: ${e.javaClass.simpleName}: ${e.message}")
                rollbackOk = false
            }
            // Resumed on EDT
            val root = gitRoot() ?: return@launch
            val submodulePaths = executor.getCheckpoint()?.keys?.filter { it != "." } ?: emptyList()
            refreshVcs(root, Preset("_rollback", "", submodulePaths.associateWith { "" }))
            onStateChanged()
            if (!rollbackOk) {
                Notifier.warn(project, Bundle.msg("rollback.partial"), Bundle.msg("rollback.partial.msg"))
            }
        }
    }

    fun derivePresetBranch(root: Path, preset: Preset, branchName: String) {
        service.scope.launch(Dispatchers.Default) {
            try {
                TaskBridge.runBackground(project, "Creating branch $branchName", true) { indicator ->
                    indicator.isIndeterminate = true
                    indicator.text = "Creating branch on all repos..."
                    val targets = preset.targets()
                    for ((idx, target) in targets.withIndex()) {
                        indicator.fraction = idx.toDouble() / targets.size
                        indicator.text2 = if (target.path == ".") root.fileName.toString() else target.path
                        val dir = if (target.path == ".") root.toFile() else root.resolve(target.path).toFile()
                        if (!dir.exists() || !java.io.File(dir, ".git").exists()) {
                            log("[derive] skip ${target.path} — not a git repo")
                            continue
                        }
                        val r = service.gitClient.checkoutNewBranch(dir, branchName)
                        if (r.ok) {
                            log("[derive] ${target.path}: created branch $branchName")
                        } else {
                            log("[derive] ${target.path}: FAILED — ${r.stderr.lines().firstOrNull() ?: ""}")
                        }
                    }
                }
            } catch (_: Exception) { /* logged in task */ }
            // Resumed on EDT
            onStateChanged()
            Notifier.info(project, Bundle.msg("notify.derive.complete"), Bundle.msg("notify.derive.created", branchName, preset.targets().size))
        }
    }

    fun undoLastSwitch() {
        val allPresets = editors().map { it.currentPreset() }
        val history = service.getHistory()
        if (history.size < 2) {
            Messages.showInfoMessage(project, Bundle.msg("no.undo.history"), Bundle.msg("dialog.undo"))
            return
        }
        val previousName = history[1].presetName
        val preset = allPresets.find { it.name == previousName }
        if (preset == null) {
            Messages.showInfoMessage(project, "${Bundle.msg("undo.not.found")}「$previousName」", Bundle.msg("dialog.undo"))
            return
        }
        runSwitch(preset)
    }

    private fun refreshVcs(root: Path, preset: Preset) {
        service.scope.launch(Dispatchers.IO) {
            try {
                refreshVcsRepos(project, root, preset.submodules.keys)
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    log("[vcs] refreshed ${preset.submodules.size + 1} repo(s)")
                    onStateChanged()
                }
            } catch (t: Throwable) {
                com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
                    log("[error] refreshVcs failed: ${t.javaClass.simpleName}: ${t.message}")
                    Notifier.warn(project, Bundle.msg("rollback.partial"), "${t.javaClass.simpleName}: ${t.message}")
                    onStateChanged()
                }
            }
        }
    }

    private fun setSwitchInProgress(inProgress: Boolean) {
        val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("SubmoduleBranches") ?: return
        if (inProgress) {
            tw.setIcon(AllIcons.Process.Step_4)
            progressBar.isVisible = true
            progressBar.isIndeterminate = true
            progressBar.string = Bundle.msg("tooltip.progress.switching")
        } else {
            tw.setIcon(AllIcons.Vcs.Branch)
            progressBar.isVisible = false
        }
    }
}
