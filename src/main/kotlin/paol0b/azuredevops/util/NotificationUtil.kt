package paol0b.azuredevops.util

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

/**
 * Centralized notification utility for consistent notifications across the plugin.
 * Uses the single notification group registered in plugin.xml.
 */
object NotificationUtil {

    private const val NOTIFICATION_GROUP_ID = "AzureDevOps.Notifications"

    fun info(project: Project?, title: String, content: String) {
        notify(project, title, content, NotificationType.INFORMATION)
    }

    fun warning(project: Project?, title: String, content: String) {
        notify(project, title, content, NotificationType.WARNING)
    }

    fun error(project: Project?, title: String, content: String) {
        notify(project, title, content, NotificationType.ERROR)
    }

    fun notify(project: Project?, title: String, content: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)
            .notify(project)
    }

    /**
     * Creates a notification with actions (e.g., "Open in Browser")
     */
    fun notifyWithAction(
        project: Project?,
        title: String,
        content: String,
        type: NotificationType,
        vararg actions: com.intellij.openapi.actionSystem.AnAction
    ) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(NOTIFICATION_GROUP_ID)
            .createNotification(title, content, type)

        actions.forEach { notification.addAction(it) }
        notification.notify(project)
    }
}
