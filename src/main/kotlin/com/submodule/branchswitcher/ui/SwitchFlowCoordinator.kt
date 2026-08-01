package com.submodule.branchswitcher.ui

import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.withContext
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.platform.GitBackgroundRunner
import com.submodule.branchswitcher.platform.logVcsRefresh
import com.submodule.branchswitcher.platform.platformCancellationClassifier
import com.submodule.branchswitcher.platform.refreshVcsRepos
import com.submodule.branchswitcher.operation.GitOperationResult
import com.submodule.branchswitcher.switch.SwitchRecoveryExecutor
import com.submodule.branchswitcher.switch.SwitchExecutionResult
import com.submodule.branchswitcher.workflow.SwitchRunner
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
    private val preflightUi = SwitchPreflightUi(project, service)
    private val resultPresenter = SwitchResultPresenter(project, service)

    private fun uiLater(block: () -> Unit) {
        project.invokeLaterIfAlive(block)
    }

    suspend fun preflight(root: Path, preset: Preset, log: AppLogger): List<PreflightRow> =
        preflightUi.probe(root, preset, log)

    fun showForceWarning(preset: Preset, probeResult: List<PreflightRow>): Boolean =
        preflightUi.confirmForceSwitch(preset, probeResult)

    fun showPreflightWarnings(probeResult: List<PreflightRow>): Boolean =
        preflightUi.confirmPreflightWarnings(probeResult)

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
                    resultPresenter.showWriteBusy()
                }
            }
            return
        }
        val job = service.scope.launch(Dispatchers.Default) {
            val runResult = try {
                SwitchRunner(
                    projectRoot = root,
                    operations = GitBackgroundRunner(project, service.gitClient),
                    cancellationClassifier = platformCancellationClassifier,
                    confirmSubmoduleInitialization = preflightUi::confirmSubmoduleInitialization,
                ).execute(
                    title = Bundle.msg("progress.switching"), request = request, log = log,
                )
            } finally {
                writeLease.close()
            }
            val operationLog = log.withContext(runResult.operationId)
            val refreshResult = refreshVcsRepos(project, root, preset.submodules.keys, operationLog)
            uiLater {
                completion.completeAfter {
                    logVcsRefresh(operationLog, refreshResult)
                    resultPresenter.presentSwitchResult(
                        preset = preset,
                        runResult = runResult,
                        onSuccess = onSuccess,
                        onRollback = { execution -> rollbackSwitch(root, execution, operationLog) },
                    )
                }
            }
        }
        completion.completeWhenFailed(job) { failure ->
            if (!platformCancellationClassifier.isCancellation(failure)) {
                log.error("switch completion failed", failure)
            }
        }
    }

    private fun rollbackSwitch(root: Path, execution: SwitchExecutionResult, log: AppLogger) {
        val writeLease = service.tryAcquireWrite()
        if (writeLease == null) {
            uiLater { resultPresenter.showWriteBusy() }
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
                    is GitOperationResult.Completed -> rollbackBackgroundResult.value
                    is GitOperationResult.Cancelled -> rollbackBackgroundResult.value ?: false
                    is GitOperationResult.Failed -> {
                        val error = rollbackBackgroundResult.error
                        log.error("notification rollback failed", error)
                        false
                    }
                }
            } finally {
                writeLease.close()
            }
            val checkpointPaths = execution.checkpoint.orEmpty().keys.filterTo(mutableSetOf()) { it != "." }
            val refreshResult = refreshVcsRepos(project, root, checkpointPaths, log)
            uiLater {
                logVcsRefresh(log, refreshResult)
                resultPresenter.presentRollbackResult(execution, rollbackSucceeded)
            }
        }
    }
}
