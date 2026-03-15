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
import paol0b.azuredevops.ui.CompletePullRequestDialog
import paol0b.azuredevops.util.NotificationUtil
import paol0b.azuredevops.util.PluginUtil

/**
 * Action to complete (merge) a Pull Request
 */
class CompletePullRequestAction(
    private val pullRequest: PullRequest,
    private val onCompleted: (() -> Unit)? = null
) : AnAction("Complete PR...", "Complete and merge this Pull Request", null) {

    private var cachedProject: Project? = null

    /**
     * Perform the complete PR action without requiring an AnActionEvent
     */
    fun performCompletePR(project: Project) {
        cachedProject = project

        // Get current user ID
        var currentUserId: String? = null
        try {
            val apiClient = AzureDevOpsApiClient.getInstance(project)
            currentUserId = apiClient.getCurrentUserIdCached()
        } catch (ex: Exception) {
            // Continue without user ID - some features will be limited
        }

        // Show dialog to get completion options
        val dialog = CompletePullRequestDialog(project, pullRequest, isAutoComplete = false, currentUserId = currentUserId)
        
        if (!dialog.showAndGet()) {
            return
        }

        val completionOptions = dialog.getCompletionOptions()
        val comment = dialog.getComment()

        // Execute completion
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Completing PR #${pullRequest.pullRequestId}...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                
                try {
                    val apiClient = AzureDevOpsApiClient.getInstance(project)
                    val completedPr = apiClient.completePullRequest(
                        pullRequest.pullRequestId,
                        completionOptions,
                        comment
                    )

                    ApplicationManager.getApplication().invokeLater {
                        NotificationUtil.info(project, "Pull Request Completed", "PR #${pullRequest.pullRequestId} has been successfully merged")
                        onCompleted?.invoke()
                    }

                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        val errorMessage = PluginUtil.parseApiErrorMessage(e.message)

                        if (errorMessage.contains("permission", ignoreCase = true) ||
                            errorMessage.contains("policies", ignoreCase = true)) {

                            val result = Messages.showYesNoDialog(
                                project,
                                "You don't have permission to complete this PR or policies are not met.\n\n" +
                                        "Error: $errorMessage\n\n" +
                                        "Would you like to set auto-complete instead?",
                                "Cannot Complete PR",
                                "Set Auto-Complete",
                                "Cancel",
                                Messages.getWarningIcon()
                            )

                            if (result == Messages.YES) {
                                // Trigger auto-complete action
                                val autoCompleteAction = SetAutoCompletePullRequestAction(pullRequest, onCompleted)
                                autoCompleteAction.performSetAutoComplete(project)
                            }
                        } else {
                            NotificationUtil.error(project, "Failed to Complete PR", errorMessage)
                        }
                    }
                }
            }
        })
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        performCompletePR(project)
    }

    override fun update(e: AnActionEvent) {
        // Show "Complete PR" only if PR is ready to complete (all checks passed, reviews approved)
        // This matches when Azure DevOps shows the "Complete" button
        e.presentation.isEnabledAndVisible = e.project != null && 
            pullRequest.isActive() && 
            pullRequest.isReadyToComplete()
    }

}
