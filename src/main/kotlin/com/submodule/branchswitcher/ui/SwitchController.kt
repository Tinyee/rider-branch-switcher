package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.Strings
import com.submodule.branchswitcher.model.DirtyAction
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.switch.SwitchExecutor
import com.submodule.branchswitcher.switch.SwitchPreflight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Path
import javax.swing.JProgressBar
import javax.swing.SwingUtilities

/**
 * Handles all switch-related operations: preflight preview, execute, rollback,
 * derive branch, undo, and VCS refresh. Extracted from [BranchSwitcherPanel].
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
    private var lastMatchedPreset: Preset? = null

    fun runSwitch(preset: Preset) {
        val root = gitRoot() ?: return

        val preflightTask = object : Task.Modal(project, "Inspecting branches", true) {
            var result: List<PreflightRow> = emptyList()
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                result = SwitchPreflight(service.gitClient).probe(root, preset, indicator)
            }
            override fun onSuccess() {
                if (!SwitchPreviewDialog.showAndConfirm(project, preset, result)) return
                executeSwitch(root, preset)
            }
        }
        ProgressManager.getInstance().run(preflightTask)
    }

    fun executeSwitch(root: Path, preset: Preset) {
        // Determine options from current service state
        val opts = SwitchOptions(
            dirty = service.dirtyAction,
            pull = service.pullAfterSwitch,
            fetchFirst = service.fetchFirst,
            confirmBeforeInit = service.confirmBeforeInit,
        )

        setSwitchInProgress(true)
        val task = object : Task.Backgroundable(project, "Switching branches", true) {
            var ok = false
            private var rollbackExecutor: SwitchExecutor? = null
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val wrapped = object : ProgressIndicator by indicator {
                    override fun setFraction(fraction: Double) {
                        indicator.fraction = fraction
                        SwingUtilities.invokeLater {
                            progressBar.isIndeterminate = false
                            progressBar.value = (fraction * 100).toInt()
                        }
                    }
                    override fun setText2(text: String?) {
                        indicator.text2 = text
                        SwingUtilities.invokeLater { progressBar.string = text ?: "Switching..." }
                    }
                }
                val executor = SwitchExecutor(root, log, service.gitClient, wrapped)
                rollbackExecutor = executor
                ok = executor.execute(preset, opts)
            }
            override fun onFinished() {
                setSwitchInProgress(false)
                service.addHistory(preset.name)
                if (ok) {
                    Notifier.info(project, Strings.switchComplete, Strings.switchCompleteMsg.format(preset.name))
                } else {
                    val executor = rollbackExecutor
                    if (executor?.getCheckpoint() != null) {
                        Notifier.rollbackAction(project, Strings.switchFailed,
                            Strings.switchPartialMsg.format(preset.name) + "。可回滚到切换前的 HEAD。") {
                            rollbackSwitch(executor)
                        }
                    } else {
                        Notifier.error(project, Strings.switchFailed,
                            Strings.switchPartialMsg.format(preset.name))
                    }
                }
                refreshVcs(root, preset)
            }
        }
        ProgressManager.getInstance().run(task)
    }

    fun rollbackSwitch(executor: SwitchExecutor) {
        val task = object : Task.Backgroundable(project, "Rolling back", true) {
            var rollbackOk = false
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                rollbackOk = executor.rollback()
            }
            override fun onFinished() {
                val root = gitRoot() ?: return
                val submodulePaths = executor.getCheckpoint()?.keys?.filter { it != "." } ?: emptyList()
                refreshVcs(root, Preset("_rollback", "", submodulePaths.associateWith { "" }))
                onStateChanged()
                if (!rollbackOk) {
                    Notifier.warn(project, Strings.rollbackPartial, Strings.rollbackPartialMsg)
                }
            }
        }
        ProgressManager.getInstance().run(task)
    }

    fun derivePresetBranch(root: Path, preset: Preset, branchName: String) {
        val task = object : Task.Backgroundable(project, "Creating branch $branchName", true) {
            override fun run(indicator: ProgressIndicator) {
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
            override fun onFinished() {
                onStateChanged()
                Notifier.info(project, Strings.deriveComplete, "分支 $branchName 已创建，共 ${preset.targets().size} 个仓库")
            }
        }
        ProgressManager.getInstance().run(task)
    }

    fun undoLastSwitch() {
        val allPresets = editors().map { it.currentPreset() }
        val history = service.getHistory()
        if (history.size < 2) {
            Messages.showInfoMessage(project, Strings.noUndoHistory, Strings.undoDialog)
            return
        }
        val previousName = history[1].presetName
        val preset = allPresets.find { it.name == previousName }
        if (preset == null) {
            Messages.showInfoMessage(project, "${Strings.undoNotFound}「$previousName」", Strings.undoDialog)
            return
        }
        runSwitch(preset)
    }

    private fun refreshVcs(root: Path, preset: Preset) {
        service.scope.launch(Dispatchers.IO) {
            val dirs = mutableListOf(root.toFile())
            preset.submodules.keys.forEach { dirs += root.resolve(it).toFile() }
            val app = com.intellij.openapi.application.ApplicationManager.getApplication()
            try {
                val lfs = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                val mgr = git4idea.repo.GitRepositoryManager.getInstance(project)
                for (dir in dirs) {
                    val vf = lfs.refreshAndFindFileByIoFile(dir) ?: continue
                    vf.refresh(false, true)
                    mgr.getRepositoryForRoot(vf)?.update()
                }
                app.invokeLater {
                    log("[vcs] refreshed ${dirs.size} repo(s)")
                    onStateChanged()
                }
            } catch (t: Throwable) {
                app.invokeLater {
                    log("[error] refreshVcs failed: ${t.javaClass.simpleName}: ${t.message}")
                    Notifier.warn(project, Strings.rollbackPartial,
                        "${t.javaClass.simpleName}: ${t.message}")
                    onStateChanged()
                }
            }
        }
    }

    private fun setSwitchInProgress(inProgress: Boolean) {
        val tw = com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow("SubmoduleBranches") ?: return
        if (inProgress) {
            tw.setIcon(AllIcons.Process.Step_4)
            progressBar.isVisible = true
            progressBar.isIndeterminate = true
            progressBar.string = "Switching..."
        } else {
            tw.setIcon(AllIcons.Vcs.Branch)
            progressBar.isVisible = false
        }
    }
}
