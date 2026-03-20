package paol0b.azuredevops.toolwindow

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.AzureDevOpsConfigService
import javax.swing.JPanel

/**
 * Main panel of the Pull Request ToolWindow.
 * Uses a flat JBList with GitHub-style cell rendering.
 * Actions are placed in the native tool window title bar by the factory.
 */
class PullRequestToolWindow(private val project: Project) {

    private val mainPanel: SimpleToolWindowPanel
    private val pullRequestListPanel: PullRequestListPanel
    private val pollingService = paol0b.azuredevops.services.PullRequestsPollingService.getInstance(project)
    private var isInitialLoadDone: Boolean = false

    init {
        pullRequestListPanel = PullRequestListPanel(project) { _ -> }

        mainPanel = SimpleToolWindowPanel(true, true).apply {
            setContent(pullRequestListPanel.getComponent())
        }

        pollingService.startPolling {
            pullRequestListPanel.refreshPullRequests()
        }
    }

    fun loadPullRequestsIfNeeded() {
        if (!isInitialLoadDone) {
            isInitialLoadDone = true
            pullRequestListPanel.refreshPullRequests()
        }
    }

    fun getContent(): JPanel = mainPanel

    fun refreshPullRequests() {
        pullRequestListPanel.refreshPullRequests()
    }

    fun getSelectedPullRequest(): PullRequest? {
        return pullRequestListPanel.getSelectedPullRequest()
    }

    fun openSelectedPrInBrowser() {
        getSelectedPullRequest()?.let { pr ->
            getPullRequestWebUrl(pr)?.let { url -> openInBrowser(url) }
        }
    }

    private fun getPullRequestWebUrl(pr: PullRequest): String? {
        val apiClient = AzureDevOpsApiClient.getInstance(project)

        pr.repository?.let { repo ->
            if (repo.name != null && repo.project?.name != null) {
                return apiClient.buildPullRequestWebUrl(repo.project.name, repo.name, pr.pullRequestId)
            }
        }

        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()
        if (!config.isValid()) return null
        return apiClient.buildPullRequestWebUrl(config.project, config.repository, pr.pullRequestId)
    }

    private fun openInBrowser(url: String) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            }
        } catch (_: Exception) { }
    }

    fun dispose() {
        try {
            pollingService.stopPolling()
        } catch (e: Exception) {
            com.intellij.openapi.diagnostic.Logger.getInstance(PullRequestToolWindow::class.java)
                .warn("Error stopping polling during dispose", e)
        }
    }
}
