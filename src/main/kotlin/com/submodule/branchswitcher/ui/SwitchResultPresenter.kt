package com.submodule.branchswitcher.ui

import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.switch.OperationIssue
import com.submodule.branchswitcher.switch.SwitchExecutionResult
import com.submodule.branchswitcher.switch.lockBlockedPresentations
import com.submodule.branchswitcher.workflow.SwitchRunResult

/** Maps structured switch and rollback outcomes to history and IDE notifications. */
internal class SwitchResultPresenter(
    private val project: Project,
    private val service: BranchSwitcherService,
) {
    fun showWriteBusy() {
        Notifier.warn(project, Bundle.msg("notify.write.busy"), Bundle.msg("notify.write.busy.msg"))
    }

    fun presentSwitchResult(
        preset: Preset,
        runResult: SwitchRunResult,
        onSuccess: (() -> Unit)?,
        onRollback: (SwitchExecutionResult) -> Unit,
    ) {
        when {
            runResult.cancelled -> notifyCancellation(runResult)
            runResult.ok -> notifySuccessfulSwitch(preset, runResult.execution, onSuccess)
            runResult.recovery != null -> notifyRecoveredFailure(preset, runResult, onRollback)
            else -> notifySwitchFailure(preset, runResult.execution, onRollback)
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
    ) {
        val recovery = runResult.recovery
        val execution = runResult.execution
        if (recovery?.ok == true) {
            Notifier.info(
                project,
                Bundle.msg("switch.failed.recovered"),
                Bundle.msg("notify.switch.failed.recovered") + retainedStateNotice(execution),
            )
            return
        }
        val message = Bundle.msg("notify.switch.partial.msg", preset.name) + retainedStateNotice(execution)
        if (execution?.checkpoint == null) {
            Notifier.error(project, Bundle.msg("switch.failed"), message)
            return
        }
        Notifier.rollbackAction(
            project,
            Bundle.msg("switch.failed"),
            message + Bundle.msg("notify.switch.rollback.hint"),
        ) { onRollback(execution) }
    }

    fun presentRollbackResult(
        execution: SwitchExecutionResult,
        succeeded: Boolean,
        recoveryIssues: List<OperationIssue> = emptyList(),
    ) {
        val retainedNotice = retainedStateNotice(execution)
        val lockLines = lockBlockedLines(execution.issues + recoveryIssues)
        val detail = joinNotificationDetails(lockLines.joinToString("\n"), retainedNotice)
        if (succeeded) {
            Notifier.info(
                project,
                Bundle.msg("rollback.complete"),
                appendNotificationDetail(Bundle.msg("notify.rollback.complete.msg"), detail),
            )
        } else {
            Notifier.error(
                project,
                Bundle.msg("rollback.failed"),
                appendNotificationDetail(Bundle.msg("notify.rollback.partial.msg"), detail),
            )
        }
    }

    private fun notifySuccessfulSwitch(
        preset: Preset,
        execution: SwitchExecutionResult?,
        onSuccess: (() -> Unit)?,
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
            )
        } else {
            Notifier.info(
                project,
                Bundle.msg("switch.complete"),
                Bundle.msg("notify.switch.complete.msg", preset.name) + retainedStashBackupNotice(execution),
            )
        }
    }

    private fun notifyCancellation(runResult: SwitchRunResult) {
        val recovery = runResult.recovery ?: return
        val retainedNotice = retainedStateNotice(runResult.execution)
        if (recovery.ok) {
            Notifier.info(
                project,
                Bundle.msg("switch.cancelled"),
                Bundle.msg("notify.switch.cancelled.recovered") + retainedNotice,
            )
        } else {
            Notifier.error(
                project,
                Bundle.msg("switch.cancelled"),
                Bundle.msg("notify.switch.cancelled.partial") + retainedNotice,
            )
        }
    }

    private fun notifySwitchFailure(
        preset: Preset,
        execution: SwitchExecutionResult?,
        onRollback: (SwitchExecutionResult) -> Unit,
    ) {
        val lockLines = lockBlockedLines(execution?.issues.orEmpty())
        if (lockLines.isNotEmpty()) {
            // A stale index.lock blocked the switch before any mutation, so there is
            // nothing to roll back — surface the actionable message instead.
            Notifier.error(project, Bundle.msg("switch.failed"), lockLines.joinToString("\n"))
            return
        }
        val message = Bundle.msg("notify.switch.partial.msg", preset.name) +
            retainedStateNotice(execution)
        if (execution?.checkpoint == null) {
            Notifier.error(project, Bundle.msg("switch.failed"), message)
            return
        }
        Notifier.rollbackAction(
            project,
            Bundle.msg("switch.failed"),
            message + Bundle.msg("notify.switch.rollback.hint"),
        ) {
            onRollback(execution)
        }
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
