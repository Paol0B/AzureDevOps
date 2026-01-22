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
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.ui.CompletePullRequestDialog

/**
 * Action to set auto-complete on a Pull Request
 */
class SetAutoCompletePullRequestAction(
    private val pullRequest: PullRequest,
    private val onCompleted: (() -> Unit)? = null
) : AnAction("Set Auto-Complete...", "Set auto-complete options for this Pull Request", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Get current user ID
        var currentUserId: String? = null
        try {
            val apiClient = AzureDevOpsApiClient.getInstance(project)
            currentUserId = apiClient.getCurrentUserIdCached()
        } catch (ex: Exception) {
            // Continue without user ID - some features will be limited
        }

        // Show dialog to get completion options
        val dialog = CompletePullRequestDialog(project, pullRequest, isAutoComplete = true, currentUserId = currentUserId)
        
        if (!dialog.showAndGet()) {
            return
        }

        val completionOptions = dialog.getCompletionOptions()
        val comment = dialog.getComment()

        // Execute auto-complete setting
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Setting auto-complete on PR #${pullRequest.pullRequestId}...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                
                try {
                    val apiClient = AzureDevOpsApiClient.getInstance(project)
                    val updatedPr = apiClient.setAutoComplete(
                        pullRequest.pullRequestId,
                        completionOptions,
                        comment
                    )

                    ApplicationManager.getApplication().invokeLater {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Azure DevOps Notifications")
                            .createNotification(
                                "Auto-Complete Set",
                                "PR #${pullRequest.pullRequestId} will auto-complete when policies are met",
                                NotificationType.INFORMATION
                            )
                            .notify(project)
                        onCompleted?.invoke()
                    }
                    
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        val errorMessage = parseErrorMessage(e.message)
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Azure DevOps Notifications")
                            .createNotification(
                                "Failed to Set Auto-Complete",
                                errorMessage,
                                NotificationType.ERROR
                            )
                            .notify(project)
                    }
                }
            }
        })
    }

    override fun update(e: AnActionEvent) {
        // Show "Set Auto-Complete" only if:
        // - PR is active
        // - Auto-complete is NOT already set
        // - PR is NOT yet ready to complete (if ready, user should use Complete instead)
        e.presentation.isEnabledAndVisible = e.project != null && 
            pullRequest.isActive() && 
            !pullRequest.hasAutoComplete() && 
            !pullRequest.isReadyToComplete()
    }

    private fun parseErrorMessage(message: String?): String {
        if (message == null) return "Unknown error occurred"
        
        // Extract meaningful error from Azure DevOps API responses
        return when {
            message.contains("TF401171") -> "You don't have permission to set auto-complete on this Pull Request."
            else -> message.take(200) // Limit error message length
        }
    }
}
