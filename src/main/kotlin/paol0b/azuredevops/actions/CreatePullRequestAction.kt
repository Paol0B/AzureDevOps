package paol0b.azuredevops.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import paol0b.azuredevops.services.AzureDevOpsConfigService
import paol0b.azuredevops.services.GitRepositoryService
import paol0b.azuredevops.toolwindow.PullRequestToolWindowFactory

/**
 * Action to create a Pull Request on Azure DevOps.
 * Opens the inline Create PR panel in the PR ToolWindow.
 */
class CreatePullRequestAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Check that the project has a Git repository
        val gitService = GitRepositoryService.getInstance(project)
        if (!gitService.hasGitRepository()) {
            Messages.showErrorDialog(
                project,
                "No Git repository found in this project.",
                "Missing Git Repository"
            )
            return
        }

        // Check that it's an Azure DevOps repository or manually configured
        val configService = AzureDevOpsConfigService.getInstance(project)
        if (!configService.isAzureDevOpsRepository()) {
            val result = Messages.showYesNoDialog(
                project,
                "Azure DevOps is not configured for this project.\n\n" +
                        "The plugin can:\n" +
                        "1. Automatically detect repositories cloned from Azure DevOps\n" +
                        "2. Be manually configured if the repository is not Azure DevOps\n\n" +
                        "Do you want to configure it now?",
                "Azure DevOps Configuration Required",
                Messages.getQuestionIcon()
            )

            if (result == Messages.YES) {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "Azure DevOps")
            }
            return
        }

        // Check that the PAT is configured
        if (!configService.isConfigured()) {
            val result = Messages.showYesNoDialog(
                project,
                "The Personal Access Token (PAT) is not configured.\n\n" +
                        "Detected repository: ${configService.getDetectedRepositoryInfo()}\n\n" +
                        "Do you want to configure the PAT now?",
                "PAT Configuration Required",
                Messages.getQuestionIcon()
            )

            if (result == Messages.YES) {
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "Azure DevOps")
            }
            return
        }

        // Open the Create PR panel in the ToolWindow
        PullRequestToolWindowFactory.openCreatePrTab(project)
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        val configService = AzureDevOpsConfigService.getInstance(project)
        e.presentation.isEnabledAndVisible = configService.isAzureDevOpsRepository()
    }
}
