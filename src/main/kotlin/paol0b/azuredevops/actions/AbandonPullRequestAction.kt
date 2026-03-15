package paol0b.azuredevops.actions

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
import paol0b.azuredevops.util.NotificationUtil
import paol0b.azuredevops.util.PluginUtil

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
                        NotificationUtil.info(project, "Pull Request Abandoned", "PR #${pullRequest.pullRequestId} has been abandoned")
                        onAbandoned?.invoke()
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        NotificationUtil.error(project, "Failed to Abandon PR", PluginUtil.parseApiErrorMessage(e.message))
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

}