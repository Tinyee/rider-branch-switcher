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
import com.submodule.branchswitcher.platform.GitBackgroundRunner
import com.submodule.branchswitcher.platform.platformCancellationClassifier
import com.submodule.branchswitcher.platform.refreshVcsTail
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.switch.DeriveNotification
import com.submodule.branchswitcher.switch.deriveNotification
import com.submodule.branchswitcher.workflow.DeriveBranchRunner
import com.submodule.branchswitcher.workflow.WriteOperationLauncher
import com.submodule.branchswitcher.workflow.DeriveRunResult
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

    private val writeOperations = WriteOperationLauncher(service.scope, service::tryAcquireWrite)
    private val coordinator = SwitchFlowCoordinator(
        project,
        service,
        writeOperations,
        onRollbackInProgress = ::setSwitchInProgress,
    )

    /** Notified on the UI thread whenever an in-flight mutation starts or ends. */
    var onInProgressChange: ((Boolean) -> Unit)? = null
    private var switchInProgress = false

    fun runSwitch(preset: Preset) {
        val root = gitRoot() ?: run {
            log.error("git root not found")
            Notifier.error(project, Bundle.msg("plugin.title"), Bundle.msg("git.root.not.found"))
            return
        }
        coordinator.runSwitchFlow(
            root,
            preset,
            log,
            newOperationContext("switch"),
            // The tool-window path refreshes state directly on success (the keyboard
            // shortcut path publishes BranchSwitchListener.onBranchSwitched instead,
            // because the action has no handle to the panel). Without this, the panel
            // would only catch up via FileStatusManager events or the 2s reflog watch.
            onSwitchStart = { setSwitchInProgress(true) },
            onSuccess = onStateChanged,
            onFinished = { setSwitchInProgress(false) },
        )
    }

    fun derivePresetBranch(root: Path, preset: Preset, branchName: String) {
        // Correlate the write-gate rejection under a derive operation id; the accepted
        // run logs under DeriveBranchRunner's own context once it starts.
        val operationLog = log.withContext(newOperationContext("derive"))
        val job = writeOperations.launch(
            onBusy = {
                operationLog.warn(
                    "operation rejected: another repository write is already running" +
                        service.currentWriteHolder?.let { " (held by $it)" }.orEmpty(),
                )
                Notifier.warn(project, Bundle.msg("notify.write.busy"), Bundle.msg("notify.write.busy.msg"))
            },
            afterRelease = { runResult ->
                val operationLog = log.withContext(runResult.operationId)
                refreshVcsTail(project, root, preset.submodules.keys, operationLog, ::invokeLaterIfProjectAlive) {
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
                rollbackTitle = Bundle.msg("progress.derive.rollback"),
                preset = preset,
                branchName = branchName,
                log = log,
            )
        }
        if (job == null) {
            // The write lease is held by someone else, so no derive starts. Any
            // in-progress state belongs to whoever holds the lease — never clear it
            // here (a running forward switch would lose its busy indicator).
            return
        }
        // Claim in-progress only once the derive owns the lease, so the rejection
        // path above never touches state set by a concurrent operation.
        setSwitchInProgress(true)
        job.invokeOnCompletion { failure ->
            if (failure != null && !platformCancellationClassifier.isCancellation(failure)) {
                log.error("derive operation failed", failure)
            }
            invokeLaterIfProjectAlive { setSwitchInProgress(false) }
        }
    }

    private fun showDeriveNotification(runResult: DeriveRunResult, branchName: String) {
        val notification = deriveNotification(
            cancelled = runResult.cancelled,
            result = runResult.execution,
            rollbackFailureCount = runResult.rollbackFailures.size,
            branchName = branchName,
        )
        val operationId = runResult.operationId
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
                    operationId,
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
                    operationId,
                )
            }
            is DeriveNotification.Blocked -> {
                Notifier.warn(
                    project,
                    Bundle.msg("notify.derive.blocked"),
                    blockedReasonLines(notification).joinToString("\n"),
                    operationId,
                )
            }
            is DeriveNotification.Silent -> Unit
        }
    }

    /** One localized line per blocking condition counted in [notification]. */
    private fun blockedReasonLines(notification: DeriveNotification.Blocked): List<String> = buildList {
        if (notification.branchExistsCount > 0) {
            add(Bundle.msg("notify.derive.blocked.exists", notification.branchExistsCount))
        }
        if (notification.skippedCount > 0) {
            add(Bundle.msg("notify.derive.blocked.skipped", notification.skippedCount))
        }
        if (notification.dirtyCount > 0) {
            add(Bundle.msg("notify.derive.blocked.dirty", notification.dirtyCount))
        }
        if (notification.branchMismatchCount > 0) {
            add(Bundle.msg("notify.derive.blocked.mismatch", notification.branchMismatchCount))
        }
        if (notification.preflightErrorCount > 0) {
            add(Bundle.msg("notify.derive.blocked.error", notification.preflightErrorCount))
        }
        if (notification.checkpointFailedCount > 0) {
            add(Bundle.msg("notify.derive.blocked.checkpoint", notification.checkpointFailedCount))
        }
        if (notification.indexLockBlockedCount > 0) {
            add(Bundle.msg("notify.derive.blocked.indexLock", notification.indexLockBlockedCount))
        }
    }

    fun switchToPreviousPreset(allPresets: List<Preset>) {
        val history = service.getHistory()
        if (history.size < 2) {
            Messages.showInfoMessage(
                project,
                Bundle.msg("previous.preset.history.empty"),
                Bundle.msg("dialog.previous.preset"),
            )
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
            Messages.showInfoMessage(
                project,
                Bundle.msg("previous.preset.not.found", entry.presetName),
                Bundle.msg("dialog.previous.preset"),
            )
            return
        }
        runSwitch(preset)
    }

    private fun invokeLaterIfProjectAlive(action: () -> Unit) = project.invokeLaterIfAlive(action)

    internal fun setSwitchInProgress(inProgress: Boolean) {
        if (switchInProgress == inProgress) return
        switchInProgress = inProgress
        onInProgressChange?.invoke(inProgress)
        val toolWindow = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
            .getToolWindow("SubmoduleBranches") ?: return
        toolWindow.setIcon(if (inProgress) AllIcons.Process.Step_4 else AllIcons.Vcs.Branch)
    }
}
