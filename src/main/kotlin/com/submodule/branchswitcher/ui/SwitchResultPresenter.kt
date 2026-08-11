package com.submodule.branchswitcher.ui

import com.intellij.openapi.project.Project
import com.submodule.branchswitcher.Bundle
import com.submodule.branchswitcher.Notifier
import com.submodule.branchswitcher.model.Preset
import com.submodule.branchswitcher.service.BranchSwitcherService
import com.submodule.branchswitcher.switch.OperationIssue
import com.submodule.branchswitcher.switch.OperationIssueCode
import com.submodule.branchswitcher.switch.SwitchExecutionResult
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
            else -> notifySwitchFailure(preset, runResult.execution, onRollback)
        }
    }

    fun presentRollbackResult(
        execution: SwitchExecutionResult,
        succeeded: Boolean,
        recoveryIssues: List<OperationIssue> = emptyList(),
    ) {
        val retainedNotice = retainedStateNotice(execution)
        val lockLines = lockBlockedLines(execution.issues + recoveryIssues)
        val detail = if (lockLines.isNotEmpty()) {
            lockLines.joinToString("\n") + retainedNotice
        } else {
            retainedNotice
        }
        if (succeeded) {
            Notifier.info(
                project,
                Bundle.msg("rollback.complete"),
                Bundle.msg("notify.rollback.complete.msg") + detail,
            )
        } else {
            Notifier.error(
                project,
                Bundle.msg("rollback.failed"),
                Bundle.msg("notify.rollback.partial.msg") + detail,
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
        Notifier.info(
            project,
            Bundle.msg("switch.complete"),
            Bundle.msg("notify.switch.complete.msg", preset.name) + retainedStashBackupNotice(execution),
        )
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
        issues.filter { it.code == OperationIssueCode.INDEX_LOCK_BLOCKING }
            .map { issue ->
                Bundle.msg(
                    "index.lock.blocking",
                    repositoryLabel(issue.repositoryPath),
                    issue.lockPath.orEmpty(),
                )
            }

    private fun repositoryLabel(repositoryPath: String?): String =
        if (repositoryPath.isNullOrBlank() || repositoryPath == ".") {
            Bundle.msg("label.main.repo")
        } else {
            repositoryPath
        }

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
