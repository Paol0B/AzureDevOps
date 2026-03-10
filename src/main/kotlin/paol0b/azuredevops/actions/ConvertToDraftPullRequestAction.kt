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

/**
 * Action to convert a Pull Request to draft, or publish a draft PR.
 * Only available for PRs created by the current user.
 *
 * @param convertToDraft true to convert to draft, false to publish (remove draft status)
 */
class ConvertToDraftPullRequestAction(
    private val pullRequest: PullRequest,
    private val currentUserId: String?,
    private val convertToDraft: Boolean,
    private val onCompleted: (() -> Unit)? = null
) : AnAction(
    if (convertToDraft) "Convert to Draft..." else "Publish PR...",
    if (convertToDraft) "Convert this Pull Request to a draft" else "Publish this draft Pull Request",
    null
) {

    fun perform(project: Project) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            if (convertToDraft) "Converting PR #${pullRequest.pullRequestId} to draft..."
            else "Publishing PR #${pullRequest.pullRequestId}...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                try {
                    val apiClient = AzureDevOpsApiClient.getInstance(project)
                    apiClient.updatePullRequestDraftStatus(pullRequest, convertToDraft)

                    ApplicationManager.getApplication().invokeLater {
                        if (convertToDraft) {
                            NotificationGroupManager.getInstance()
                                .getNotificationGroup("Azure DevOps Notifications")
                                ?.createNotification(
                                    "Pull Request Converted to Draft",
                                    "PR #${pullRequest.pullRequestId} is now a draft",
                                    NotificationType.INFORMATION
                                )
                                ?.notify(project)
                        } else {
                            NotificationGroupManager.getInstance()
                                .getNotificationGroup("Azure DevOps Notifications")
                                ?.createNotification(
                                    "Pull Request Published",
                                    "PR #${pullRequest.pullRequestId} has been published",
                                    NotificationType.INFORMATION
                                )
                                ?.notify(project)
                        }
                        onCompleted?.invoke()
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        val title = if (convertToDraft) "Failed to Convert PR to Draft" else "Failed to Publish PR"
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("Azure DevOps Notifications")
                            ?.createNotification(
                                title,
                                e.message?.take(200) ?: "Unknown error occurred",
                                NotificationType.ERROR
                            )
                            ?.notify(project)
                    }
                }
            }
        })
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        perform(project)
    }

    override fun update(e: AnActionEvent) {
        val visible = e.project != null &&
            pullRequest.isActive() &&
            pullRequest.isCreatedByUser(currentUserId) &&
            (if (convertToDraft) pullRequest.isDraft != true else pullRequest.isDraft == true)
        e.presentation.isEnabledAndVisible = visible
    }
}
