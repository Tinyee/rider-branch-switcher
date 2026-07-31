package com.submodule.branchswitcher.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.TaskBridge
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.switch.SwitchPreflight
import com.submodule.branchswitcher.platform.ProgressCancellationHandle
import com.submodule.branchswitcher.platform.GitBackgroundResult
import com.submodule.branchswitcher.platform.GitBackgroundRunner
import com.submodule.branchswitcher.platform.logVcsRefresh
import com.submodule.branchswitcher.platform.platformCancellationClassifier
import com.submodule.branchswitcher.platform.refreshVcsRepos
import com.submodule.branchswitcher.presentation.shouldShowForceWarning
import com.submodule.branchswitcher.switch.SwitchRecoveryExecutor
import com.submodule.branchswitcher.switch.SwitchExecutionResult
import com.submodule.branchswitcher.workflow.SwitchRunner
import com.submodule.branchswitcher.workflow.SwitchRunResult
import com.submodule.branchswitcher.workflow.SwitchRecoveryResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean

/** Delivers Tool Window cleanup exactly once across background and UI failure paths. */
internal class SwitchUiCompletion(
    private val uiLater: (() -> Unit) -> Unit,
    private val onFinished: (() -> Unit)?,
) {
    private val completed = AtomicBoolean(false)

    fun completeAfter(block: () -> Unit) {
        try {
            block()
        } finally {
            complete()
        }
    }

    fun completeWhenFailed(job: Job, onFailure: (Throwable) -> Unit) {
        job.invokeOnCompletion { failure ->
            if (failure != null) {
                try {
                    onFailure(failure)
                } finally {
                    uiLater(::complete)
                }
            }
        }
    }

    private fun complete() {
        if (completed.compareAndSet(false, true)) onFinished?.invoke()
    }
}

/**
 * Shared switch orchestration for ToolWindow and keyboard shortcut entries.
 *
 * Each entry point owns its own preflight UI (preview dialog vs simple confirm),
 * but preflight logic, force warnings, and post-execution tail are shared here.
 */
class SwitchFlowCoordinator(
    private val project: Project,
    private val service: BranchSwitcherService,
) {
    private fun uiLater(block: () -> Unit) {
        project.invokeLaterIfAlive(block)
    }

    /** Shared preflight: probes all repos in [preset]. */
    suspend fun preflight(root: Path, preset: Preset): List<PreflightRow> {
        val git = service.gitClient
        return TaskBridge.runModal(project, Bundle.msg("progress.preflight"), true) { indicator ->
            indicator.isIndeterminate = false
            SwitchPreflight(git, Bundle.msg("preflight.probe.error.suffix"), platformCancellationClassifier)
                .probe(root, preset, ProgressCancellationHandle(indicator)) { index, total, label ->
                    indicator.text2 = label
                    indicator.fraction = index.toDouble() / total
                }
        }
    }

    /** Show force warning dialog. Returns true if user confirms (or no warning needed). */
    fun showForceWarning(preset: Preset, probeResult: List<PreflightRow>): Boolean {
        val request = service.resolveSwitchRequest(preset)
        if (!shouldShowForceWarning(request, probeResult)) return true
        var confirmed = false
        ApplicationManager.getApplication().invokeAndWait {
            confirmed = Messages.showYesNoDialog(
                project, Bundle.msg("dialog.force.confirm.msg", preset.name),
                Bundle.msg("dialog.force.confirm.title"), Messages.getWarningIcon(),
            ) == Messages.YES
        }
        return confirmed
    }

    /** Show missing-dir / missing-branch warnings. Returns true if user proceeds. */
    fun showPreflightWarnings(probeResult: List<PreflightRow>): Boolean {
        val missingDirs = probeResult.filter { !it.exists }
        val missingBranches = probeResult.filter { it.branchMissing }
        if (missingDirs.isEmpty() && missingBranches.isEmpty()) return true
        val warnings = mutableListOf<String>()
        if (missingDirs.isNotEmpty()) {
            warnings += Bundle.msg("preflight.warn.dir.missing", missingDirs.joinToString(", ") { it.label })
        }
        if (missingBranches.isNotEmpty()) {
            warnings += Bundle.msg("preflight.warn.branch.not.found", missingBranches.joinToString(", ") { it.label })
        }
        var confirmed = false
        ApplicationManager.getApplication().invokeAndWait {
            confirmed = Messages.showYesNoDialog(
                project,
                warnings.joinToString("\n\n") + "\n\n" + Bundle.msg("preflight.warn.continue"),
                Bundle.msg("dialog.switch.title"), Messages.getWarningIcon(),
            ) == Messages.YES
        }
        return confirmed
    }

    /**
     * Acquires the project write lease, executes the shared switch workflow,
     * then maps its structured result to notifications and VCS refresh.
     *
     * Callbacks run on the UI thread. The write lease remains held until the
     * background workflow has produced its final result.
     */
    fun executeAndNotify(
        root: Path,
        request: ResolvedSwitchRequest,
        log: AppLogger,
        onSuccess: (() -> Unit)? = null,
        onFinished: (() -> Unit)? = null,
    ) {
        val preset = request.preset
        val completion = SwitchUiCompletion(::uiLater, onFinished)
        val writeLease = service.tryAcquireWrite()
        if (writeLease == null) {
            uiLater {
                completion.completeAfter {
                    Notifier.warn(project, Bundle.msg("notify.write.busy"), Bundle.msg("notify.write.busy.msg"))
                }
            }
            return
        }
        val job = service.scope.launch(Dispatchers.Default) {
            val runResult = try {
                SwitchRunner(project, root, service.gitClient).execute(
                    title = Bundle.msg("progress.switching"), request = request, log = log,
                )
            } finally {
                writeLease.close()
            }
            val refreshResult = refreshVcsRepos(project, root, preset.submodules.keys)
            uiLater {
                completion.completeAfter {
                    logVcsRefresh(log, refreshResult)
                    presentSwitchResult(
                        root = root,
                        preset = preset,
                        runResult = runResult,
                        log = log,
                        onSuccess = onSuccess,
                    )
                }
            }
        }
        completion.completeWhenFailed(job) { failure ->
            if (!platformCancellationClassifier.isCancellation(failure)) {
                log.error("switch completion failed: ${failure.javaClass.simpleName}: ${failure.message}")
            }
        }
    }

    private fun presentSwitchResult(
        root: Path,
        preset: Preset,
        runResult: SwitchRunResult,
        log: AppLogger,
        onSuccess: (() -> Unit)?,
    ) {
        when {
            runResult.cancelled -> notifyCancellation(runResult.recovery)
            runResult.ok -> notifySuccessfulSwitch(preset, onSuccess)
            else -> notifySwitchFailure(root, preset, runResult.execution, log)
        }
    }

    private fun notifySuccessfulSwitch(preset: Preset, onSuccess: (() -> Unit)?) {
        service.addHistory(preset.name, preset.id)
        onSuccess?.invoke()
        Notifier.info(
            project,
            Bundle.msg("switch.complete"),
            Bundle.msg("notify.switch.complete.msg", preset.name),
        )
    }

    private fun notifyCancellation(recovery: SwitchRecoveryResult?) {
        if (recovery == null) {
            return
        }
        if (recovery.ok) {
            Notifier.info(
                project,
                Bundle.msg("switch.cancelled"),
                Bundle.msg("notify.switch.cancelled.recovered"),
            )
        } else {
            Notifier.error(
                project,
                Bundle.msg("switch.cancelled"),
                Bundle.msg("notify.switch.cancelled.partial"),
            )
        }
    }

    private fun notifySwitchFailure(
        root: Path,
        preset: Preset,
        execution: SwitchExecutionResult?,
        log: AppLogger,
    ) {
        val message = Bundle.msg("notify.switch.partial.msg", preset.name)
        if (execution?.checkpoint == null) {
            Notifier.error(project, Bundle.msg("switch.failed"), message)
            return
        }
        Notifier.rollbackAction(
            project,
            Bundle.msg("switch.failed"),
            message + Bundle.msg("notify.switch.rollback.hint"),
        ) {
            rollbackSwitch(root, execution, log)
        }
    }

    private fun rollbackSwitch(root: Path, execution: SwitchExecutionResult, log: AppLogger) {
        val writeLease = service.tryAcquireWrite()
        if (writeLease == null) {
            uiLater { Notifier.warn(project, Bundle.msg("notify.write.busy"), Bundle.msg("notify.write.busy.msg")) }
            return
        }
        service.scope.launch(Dispatchers.Default) {
            val rollbackSucceeded = try {
                val rollbackBackgroundResult = GitBackgroundRunner(project, service.gitClient).run(
                    Bundle.msg("progress.rollback"),
                ) { indicator, operation ->
                    indicator.isIndeterminate = true
                    indicator.text = Bundle.msg("progress.rollback")
                    val recovery = SwitchRecoveryExecutor(root, log, operation)
                    recovery.recover(execution).ok
                }
                when (rollbackBackgroundResult) {
                    is GitBackgroundResult.Completed -> rollbackBackgroundResult.value
                    is GitBackgroundResult.Cancelled -> rollbackBackgroundResult.value ?: false
                    is GitBackgroundResult.Failed -> {
                        val error = rollbackBackgroundResult.error
                        log.error("notification rollback: ${error.javaClass.simpleName}: ${error.message}")
                        false
                    }
                }
            } finally {
                writeLease.close()
            }
            val checkpointPaths = execution.checkpoint.orEmpty().keys.filterTo(mutableSetOf()) { it != "." }
            val refreshResult = refreshVcsRepos(project, root, checkpointPaths)
            uiLater {
                logVcsRefresh(log, refreshResult)
                if (rollbackSucceeded) {
                    Notifier.info(
                        project,
                        Bundle.msg("rollback.complete"),
                        Bundle.msg("notify.rollback.complete.msg"),
                    )
                } else {
                    Notifier.error(
                        project,
                        Bundle.msg("rollback.failed"),
                        Bundle.msg("notify.rollback.partial.msg"),
                    )
                }
            }
        }
    }
}
