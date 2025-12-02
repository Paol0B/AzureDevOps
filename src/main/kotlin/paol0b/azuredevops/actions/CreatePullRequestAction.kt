package paol0b.azuredevops.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import paol0b.azuredevops.model.Identity
import paol0b.azuredevops.model.PullRequestResponse
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.AzureDevOpsApiException
import paol0b.azuredevops.services.AzureDevOpsConfigService
import paol0b.azuredevops.services.GitRepositoryService
import paol0b.azuredevops.ui.CreatePullRequestDialog
import java.awt.Desktop
import java.net.URI

/**
 * Action to create a Pull Request on Azure DevOps
 */
class CreatePullRequestAction : AnAction() {

    private val logger = Logger.getInstance(CreatePullRequestAction::class.java)

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
                // Open settings
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
                // Open settings
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "Azure DevOps")
            }
            return
        }

        // Show the dialog to create the PR
        val dialog = CreatePullRequestDialog.create(project, gitService)
        if (dialog != null && dialog.showAndGet()) {
            val sourceBranch = dialog.getSourceBranch()
            val targetBranch = dialog.getTargetBranch()
            val title = dialog.getPrTitle()
            val description = dialog.getDescription()
            val requiredReviewers = dialog.getRequiredReviewers()
            val optionalReviewers = dialog.getOptionalReviewers()

            if (sourceBranch != null && targetBranch != null) {
                createPullRequest(
                    project, 
                    sourceBranch.name, 
                    targetBranch.name, 
                    title, 
                    description,
                    requiredReviewers,
                    optionalReviewers
                )
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        // Show the action only if it is an Azure DevOps repository
        val configService = AzureDevOpsConfigService.getInstance(project)
        e.presentation.isEnabledAndVisible = configService.isAzureDevOpsRepository()
    }

    /**
     * Creates the Pull Request asynchronously
     */
    private fun createPullRequest(
        project: Project,
        sourceBranch: String,
        targetBranch: String,
        title: String,
        description: String,
        requiredReviewers: List<Identity>,
        optionalReviewers: List<Identity>
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Creating pull request on Azure DevOps...",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true

                try {
                    val apiClient = AzureDevOpsApiClient.getInstance(project)

                    // Check if there is already an active PR between these branches
                    indicator.text = "Checking for existing Pull Requests..."
                    val existingPr = apiClient.findActivePullRequest(sourceBranch, targetBranch)

                    if (existingPr != null) {
                        // There is already an active PR!
                        val sourceDisplayName = sourceBranch.removePrefix("refs/heads/")
                        val targetDisplayName = targetBranch.removePrefix("refs/heads/")

                        showExistingPrError(
                            project,
                            existingPr.pullRequestId,
                            existingPr.title,
                            sourceDisplayName,
                            targetDisplayName
                        )
                        return
                    }

                    // No existing PR, proceed with creation
                    indicator.text = "Sending request to Azure DevOps..."
                    val response = apiClient.createPullRequest(
                        sourceBranch = sourceBranch,
                        targetBranch = targetBranch,
                        title = title,
                        description = description,
                        requiredReviewers = requiredReviewers,
                        optionalReviewers = optionalReviewers
                    )

                    // Show success notification
                    showSuccessNotification(project, response)

                } catch (e: AzureDevOpsApiException) {
                    logger.error("Failed to create pull request", e)
                    showErrorNotification(project, e.message ?: "Unknown error")
                } catch (e: Exception) {
                    logger.error("Unexpected error creating pull request", e)
                    showErrorNotification(project, "Unexpected error: ${e.message}")
                }
            }
        })
    }

    /**
     * Shows a success notification
     */
    private fun showSuccessNotification(project: Project, response: PullRequestResponse) {
        val prUrl = getPullRequestUrl(project, response.pullRequestId)

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("AzureDevOps.Notifications")
            .createNotification(
                "Pull Request created successfully",
                "PR #${response.pullRequestId}: ${response.title}<br>" +
                        "Source: ${response.sourceRefName.removePrefix("refs/heads/")}<br>" +
                        "Target: ${response.targetRefName.removePrefix("refs/heads/")}",
                NotificationType.INFORMATION
            )

        // Add action to open the PR in the browser
        if (prUrl != null) {
            notification.addAction(object : com.intellij.openapi.actionSystem.AnAction("Open in Browser") {
                override fun actionPerformed(e: AnActionEvent) {
                    openInBrowser(prUrl)
                    notification.expire()
                }
            })
        }

        notification.notify(project)
    }

    /**
     * Shows an error notification
     */
    private fun showErrorNotification(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AzureDevOps.Notifications")
            .createNotification(
                "Failed to create Pull Request",
                message,
                NotificationType.ERROR
            )
            .notify(project)
    }

    /**
     * Shows an error when there is already a PR between the branches
     */
    private fun showExistingPrError(
        project: Project,
        prId: Int,
        prTitle: String,
        sourceBranch: String,
        targetBranch: String
    ) {
        val prUrl = getPullRequestUrl(project, prId)

        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("AzureDevOps.Notifications")
            .createNotification(
                "Pull Request already exists",
                "There is already an active Pull Request from <b>$sourceBranch</b> to <b>$targetBranch</b>:<br><br>" +
                        "PR #$prId: $prTitle",
                NotificationType.WARNING
            )

        // Add action to open the existing PR
        if (prUrl != null) {
            notification.addAction(object : com.intellij.openapi.actionSystem.AnAction("Open Existing PR") {
                override fun actionPerformed(e: AnActionEvent) {
                    openInBrowser(prUrl)
                    notification.expire()
                }
            })
        }

        notification.notify(project)
    }

    /**
     * Builds the Pull Request URL
     */
    private fun getPullRequestUrl(project: Project, prId: Int): String? {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) return null

        // URL format: https://dev.azure.com/{org}/{project}/_git/{repo}/pullrequest/{prId}
        return "https://dev.azure.com/${config.organization}/${config.project}/_git/${config.repository}/pullrequest/$prId"
    }

    /**
     * Opens a URL in the default browser
     */
    private fun openInBrowser(url: String) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            }
        } catch (e: Exception) {
            logger.error("Failed to open browser", e)
        }
    }
}
