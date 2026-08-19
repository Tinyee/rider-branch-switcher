package com.submodule.branchswitcher

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import org.jetbrains.annotations.Nls
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Convenience wrapper around IntelliJ's [NotificationGroupManager].
 * Provides typed helpers for info/warn/error balloons + the rollback action notification.
 */
object Notifier {
    private const val GROUP_ID = "Submodule Branch Switcher"

    fun info(project: Project?, @Nls title: String, @Nls content: String, operationId: String? = null) =
        notify(project, title, content, NotificationType.INFORMATION, operationId)

    fun warn(project: Project?, @Nls title: String, @Nls content: String, operationId: String? = null) =
        notify(project, title, content, NotificationType.WARNING, operationId)

    fun error(project: Project?, @Nls title: String, @Nls content: String, operationId: String? = null) =
        notify(project, title, content, NotificationType.ERROR, operationId)

    /**
     * Shows an ERROR notification with a clickable "rollback" action button.
     * [onRollback] is invoked when the user clicks the action in the balloon.
     */
    fun rollbackAction(
        project: Project?,
        @Nls title: String,
        @Nls content: String,
        onRollback: () -> Unit,
        operationId: String? = null,
    ) = actionNotification(project, title, content, NotificationType.ERROR, Bundle.msg("rollback.action"), onRollback, operationId)

    /**
     * Shows a WARNING notification with one clickable action button.
     * [onAction] is invoked when the user clicks the action in the balloon.
     */
    fun warnAction(
        project: Project?,
        @Nls title: String,
        @Nls content: String,
        @Nls actionLabel: String,
        onAction: () -> Unit,
        operationId: String? = null,
    ) = actionNotification(project, title, content, NotificationType.WARNING, actionLabel, onAction, operationId)

    /**
     * Appends the correlation ID to the content so a balloon can be matched to the
     * operation's log lines (the tool window prefixes every line with `[<id>...]`).
     */
    private fun contentWithOperation(content: String, operationId: String?): String =
        if (operationId == null) content else "$content\nop: $operationId"

    private fun actionNotification(
        project: Project?,
        title: String,
        content: String,
        type: NotificationType,
        actionLabel: String,
        onAction: () -> Unit,
        operationId: String?,
    ) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, contentWithOperation(content, operationId), type)
        val started = AtomicBoolean(false)
        notification.addAction(com.intellij.notification.NotificationAction.createSimple(actionLabel) {
            if (started.compareAndSet(false, true)) {
                notification.expire()
                onAction()
            }
        })
        notification.notify(project)
    }

    private fun notify(
        project: Project?,
        title: String,
        content: String,
        type: NotificationType,
        operationId: String?,
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, contentWithOperation(content, operationId), type)
            .notify(project)
    }
}
