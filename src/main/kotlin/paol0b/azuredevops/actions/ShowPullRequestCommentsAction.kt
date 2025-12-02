package paol0b.azuredevops.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.Messages
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.GitRepositoryService
import paol0b.azuredevops.services.PullRequestCommentsService

/**
 * Action to load and display PR comments in the current file
 */
class ShowPullRequestCommentsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        // Check that there is a current branch
        val gitService = GitRepositoryService.getInstance(project)
        val currentBranch = gitService.getCurrentBranch() ?: run {
            Messages.showWarningDialog(
                project,
                "No active Git branch.",
                "Unable to Display Comments"
            )
            return
        }

        // Search for the PR associated with the branch
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val pullRequest = apiClient.findPullRequestForBranch(currentBranch.displayName)

                if (pullRequest == null) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showMessageDialog(
                            project,
                            "The branch '${currentBranch.displayName}' does not have an active Pull Request.\n\n" +
                                    "Create a Pull Request for this branch to view comments.",
                            "No Pull Request",
                            Messages.getInformationIcon()
                        )
                    }
                    return@executeOnPooledThread
                }

                // Carica i commenti
                val commentsService = PullRequestCommentsService.getInstance(project)
                ApplicationManager.getApplication().invokeLater {
                    commentsService.loadCommentsInEditor(editor, file, pullRequest)
                    
                    Messages.showMessageDialog(
                        project,
                        "Comments loaded for PR #${pullRequest.pullRequestId}:\n${pullRequest.title}\n\n" +
                                "Comments are highlighted in the code.\n" +
                                "Click the icon in the gutter to view and reply.",
                        "Comments Loaded",
                        Messages.getInformationIcon()
                    )
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Error while loading comments:\n${e.message}",
                        "Error"
                    )
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        
        e.presentation.isEnabledAndVisible = project != null && editor != null && file != null
    }
}
