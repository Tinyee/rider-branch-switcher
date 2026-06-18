package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.model.SwitchOptions
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.switch.DeriveBranchExecutor
import com.submodule.branchswitcher.switch.DeriveNotification
import com.submodule.branchswitcher.switch.SwitchPreflight
import com.submodule.branchswitcher.switch.SwitchExecutor
import com.submodule.branchswitcher.switch.SwitchRunner
import com.submodule.branchswitcher.switch.deriveNotification
import com.submodule.branchswitcher.switch.refreshVcsRepos
import kotlinx.coroutines.CancellationException
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
            val probeResult = try {
                TaskBridge.runModal(project, Bundle.msg("progress.preflight"), true) { indicator ->
                    indicator.isIndeterminate = false
                    SwitchPreflight(service.gitClient).probe(root, preset, indicator)
                }
            } catch (_: CancellationException) {
                return@launch // user cancelled modal, nothing to do
            } catch (e: Exception) {
                log.error("preflight probe failed: ${e.javaClass.simpleName}: ${e.message}")
                // Show empty probe result on error — user can still attempt switch
                invokeLaterIfProjectAlive {
                    val request = service.resolveSwitchRequest(preset)
                    if (SwitchPreviewDialog.showAndConfirm(project, request, emptyList())) {
                        executeSwitch(root, request)
                    }
                }
                return@launch
            }
            // Resumed on caller thread after modal closes
            invokeLaterIfProjectAlive {
                val request = service.resolveSwitchRequest(preset)
                if (SwitchPreviewDialog.showAndConfirm(project, request, probeResult)) {
                    executeSwitch(root, request)
                }
            }
        }
    }

    fun executeSwitch(root: Path, request: ResolvedSwitchRequest) {
        if (!service.tryStartWrite()) {
            Notifier.warn(project, Bundle.msg("notify.write.busy"), Bundle.msg("notify.write.busy.msg"))
            return
        }
        val preset = request.preset

        setSwitchInProgress(true)
        service.scope.launch(Dispatchers.Default) {
            try {
            val gitClient = service.gitClient
            val result = SwitchRunner(project, root, gitClient).execute(
                title = "Switching branches",
                request = request,
                log = log,
                progress = { indicator ->
                    object : ProgressIndicator by indicator {
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
                },
            )
            // Resumed on EDT via TaskBridge.onFinished, but continuation dispatcher is Default
            // Wrap UI ops in invokeLater to avoid EDT violations
            invokeLaterIfProjectAlive {
                setSwitchInProgress(false)
                if (result.cancelled) {
                    refreshVcs(root, preset)
                    return@invokeLaterIfProjectAlive
                } else if (result.ok) {
                    service.incrementSwitchCount()
                    service.addHistory(preset.name, preset.id)
                    Notifier.info(project, Bundle.msg("switch.complete"), Bundle.msg("notify.switch.complete.msg", preset.name))
                } else {
                    service.incrementErrorCount()
                    val executor = result.executor
                    if (executor?.getCheckpoint() != null) {
                        Notifier.rollbackAction(project, Bundle.msg("switch.failed"),
                            Bundle.msg("notify.switch.partial.msg", preset.name) + Bundle.msg("notify.switch.rollback.hint")) {
                            rollbackSwitch(executor)
                        }
                    } else {
                        Notifier.error(project, Bundle.msg("switch.failed"),
                            Bundle.msg("notify.switch.partial.msg", preset.name))
                    }
                }
                refreshVcs(root, preset)
            }
            } finally { service.endWrite() }
        }
    }

    fun rollbackSwitch(executor: SwitchExecutor) {
        if (!service.tryStartWrite()) {
            Notifier.warn(project, Bundle.msg("notify.write.busy"), Bundle.msg("notify.write.busy.msg"))
            return
        }
        service.scope.launch(Dispatchers.Default) {
            try {
            var rollbackOk = false
            val gitClient = service.gitClient
            gitClient.beginOperation()
            try {
                TaskBridge.runBackground(project, Bundle.msg("progress.rollback"), true,
                    block = { indicator ->
                        indicator.isIndeterminate = true
                        rollbackOk = executor.rollback()
                    },
                    onCancel = { gitClient.cancel() },
                    onFinished = { gitClient.endOperation() },
                )
            } catch (_: CancellationException) {
                log.info("[cancelled] rollback cancelled by user")
                rollbackOk = false
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
            } finally { service.endWrite() }
        }
    }

    fun derivePresetBranch(root: Path, preset: Preset, branchName: String) {
        if (!service.tryStartWrite()) {
            Notifier.warn(project, Bundle.msg("notify.write.busy"), Bundle.msg("notify.write.busy.msg"))
            return
        }
        service.scope.launch(Dispatchers.Default) {
            val gitClient = service.gitClient
            var result: DeriveBranchExecutor.DeriveResult? = null
            var cancelled = false
            var rollbackFailures = emptyList<String>()

            try {
                gitClient.beginOperation()
                try {
                    TaskBridge.runBackground(project, Bundle.msg("progress.derive", branchName), true,
                        block = { indicator ->
                            indicator.isIndeterminate = true
                            val executor = DeriveBranchExecutor(root, log, gitClient, cancelled = { indicator.isCanceled })
                            result = executor.execute(preset, branchName)
                            if (!result.allOk && result.succeeded.isNotEmpty()) {
                                log.activity("[derive] rolling back ${result.succeeded.size} succeeded repo(s)...")
                                rollbackFailures = executor.rollbackSucceeded(result, branchName)
                            }
                        },
                        onCancel = { gitClient.cancel() },
                        onFinished = { gitClient.endOperation() },
                    )
                } catch (_: CancellationException) {
                    log.info("[cancelled] derive cancelled by user")
                    cancelled = true
                } catch (e: Exception) {
                    log.error("derive: ${e.javaClass.simpleName}: ${e.message}")
                }

                if (cancelled && result != null && result!!.succeeded.isNotEmpty()) {
                    gitClient.beginOperation()
                    try {
                        val executor = DeriveBranchExecutor(root, log, gitClient)
                        log.activity("[derive] rolling back ${result!!.succeeded.size} succeeded repo(s) after cancel...")
                        rollbackFailures = executor.rollbackSucceeded(result!!, branchName)
                    } catch (e: Exception) {
                        log.error("derive rollback after cancel: ${e.javaClass.simpleName}: ${e.message}")
                        rollbackFailures = listOf("(exception)")
                    } finally {
                        gitClient.endOperation()
                    }
                }
            } finally {
                service.endWrite()
            }

            val r = result
            val rfCount = rollbackFailures.size
            invokeLaterIfProjectAlive {
                onStateChanged()
                refreshVcs(root, preset)
                when (val d = deriveNotification(cancelled, r, rfCount, branchName)) {
                    is DeriveNotification.Success -> {
                        service.incrementDeriveCount()
                        Notifier.info(project,
                            Bundle.msg("notify.derive.complete"),
                            Bundle.msg("notify.derive.created", d.branchName, d.repoCount))
                    }
                    is DeriveNotification.Failure -> {
                        service.incrementErrorCount()
                        Notifier.warn(project,
                            Bundle.msg("notify.derive.partial"),
                            when (d.reason) {
                                DeriveNotification.Reason.ROLLBACK_FAILED ->
                                    Bundle.msg("notify.derive.rollback.failed", d.count)
                                DeriveNotification.Reason.UNEXPECTED ->
                                    Bundle.msg("notify.derive.unexpected")
                                DeriveNotification.Reason.PARTIAL ->
                                    Bundle.msg("notify.derive.partial.msg", d.branchName)
                            })
                    }
                    is DeriveNotification.Blocked -> {
                        val parts = mutableListOf<String>()
                        if (d.branchExistsCount > 0) parts.add(Bundle.msg("notify.derive.blocked.exists", d.branchExistsCount))
                        if (d.skippedCount > 0) parts.add(Bundle.msg("notify.derive.blocked.skipped", d.skippedCount))
                        if (d.dirtyCount > 0) parts.add(Bundle.msg("notify.derive.blocked.dirty", d.dirtyCount))
                        if (d.branchMismatchCount > 0) parts.add(Bundle.msg("notify.derive.blocked.mismatch", d.branchMismatchCount))
                        if (d.preflightErrorCount > 0) parts.add(Bundle.msg("notify.derive.blocked.error", d.preflightErrorCount))
                        if (d.checkpointFailedCount > 0) parts.add(Bundle.msg("notify.derive.blocked.checkpoint", d.checkpointFailedCount))
                        Notifier.warn(project, Bundle.msg("notify.derive.blocked"), parts.joinToString("\n"))
                    }
                    is DeriveNotification.Silent -> {}
                }
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
            Messages.showInfoMessage(project, Bundle.msg("undo.not.found.preset", entry.presetName), Bundle.msg("dialog.undo"))
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

    private fun invokeLaterIfProjectAlive(action: () -> Unit) = project.invokeLaterIfAlive(action)

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
