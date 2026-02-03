package paol0b.azuredevops.actions

import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import okhttp3.HttpUrl.Companion.toHttpUrl
import paol0b.azuredevops.services.AzureDevOpsConfigService
import paol0b.azuredevops.services.GitRepositoryService

/**
 * Action to open the current Azure DevOps repository in the browser
 */
class OpenRepositoryInBrowserAction : AnAction() {

    private val logger = Logger.getInstance(OpenRepositoryInBrowserAction::class.java)

    override fun getActionUpdateThread(): ActionUpdateThread {
        return ActionUpdateThread.BGT
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        try {
            val url = buildRepositoryUrl(project)
            BrowserUtil.browse(url)
            
            logger.info("Opened repository in browser: $url")
        } catch (ex: Exception) {
            logger.error("Failed to open repository in browser", ex)
            
            NotificationGroupManager.getInstance()
                .getNotificationGroup("AzureDevOps.Notifications")
                .createNotification(
                    "Failed to Open Repository",
                    ex.message ?: "Unknown error",
                    NotificationType.ERROR
                )
                .notify(project)
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabledAndVisible = project != null && isAzureDevOpsProject(project)
    }

    private fun isAzureDevOpsProject(project: Project): Boolean {
        val gitService = GitRepositoryService.getInstance(project)
        if (!gitService.hasGitRepository()) {
            return false
        }

        val configService = AzureDevOpsConfigService.getInstance(project)
        return configService.isAzureDevOpsRepository()
    }

    /**
     * Builds the Azure DevOps repository URL using OkHttp's HttpUrl.Builder
     * Format: {baseUrl}/{project}/_git/{repo}
     */
    private fun buildRepositoryUrl(project: Project): String {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (config.organization.isBlank() || config.project.isBlank() || config.repository.isBlank()) {
            throw IllegalStateException("Azure DevOps repository information is not available")
        }

        val baseUrl = configService.getApiBaseUrl()

        return baseUrl.toHttpUrl().newBuilder()
            .addPathSegment(config.project)
            .addPathSegment("_git")
            .addPathSegment(config.repository)
            .build()
            .toString()
    }
}
