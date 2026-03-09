package paol0b.azuredevops.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AzureDevOpsApiClient

class AbandonPullRequestAction(
    private val pullRequest: PullRequest,
    private val currentUserId: String?,
    private val onAbandoned: (() -> Unit)? = null
) : AnAction("Abandon PR...", "Abandon this Pull Request", null) {

    fun performAbandonPR(project: Project) {
        val confirm = Messages.showYesNoDialog(
            project,
            "Abandon PR #${pullRequest.pullRequestId}?\n\n${pullRequest.title}",
            "Abandon Pull Request",
            "Abandon",
            "Cancel",
            Messages.getWarningIcon()
        )

        if (confirm != Messages.YES) {
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Abandoning PR #${pullRequest.pullRequestId}...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                try {
                    val apiClient = AzureDevOpsApiClient.getInstance(project)
                    apiClient.abandonPullRequest(pullRequest)

                    ApplicationManager.getApplication().invokeLater {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Azure DevOps Notifications")
                            .createNotification(
                                "Pull Request Abandoned",
                                "PR #${pullRequest.pullRequestId} has been abandoned",
                                NotificationType.INFORMATION
                            )
                            .notify(project)
                        onAbandoned?.invoke()
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Azure DevOps Notifications")
                            .createNotification(
                                "Failed to Abandon PR",
                                parseErrorMessage(e.message),
                                NotificationType.ERROR
                            )
                            .notify(project)
                    }
                }
            }
        })
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        performAbandonPR(project)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null &&
            pullRequest.isActive() &&
            pullRequest.isCreatedByUser(currentUserId)
    }

    private fun parseErrorMessage(message: String?): String {
        if (message == null) return "Unknown error occurred"

        return when {
            message.contains("TF401179") -> "Only the pull request author can abandon this Pull Request."
            message.contains("TF401171") -> "You don't have permission to abandon this Pull Request."
            else -> message.take(200)
        }
    }
}