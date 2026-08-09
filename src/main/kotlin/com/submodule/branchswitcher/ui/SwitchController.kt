package com.submodule.branchswitcher.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.logFailure
import com.submodule.branchswitcher.log.newOperationContext
import com.submodule.branchswitcher.log.withContext
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.platform.logVcsRefresh
import com.submodule.branchswitcher.platform.GitBackgroundRunner
import com.submodule.branchswitcher.platform.platformCancellationClassifier
import com.submodule.branchswitcher.platform.refreshVcsRepos
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.switch.DeriveNotification
import com.submodule.branchswitcher.switch.deriveNotification
import com.submodule.branchswitcher.workflow.DeriveBranchRunner
import com.submodule.branchswitcher.workflow.WriteOperationLauncher
import com.submodule.branchswitcher.workflow.DeriveRunResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * Handles all switch-related operations: preflight preview, execute, rollback,
 * derive branch, undo, and VCS refresh. All async via [service.scope].
 */
internal class SwitchController(
    private val project: Project,
    private val service: BranchSwitcherService,
    private val gitRoot: () -> Path?,
    private val log: AppLogger,
    private val onStateChanged: () -> Unit,
) {

    private val coordinator = SwitchFlowCoordinator(project, service)
    private val writeOperations = WriteOperationLauncher(service.scope, service::tryAcquireWrite)

    @Suppress("TooGenericExceptionCaught") // platform preflight adapters report unrelated failures through one UI boundary
    fun runSwitch(preset: Preset) {
        val root = gitRoot() ?: return
        val operationContext = newOperationContext("switch")
        service.scope.launch(Dispatchers.Default) {
            val probeResult = try {
                coordinator.preflight(root, preset, log, operationContext)
            } catch (_: CancellationException) {
                return@launch
            } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
                return@launch
            } catch (e: Exception) {
                log.logFailure("preflight probe failed", e)
                invokeLaterIfProjectAlive {
                    Notifier.error(project, Bundle.msg("notify.preflight.failed"),
                        Bundle.msg("notify.preflight.failed.msg", e.javaClass.simpleName, e.message ?: ""))
                }
                return@launch
            }
            invokeLaterIfProjectAlive {
                val request = service.resolveSwitchRequest(preset)
                if (SwitchPreviewDialog.showAndConfirm(project, request, probeResult)) {
                    val preApproved = coordinator.resolvePreApprovedSubmoduleInit(request, probeResult)
                        ?: return@invokeLaterIfProjectAlive
                    setSwitchInProgress(true)
                    coordinator.executeAndNotify(root, request, log, operationContext,
                        preApprovedSubmoduleInit = preApproved,
                        onFinished = { setSwitchInProgress(false) })
                }
            }
        }
    }

    fun derivePresetBranch(root: Path, preset: Preset, branchName: String) {
        writeOperations.launch(
            onBusy = {
                Notifier.warn(project, Bundle.msg("notify.write.busy"), Bundle.msg("notify.write.busy.msg"))
            },
            afterRelease = { runResult ->
                val operationLog = log.withContext(runResult.operationId)
                val refreshResult = refreshVcsRepos(project, root, preset.submodules.keys, operationLog)

                invokeLaterIfProjectAlive {
                    logVcsRefresh(operationLog, refreshResult)
                    onStateChanged()
                    showDeriveNotification(runResult, branchName)
                }
            },
        ) {
            DeriveBranchRunner(
                    projectRoot = root,
                    operations = GitBackgroundRunner(project, service.gitClient),
                    cancellationClassifier = platformCancellationClassifier,
                ).execute(
                    title = Bundle.msg("progress.derive", branchName),
                    preset = preset,
                    branchName = branchName,
                    log = log,
                )
        }
    }

    private fun showDeriveNotification(runResult: DeriveRunResult, branchName: String) {
        val notification = deriveNotification(
            cancelled = runResult.cancelled,
            result = runResult.execution,
            rollbackFailureCount = runResult.rollbackFailures.size,
            branchName = branchName,
        )
        when (notification) {
            is DeriveNotification.Success -> {
                Notifier.info(
                    project,
                    Bundle.msg("notify.derive.complete"),
                    Bundle.msg(
                        "notify.derive.created",
                        notification.branchName,
                        notification.repoCount,
                    ),
                )
            }
            is DeriveNotification.Failure -> {
                Notifier.warn(
                    project,
                    Bundle.msg("notify.derive.partial"),
                    when (notification.reason) {
                        DeriveNotification.Reason.ROLLBACK_FAILED ->
                            Bundle.msg("notify.derive.rollback.failed", notification.count)
                        DeriveNotification.Reason.UNEXPECTED ->
                            Bundle.msg("notify.derive.unexpected")
                        DeriveNotification.Reason.PARTIAL ->
                            Bundle.msg("notify.derive.partial.msg", notification.branchName)
                    },
                )
            }
            is DeriveNotification.Blocked -> {
                val blockedReasons = mutableListOf<String>()
                if (notification.branchExistsCount > 0) {
                    blockedReasons.add(
                        Bundle.msg("notify.derive.blocked.exists", notification.branchExistsCount),
                    )
                }
                if (notification.skippedCount > 0) {
                    blockedReasons.add(Bundle.msg("notify.derive.blocked.skipped", notification.skippedCount))
                }
                if (notification.dirtyCount > 0) {
                    blockedReasons.add(Bundle.msg("notify.derive.blocked.dirty", notification.dirtyCount))
                }
                if (notification.branchMismatchCount > 0) {
                    blockedReasons.add(
                        Bundle.msg("notify.derive.blocked.mismatch", notification.branchMismatchCount),
                    )
                }
                if (notification.preflightErrorCount > 0) {
                    blockedReasons.add(
                        Bundle.msg("notify.derive.blocked.error", notification.preflightErrorCount),
                    )
                }
                if (notification.checkpointFailedCount > 0) {
                    blockedReasons.add(
                        Bundle.msg("notify.derive.blocked.checkpoint", notification.checkpointFailedCount),
                    )
                }
                Notifier.warn(
                    project,
                    Bundle.msg("notify.derive.blocked"),
                    blockedReasons.joinToString("\n"),
                )
            }
            is DeriveNotification.Silent -> Unit
        }
    }

    fun undoLastSwitch(allPresets: List<Preset>) {
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

    private fun invokeLaterIfProjectAlive(action: () -> Unit) = project.invokeLaterIfAlive(action)

    private fun setSwitchInProgress(inProgress: Boolean) {
        val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("SubmoduleBranches") ?: return
        toolWindow.setIcon(if (inProgress) AllIcons.Process.Step_4 else AllIcons.Vcs.Branch)
    }
}
