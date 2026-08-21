package com.submodule.branchswitcher.ui

import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.switch.OperationIssue
import com.submodule.branchswitcher.switch.SwitchExecutionResult
import com.submodule.branchswitcher.switch.lockBlockedPresentations
import com.submodule.branchswitcher.log.AppLogger
import com.submodule.branchswitcher.workflow.SingleRepositorySwitchResult
import com.submodule.branchswitcher.workflow.SwitchRunResult

/** Maps structured switch and rollback outcomes to history and IDE notifications. */
internal class SwitchResultPresenter(
    private val project: Project,
    private val service: BranchSwitcherService,
) {
    fun showWriteBusy() {
        Notifier.warn(project, Bundle.msg("notify.write.busy"), Bundle.msg("notify.write.busy.msg"))
    }

    /**
     * Presents one single-repository switch outcome. Keeps the single-repo result
     * mapping in this presenter alongside the full-preset path, so both switch paths
     * present results from the same class instead of inlining it at the call site.
     */
    fun presentSingleSwitch(
        path: String,
        target: String,
        result: SingleRepositorySwitchResult,
        operationId: String,
        log: AppLogger,
    ) {
        when (result) {
            is SingleRepositorySwitchResult.Success ->
                Notifier.info(
                    project,
                    Bundle.msg("switch.complete"),
                    Bundle.msg("notify.switch.only.complete", path, target),
                    operationId,
                )
            is SingleRepositorySwitchResult.GitFailure ->
                Notifier.warn(
                    project,
                    Bundle.msg("switch.failed"),
                    Bundle.msg("notify.switch.only.failed", path, target),
                    operationId,
                )
            is SingleRepositorySwitchResult.LockBlocked -> {
                val label = if (path == ".") Bundle.msg("label.main.repo") else path
                Notifier.warn(
                    project,
                    Bundle.msg("switch.failed"),
                    Bundle.msg("index.lock.blocking", label, result.lockPath),
                    operationId,
                )
            }
            is SingleRepositorySwitchResult.Skipped ->
                log.info("single switch skipped: path=$path target=$target")
            SingleRepositorySwitchResult.Cancelled ->
                log.debug("single switch cancelled: path=$path target=$target")
            is SingleRepositorySwitchResult.Unexpected ->
                log.warn("unexpected single-switch result: $result")
        }
    }

    fun presentSwitchResult(
        preset: Preset,
        runResult: SwitchRunResult,
        onSuccess: (() -> Unit)?,
        onRollback: (SwitchExecutionResult) -> Unit,
        operationId: String,
    ) {
        when {
            runResult.cancelled -> notifyCancellation(runResult, operationId)
            runResult.ok -> notifySuccessfulSwitch(preset, runResult.execution, onSuccess, operationId)
            runResult.recovery != null -> notifyRecoveredFailure(preset, runResult, onRollback, operationId)
            else -> notifySwitchFailure(preset, runResult.execution, onRollback, operationId)
        }
    }

    /**
     * A FAILED switch with a checkpoint was automatically rolled back (repositories
     * first, then the stashed WIP). Present the recovery outcome, and offer a manual
     * rollback retry only when that automatic recovery was itself incomplete.
     */
    private fun notifyRecoveredFailure(
        preset: Preset,
        runResult: SwitchRunResult,
        onRollback: (SwitchExecutionResult) -> Unit,
        operationId: String,
    ) {
        val recovery = runResult.recovery
        val execution = runResult.execution
        if (recovery?.ok == true) {
            Notifier.info(
                project,
                Bundle.msg("switch.failed.recovered"),
                Bundle.msg("notify.switch.failed.recovered") + retainedStateNotice(execution),
                operationId,
            )
            return
        }
        val message = Bundle.msg("notify.switch.partial.msg", preset.name) + retainedStateNotice(execution)
        presentRollbackOrError(execution, message, onRollback, operationId)
    }

    fun presentRollbackResult(
        execution: SwitchExecutionResult,
        succeeded: Boolean,
        recoveryIssues: List<OperationIssue> = emptyList(),
        operationId: String,
    ) {
        val retainedNotice = retainedStateNotice(execution)
        val lockLines = lockBlockedLines(execution.issues + recoveryIssues)
        val detail = joinNotificationDetails(lockLines.joinToString("\n"), retainedNotice)
        if (succeeded) {
            Notifier.info(
                project,
                Bundle.msg("rollback.complete"),
                appendNotificationDetail(Bundle.msg("notify.rollback.complete.msg"), detail),
                operationId,
            )
        } else {
            Notifier.error(
                project,
                Bundle.msg("rollback.failed"),
                appendNotificationDetail(Bundle.msg("notify.rollback.partial.msg"), detail),
                operationId,
            )
        }
    }

    private fun notifySuccessfulSwitch(
        preset: Preset,
        execution: SwitchExecutionResult?,
        onSuccess: (() -> Unit)?,
        operationId: String,
    ) {
        service.addHistory(preset.name, preset.id)
        onSuccess?.invoke()
        val issues = execution?.issues.orEmpty()
        if (issues.isNotEmpty()) {
            // The switch itself completed, but some stashed WIP could not be restored
            // (a stale index.lock or a cancellation during the restore). Surface the
            // preserved stash instead of claiming a fully clean success.
            val lockLines = lockBlockedLines(issues)
            val detailLines = if (lockLines.isNotEmpty()) {
                lockLines
            } else {
                issues.mapNotNull(OperationIssue::diagnostic)
            }
            Notifier.warn(
                project,
                Bundle.msg("switch.complete"),
                appendNotificationDetail(
                    Bundle.msg("notify.switch.complete.msg", preset.name),
                    joinNotificationDetails(
                        Bundle.msg("notify.switch.stash.restore.suspended"),
                        detailLines.joinToString("\n"),
                        retainedStashBackupNotice(execution).trim(),
                    ),
                ),
                operationId,
            )
        } else {
            Notifier.info(
                project,
                Bundle.msg("switch.complete"),
                Bundle.msg("notify.switch.complete.msg", preset.name) + retainedStashBackupNotice(execution),
                operationId,
            )
        }
    }

    private fun notifyCancellation(runResult: SwitchRunResult, operationId: String) {
        val recovery = runResult.recovery ?: return
        val retainedNotice = retainedStateNotice(runResult.execution)
        if (recovery.ok) {
            Notifier.info(
                project,
                Bundle.msg("switch.cancelled"),
                Bundle.msg("notify.switch.cancelled.recovered") + retainedNotice,
                operationId,
            )
        } else {
            Notifier.error(
                project,
                Bundle.msg("switch.cancelled"),
                Bundle.msg("notify.switch.cancelled.partial") + retainedNotice,
                operationId,
            )
        }
    }

    private fun notifySwitchFailure(
        preset: Preset,
        execution: SwitchExecutionResult?,
        onRollback: (SwitchExecutionResult) -> Unit,
        operationId: String,
    ) {
        val lockLines = lockBlockedLines(execution?.issues.orEmpty())
        if (lockLines.isNotEmpty()) {
            // A stale index.lock blocked the switch before any mutation, so there is
            // nothing to roll back — surface the actionable message instead.
            Notifier.error(project, Bundle.msg("switch.failed"), lockLines.joinToString("\n"), operationId)
            return
        }
        val message = Bundle.msg("notify.switch.partial.msg", preset.name) +
            retainedStateNotice(execution)
        presentRollbackOrError(execution, message, onRollback, operationId)
    }

    /**
     * Presents a FAILED switch that still has a checkpoint: offer a manual rollback,
     * or a plain error when the checkpoint was never recorded (nothing to roll back).
     */
    private fun presentRollbackOrError(
        execution: SwitchExecutionResult?,
        message: String,
        onRollback: (SwitchExecutionResult) -> Unit,
        operationId: String,
    ) {
        if (execution?.checkpoint == null) {
            Notifier.error(project, Bundle.msg("switch.failed"), message, operationId)
            return
        }
        Notifier.rollbackAction(
            project,
            Bundle.msg("switch.failed"),
            message + Bundle.msg("notify.switch.rollback.hint"),
            onRollback = { onRollback(execution) },
            operationId = operationId,
        )
    }

    /** Localized, actionable lines for every stale-index.lock issue in [issues]. */
    private fun lockBlockedLines(issues: List<OperationIssue>): List<String> =
        lockBlockedPresentations(issues, Bundle.msg("label.main.repo"))
            .map { Bundle.msg("index.lock.blocking", it.repositoryLabel, it.lockPath) }

    private fun retainedInitializationNotice(execution: SwitchExecutionResult?): String {
        val paths = execution?.state?.initializedSubmodulesSnapshot().orEmpty()
        if (paths.isEmpty()) return ""
        return " " + Bundle.msg("notify.switch.init.retained", paths.sorted().joinToString(", "))
    }

    private fun retainedStashBackupNotice(execution: SwitchExecutionResult?): String {
        val count = execution?.state?.retainedStashBackupsSnapshot()?.size ?: 0
        if (count == 0) return ""
        return " " + Bundle.msg("notify.switch.stash.backups.retained", count)
    }

    private fun retainedStateNotice(execution: SwitchExecutionResult?): String =
        retainedInitializationNotice(execution) + retainedStashBackupNotice(execution)
}

internal fun joinNotificationDetails(vararg details: String): String =
    details.filter(String::isNotEmpty).joinToString("\n")

/**
 * Appends an optional detail block to a localized base message. The base texts
 * (e.g. the Chinese rollback messages) carry no trailing punctuation and a detail
 * block can start with a repository label, so a space is inserted to avoid glue.
 */
internal fun appendNotificationDetail(base: String, detail: String): String =
    if (detail.isEmpty()) base else "$base $detail"
