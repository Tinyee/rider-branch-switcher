package com.submodule.branchswitcher.ui

import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.OperationContext
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
import com.submodule.branchswitcher.workflow.WriteOperationLauncher
import kotlinx.coroutines.Job
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
    private val writeOperations = WriteOperationLauncher(service.scope, service::tryAcquireWrite)

    private fun uiLater(block: () -> Unit) {
        project.invokeLaterIfAlive(block)
    }

    suspend fun preflight(
        root: Path,
        preset: Preset,
        log: AppLogger,
        operationContext: OperationContext,
    ): List<PreflightRow> = preflightUi.probe(root, preset, log, operationContext)

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
        operationContext: OperationContext,
        onSuccess: (() -> Unit)? = null,
        onFinished: (() -> Unit)? = null,
    ) {
        val preset = request.preset
        val completion = SwitchUiCompletion(::uiLater, onFinished)
        val job = writeOperations.launch(
            onBusy = {
                uiLater {
                    completion.completeAfter {
                        resultPresenter.showWriteBusy()
                    }
                }
            },
        ) {
            val runResult = SwitchRunner(
                projectRoot = root,
                operations = GitBackgroundRunner(project, service.gitClient),
                cancellationClassifier = platformCancellationClassifier,
                confirmSubmoduleInitialization = preflightUi::confirmSubmoduleInitialization,
            ).execute(
                title = Bundle.msg("progress.switching"),
                request = request,
                log = log,
                operationContext = operationContext,
            )
            val operationLog = log.withContext(operationContext.inPhase("refresh"))
            val refreshResult = refreshVcsRepos(project, root, preset.submodules.keys, operationLog)
            uiLater {
                completion.completeAfter {
                    logVcsRefresh(operationLog, refreshResult)
                    resultPresenter.presentSwitchResult(
                        preset = preset,
                        runResult = runResult,
                        onSuccess = onSuccess,
                        onRollback = { execution -> rollbackSwitch(root, execution, log, operationContext) },
                    )
                }
            }
        }
        if (job == null) return
        completion.completeWhenFailed(job) { failure ->
            if (!platformCancellationClassifier.isCancellation(failure)) {
                log.error("switch completion failed", failure)
            }
        }
    }

    private fun rollbackSwitch(
        root: Path,
        execution: SwitchExecutionResult,
        log: AppLogger,
        operationContext: OperationContext,
    ) {
        val recoveryLog = log.withContext(operationContext.inPhase("recovery"))
        writeOperations.launch(
            onBusy = { uiLater { resultPresenter.showWriteBusy() } },
        ) {
            val rollbackBackgroundResult = GitBackgroundRunner(project, service.gitClient).run(
                    Bundle.msg("progress.rollback"),
                ) { indicator, operation ->
                    indicator.isIndeterminate = true
                    indicator.text = Bundle.msg("progress.rollback")
                    val recovery = SwitchRecoveryExecutor(root, recoveryLog, operation)
                    recovery.recover(execution).ok
                }
            val rollbackSucceeded = when (rollbackBackgroundResult) {
                    is GitOperationResult.Completed -> rollbackBackgroundResult.value
                    is GitOperationResult.Cancelled -> rollbackBackgroundResult.value ?: false
                    is GitOperationResult.Failed -> {
                        val error = rollbackBackgroundResult.error
                        recoveryLog.error("notification rollback failed", error)
                        false
                    }
            }
            val checkpointPaths = execution.checkpoint.orEmpty().keys.filterTo(mutableSetOf()) { it != "." }
            val refreshLog = log.withContext(operationContext.inPhase("recovery-refresh"))
            val refreshResult = refreshVcsRepos(project, root, checkpointPaths, refreshLog)
            uiLater {
                logVcsRefresh(refreshLog, refreshResult)
                resultPresenter.presentRollbackResult(execution, rollbackSucceeded)
            }
        }
    }
}
