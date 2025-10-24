package paol0b.azuredevops.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.Messages
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.AzureDevOpsConfigService
import paol0b.azuredevops.services.GitRepositoryService
import paol0b.azuredevops.services.PullRequestCommentsService

/**
 * Action per mostrare/nascondere i commenti PR nell'editor corrente
 * Stile Visual Studio: toggle rapido dei commenti
 */
class TogglePRCommentsAction : AnAction(
    "Toggle PR Comments",
    "Show or hide Pull Request comments in the current file",
    AllIcons.Toolwindows.ToolWindowMessages
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        
        val commentsService = PullRequestCommentsService.getInstance(project)
        val gitService = GitRepositoryService.getInstance(project)
        val currentBranch = gitService.getCurrentBranch()
        
        if (currentBranch == null) {
            Messages.showInfoMessage(
                project,
                "No active Git branch found.",
                "No Branch"
            )
            return
        }
        
        // Cerca PR per il branch corrente
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val pullRequest = apiClient.findPullRequestForBranch(currentBranch.displayName)
                
                ApplicationManager.getApplication().invokeLater {
                    if (pullRequest != null) {
                        // Carica i commenti
                        commentsService.loadCommentsInEditor(editor, file, pullRequest)
                        
                        Messages.showInfoMessage(
                            project,
                            "Loaded comments for PR #${pullRequest.pullRequestId}: ${pullRequest.title}",
                            "PR Comments Loaded"
                        )
                    } else {
                        Messages.showInfoMessage(
                            project,
                            "No active Pull Request found for branch '${currentBranch.displayName}'.",
                            "No PR Found"
                        )
                    }
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Failed to load PR comments: ${e.message}",
                        "Error"
                    )
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        
        // Abilita l'azione solo se c'è un editor aperto e Azure DevOps è configurato
        val isEnabled = project != null && editor != null
        
        if (isEnabled && project != null) {
            val configService = AzureDevOpsConfigService.getInstance(project)
            e.presentation.isEnabledAndVisible = configService.isAzureDevOpsRepository()
        } else {
            e.presentation.isEnabledAndVisible = false
        }
    }
}
