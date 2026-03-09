package paol0b.azuredevops.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import paol0b.azuredevops.actions.CreatePullRequestAction
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.AzureDevOpsConfigService
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * Main panel of the Pull Request ToolWindow.
 * Uses a flat JBList with GitHub-style cell rendering.
 * Details panel has been removed — double-click opens the review tab.
 */
class PullRequestToolWindow(private val project: Project) {

    private val mainPanel: SimpleToolWindowPanel
    private val pullRequestListPanel: PullRequestListPanel
    private val pollingService = paol0b.azuredevops.services.PullRequestsPollingService.getInstance(project)
    private var isInitialLoadDone: Boolean = false

    init {
        pullRequestListPanel = PullRequestListPanel(project) { _ -> }

        mainPanel = SimpleToolWindowPanel(true, true).apply {
            toolbar = createToolbar()
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

    private fun createToolbar(): JPanel {
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("New Pull Request", "Create a new Pull Request", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    val createPRAction = CreatePullRequestAction()
                    ActionManager.getInstance().tryToExecute(
                        createPRAction, e.inputEvent, null, e.place, true
                    )
                    pullRequestListPanel.refreshPullRequests()
                }
            })

            addSeparator()

            add(object : AnAction("Refresh", "Refresh Pull Requests", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    pullRequestListPanel.refreshPullRequests()
                }
            })

            addSeparator()

            add(object : AnAction("Open in Browser", "Open selected PR in browser", AllIcons.Ide.External_link_arrow) {
                override fun actionPerformed(e: AnActionEvent) {
                    pullRequestListPanel.getSelectedPullRequest()?.let { pr ->
                        getPullRequestWebUrl(pr)?.let { url -> openInBrowser(url) }
                    }
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = pullRequestListPanel.getSelectedPullRequest() != null
                }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
        }

        val toolbar = ActionManager.getInstance().createActionToolbar("AzureDevOpsPRToolbar", actionGroup, true)
        toolbar.targetComponent = mainPanel

        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
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
        } catch (_: Exception) { }
    }
}
