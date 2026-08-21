package com.submodule.branchswitcher.ui

import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.log.OperationContext
import com.submodule.branchswitcher.log.logFailure
import com.submodule.branchswitcher.log.withContext
import com.submodule.branchswitcher.model.PreflightRow
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.model.ResolvedSwitchRequest
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.platform.GitBackgroundRunner
import com.submodule.branchswitcher.platform.platformCancellationClassifier
import com.submodule.branchswitcher.platform.refreshVcsTail
import com.submodule.branchswitcher.operation.GitOperationResult
import com.submodule.branchswitcher.switch.SwitchRecoveryExecutor
import com.submodule.branchswitcher.switch.SwitchExecutionResult
import com.submodule.branchswitcher.workflow.SwitchRunner
import com.submodule.branchswitcher.workflow.WriteOperationLauncher
import kotlinx.coroutines.CancellationException
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
 * Both entries confirm through the same [SwitchPreviewDialog]; preflight logic,
 * submodule-init confirmation, and post-execution tail are shared here.
 */
class SwitchFlowCoordinator(
    private val project: Project,
    private val service: BranchSwitcherService,
    /** Shared write-lease launcher, owned by the caller so every entry point reuses one instance. */
    private val writeOperations: WriteOperationLauncher,
    /**
     * Notified (on the UI thread) when a user-initiated rollback write starts or ends,
     * so the Tool Window can show the same in-progress state as a forward switch.
     */
    private val onRollbackInProgress: ((Boolean) -> Unit)? = null,
) {
    private val preflightUi = SwitchPreflightUi(project, service)
    private val resultPresenter = SwitchResultPresenter(project, service)

    private fun uiLater(block: () -> Unit) {
        project.invokeLaterIfAlive(block)
    }

    private suspend fun preflight(
        root: Path,
        preset: Preset,
        log: AppLogger,
        operationContext: OperationContext,
    ): List<PreflightRow> = preflightUi.probe(root, preset, log, operationContext)

    /**
     * Resolves which missing submodule directories the user has approved for
     * initialization BEFORE the switch acquires the write lease.
     *
     * Returns `emptySet()` when no upfront confirmation is needed (confirmBeforeInit
     * disabled, or nothing missing), the approved path set after the user confirms,
     * or `null` when the user declines (the switch must be aborted).
     */
    private fun resolvePreApprovedSubmoduleInit(
        request: ResolvedSwitchRequest,
        probeResult: List<PreflightRow>,
    ): Set<String>? {
        if (!request.options.confirmBeforeInit) return emptySet()
        val missing = probeResult
            .filter { !it.exists && !it.isMain }
            .map { it.path }
        if (missing.isEmpty()) return emptySet()
        return if (preflightUi.confirmSubmoduleInitializations(missing)) missing.toSet() else null
    }

    /**
     * The shared preflight → preview-confirm → submodule-init approval → execute flow
     * used by every switch entry point (tool window and keyboard shortcut), so both
     * confirm through the same [SwitchPreviewDialog] and share one confirmation UI.
     *
     * Callers differ only in where the root comes from, how a declined confirmation is
     * logged, and which busy/success/finish callbacks run.
     */
    @Suppress("TooGenericExceptionCaught") // platform preflight adapters report unrelated failures through one UI boundary
    fun runSwitchFlow(
        root: Path,
        preset: Preset,
        log: AppLogger,
        operationContext: OperationContext,
        onDecline: (String) -> Unit = {},
        onSwitchStart: (() -> Unit)? = null,
        onSuccess: (() -> Unit)? = null,
        onFinished: (() -> Unit)? = null,
    ) {
        service.scope.launch(Dispatchers.Default) {
            val probeResult = try {
                preflight(root, preset, log, operationContext)
            } catch (_: CancellationException) {
                log.withContext(operationContext).info("preflight cancelled by user")
                return@launch
            } catch (_: com.intellij.openapi.progress.ProcessCanceledException) {
                log.withContext(operationContext).info("preflight cancelled by user")
                return@launch
            } catch (e: Exception) {
                log.withContext(operationContext).logFailure("preflight probe failed", e)
                uiLater {
                    Notifier.error(
                        project,
                        Bundle.msg("notify.preflight.failed"),
                        Bundle.msg("notify.preflight.failed.msg", e.javaClass.simpleName, e.message ?: ""),
                        operationContext.id,
                    )
                }
                return@launch
            }
            uiLater {
                val request = service.resolveSwitchRequest(preset)
                val preview = SwitchPreviewDialog(project, request, probeResult)
                if (!preview.showAndGet()) {
                    log.withContext(operationContext).info("switch declined by user: preview cancelled")
                    onDecline("switch cancelled by user - preview declined")
                    return@uiLater
                }
                val preApproved = resolvePreApprovedSubmoduleInit(request, probeResult)
                    ?: run {
                        log.withContext(operationContext).info("switch declined by user: submodule init not approved")
                        onDecline("switch cancelled by user - submodule init declined")
                        return@uiLater
                    }
                val collisionDiscards = resolveCollisionDiscards(probeResult, preview.onlyMetaDiscard)
                onSwitchStart?.invoke()
                executeAndNotify(
                    root,
                    request,
                    log,
                    operationContext,
                    preApprovedSubmoduleInit = preApproved,
                    collisionDiscards = collisionDiscards,
                    onSuccess = onSuccess,
                    onFinished = onFinished,
                )
            }
        }
    }

    /**
     * Acquires the project write lease, executes the shared switch workflow,
     * then maps its structured result to notifications and VCS refresh.
     *
     * Callbacks run on the UI thread. The write lease remains held until the
     * background workflow has produced its final result.
     */
    private fun executeAndNotify(
        root: Path,
        request: ResolvedSwitchRequest,
        log: AppLogger,
        operationContext: OperationContext,
        preApprovedSubmoduleInit: Set<String> = emptySet(),
        collisionDiscards: Map<String, Set<String>> = emptyMap(),
        onSuccess: (() -> Unit)? = null,
        onFinished: (() -> Unit)? = null,
    ) {
        val preset = request.preset
        val completion = SwitchUiCompletion(::uiLater, onFinished)
        val job = writeOperations.launch(
            onBusy = {
                log.withContext(operationContext).warn(
                    "operation rejected: another repository write is already running" +
                        service.currentWriteHolder?.let { " (held by $it)" }.orEmpty(),
                )
                uiLater {
                    completion.completeAfter {
                        resultPresenter.showWriteBusy()
                    }
                }
            },
            afterRelease = { runResult ->
                val operationLog = log.withContext(operationContext.inPhase("refresh"))
                refreshVcsTail(project, root, preset.submodules.keys, operationLog, ::uiLater) {
                    completion.completeAfter {
                        resultPresenter.presentSwitchResult(
                            preset = preset,
                            runResult = runResult,
                            onSuccess = onSuccess,
                            onRollback = { execution -> rollbackSwitch(root, execution, log, operationContext) },
                            operationId = operationContext.id,
                        )
                    }
                }
            },
        ) {
            SwitchRunner(
                projectRoot = root,
                operations = GitBackgroundRunner(project, service.gitClient),
                cancellationClassifier = platformCancellationClassifier,
                preApprovedSubmoduleInit = preApprovedSubmoduleInit,
                collisionDiscards = collisionDiscards,
            ).execute(
                title = Bundle.msg("progress.switching"),
                request = request,
                log = log,
                operationContext = operationContext,
                recoveryTitle = Bundle.msg("progress.rollback"),
                stashRestoreTitle = Bundle.msg("progress.stash.restore"),
            )
        }
        if (job == null) return
        completion.completeWhenFailed(job) { failure ->
            if (!platformCancellationClassifier.isCancellation(failure)) {
                log.logFailure("switch completion failed", failure)
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
        val job = writeOperations.launch(
            onBusy = {
                log.withContext(operationContext).warn(
                    "operation rejected: another repository write is already running" +
                        service.currentWriteHolder?.let { " (held by $it)" }.orEmpty(),
                )
                uiLater { resultPresenter.showWriteBusy() }
            },
            afterRelease = { recoveryOutcome ->
                val checkpointPaths = execution.checkpoint.orEmpty().keys.filterTo(mutableSetOf()) { it != "." }
                val refreshLog = log.withContext(operationContext.inPhase("recovery-refresh"))
                refreshVcsTail(project, root, checkpointPaths, refreshLog, ::uiLater) {
                    val recoveredExecution = recoveryOutcome?.let {
                        execution.copy(state = it.stashRestore.state)
                    } ?: execution
                    resultPresenter.presentRollbackResult(
                        recoveredExecution,
                        recoveryOutcome?.ok == true,
                        recoveryIssues = recoveryOutcome?.issues.orEmpty(),
                        operationId = operationContext.id,
                    )
                }
            },
        ) {
            val rollbackBackgroundResult = GitBackgroundRunner(project, service.gitClient).run(
                Bundle.msg("progress.rollback"),
            ) { indicator, operation ->
                indicator.isIndeterminate = true
                indicator.text = Bundle.msg("progress.rollback")
                val recovery = SwitchRecoveryExecutor(
                    root,
                    recoveryLog,
                    operation,
                    cancelled = { indicator.isCanceled },
                )
                recovery.recover(execution)
            }
            when (rollbackBackgroundResult) {
                is GitOperationResult.Completed -> rollbackBackgroundResult.value
                is GitOperationResult.Cancelled -> rollbackBackgroundResult.value
                is GitOperationResult.Failed -> {
                    val error = rollbackBackgroundResult.error
                    recoveryLog.logFailure("notification rollback failed", error)
                    null
                }
            }
        }
        if (job == null) {
            // The write lease is held by someone else, so no rollback starts. Any
            // in-progress state belongs to whoever holds the lease — never clear it
            // here (a running forward switch would lose its busy indicator).
            return
        }
        // Claim in-progress only once the rollback owns the lease, so the busy path
        // above never touches state set by a concurrent operation.
        onRollbackInProgress?.invoke(true)
        job.invokeOnCompletion { uiLater { onRollbackInProgress?.invoke(false) } }
    }
}
