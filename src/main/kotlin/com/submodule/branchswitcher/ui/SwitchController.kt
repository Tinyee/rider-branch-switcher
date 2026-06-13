package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.log.AppLogger
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

/**
 * Handles all switch-related operations: preflight preview, execute, rollback,
 * derive branch, undo, and VCS refresh. All async via [service.scope].
 */
class SwitchController(
    private val project: Project,
    private val service: BranchSwitcherService,
    private val gitRoot: () -> Path?,
    private val log: AppLogger,
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
            invokeLaterIfProjectAlive {
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
            service.gitClient.beginOperation()
            try {
                TaskBridge.runBackground(project, "Switching branches", true,
                    block = { indicator ->
                        indicator.isIndeterminate = true
                        val wrapped = object : ProgressIndicator by indicator {
                            override fun setFraction(fraction: Double) {
                                indicator.fraction = fraction
                                invokeLaterIfProjectAlive {
                                    progressBar.isIndeterminate = false
                                    progressBar.value = (fraction * 100).toInt()
                                }
                            }
                            override fun setText2(text: String?) {
                                indicator.text2 = text
                                invokeLaterIfProjectAlive {
                                    progressBar.string = text ?: Bundle.msg("tooltip.progress.switching")
                                }
                            }
                            override fun setText(text: String?) {
                                indicator.text = text
                                invokeLaterIfProjectAlive {
                                    progressBar.string = text ?: Bundle.msg("tooltip.progress.switching")
                                }
                            }
                            override fun setIndeterminate(indeterminate: Boolean) {
                                indicator.isIndeterminate = indeterminate
                                invokeLaterIfProjectAlive {
                                    progressBar.isIndeterminate = indeterminate
                                }
                            }
                        }
                        val executor = SwitchExecutor(root, log, service.gitClient, wrapped)
                        rollbackExecutor = executor
                        ok = executor.execute(preset, opts)
                    },
                    onCancel = { service.gitClient.cancel() },
                    onFinished = { service.gitClient.endOperation() },
                )
            } catch (e: Exception) {
                log.error("switch: ${e.javaClass.simpleName}: ${e.message}")
                ok = false
            }
            // Resumed on EDT via TaskBridge.onFinished, but continuation dispatcher is Default
            // Wrap UI ops in invokeLater to avoid EDT violations
            invokeLaterIfProjectAlive {
                setSwitchInProgress(false)
                if (ok) {
                    service.addHistory(preset.name, preset.id)
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
                log.error("rollback: ${e.javaClass.simpleName}: ${e.message}")
                rollbackOk = false
            }
            // Wrap UI ops in invokeLater to avoid EDT violations
            invokeLaterIfProjectAlive {
                val root = gitRoot()
                if (root != null) {
                    val submodulePaths = executor.getCheckpoint()?.keys?.filter { it != "." } ?: emptyList()
                    refreshVcs(root, Preset("_rollback", "", submodulePaths.associateWith { "" }))
                    onStateChanged()
                    if (!rollbackOk) {
                        Notifier.warn(project, Bundle.msg("rollback.partial"), Bundle.msg("rollback.partial.msg"))
                    }
                }
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
                            log.debug("[derive] skip ${target.path} — not a git repo")
                            continue
                        }
                        val r = service.gitClient.checkoutNewBranch(dir, branchName)
                        if (r.ok) {
                            log.activity("[derive] ${target.path}: created branch $branchName")
                        } else {
                            log.warn("[derive] ${target.path}: FAILED — ${r.stderr.lines().firstOrNull() ?: ""}")
                        }
                    }
                }
            } catch (_: Exception) { /* logged in task */ }
            invokeLaterIfProjectAlive {
                onStateChanged()
                Notifier.info(project, Bundle.msg("notify.derive.complete"), Bundle.msg("notify.derive.created", branchName, preset.targets().size))
            }
        }
    }

    fun undoLastSwitch() {
        val allPresets = editors().map { it.currentPreset() }
        val history = service.getHistory()
        if (history.size < 2) {
            Messages.showInfoMessage(project, Bundle.msg("no.undo.history"), Bundle.msg("dialog.undo"))
            return
        }
        val entry = history[1]
        // Prefer stable id (survives renames), fall back to name for old history entries
        val preset: Preset? = if (entry.presetId != null) {
            allPresets.find { it.id == entry.presetId } ?: allPresets.find { it.name == entry.presetName }
        } else {
            allPresets.find { it.name == entry.presetName }
        }
        if (preset == null) {
            Messages.showInfoMessage(project, "${Bundle.msg("undo.not.found")}「${entry.presetName}」", Bundle.msg("dialog.undo"))
            return
        }
        runSwitch(preset)
    }

    private fun refreshVcs(root: Path, preset: Preset) {
        service.scope.launch(Dispatchers.IO) {
            try {
                refreshVcsRepos(project, root, preset.submodules.keys)
                invokeLaterIfProjectAlive {
                    log.debug("[vcs] refreshed ${preset.submodules.size + 1} repo(s)")
                    onStateChanged()
                }
            } catch (t: Throwable) {
                invokeLaterIfProjectAlive {
                    log.error("refreshVcs failed: ${t.javaClass.simpleName}: ${t.message}")
                    Notifier.warn(project, Bundle.msg("rollback.partial"), "${t.javaClass.simpleName}: ${t.message}")
                    onStateChanged()
                }
            }
        }
    }

    private fun invokeLaterIfProjectAlive(action: () -> Unit) {
        ApplicationManager.getApplication().invokeLater({
            if (project.isDisposed) return@invokeLater
            action()
        }, ModalityState.any(), project.disposed)
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
