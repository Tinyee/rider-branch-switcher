package com.submodule.branchswitcher

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * Convenience wrapper around IntelliJ's [NotificationGroupManager].
 * Provides typed helpers for info/warn/error balloons + the rollback action notification.
 */
object Notifier {
    private const val GROUP_ID = "Submodule Branch Switcher"

    fun info(project: Project?, title: String, content: String) =
        notify(project, title, content, NotificationType.INFORMATION)

    fun warn(project: Project?, title: String, content: String) =
        notify(project, title, content, NotificationType.WARNING)

    fun error(project: Project?, title: String, content: String) =
        notify(project, title, content, NotificationType.ERROR)

    /**
     * Shows an ERROR notification with a clickable "rollback" action button.
     * [onRollback] is invoked when the user clicks the action in the balloon.
     */
    fun rollbackAction(
        project: Project?,
        title: String,
        content: String,
        onRollback: () -> Unit,
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, content, NotificationType.ERROR)
            .addAction(com.intellij.notification.NotificationAction.createSimple("回滚到切换前") {
                onRollback()
            })
            .notify(project)
    }

    private fun notify(project: Project?, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }
}
