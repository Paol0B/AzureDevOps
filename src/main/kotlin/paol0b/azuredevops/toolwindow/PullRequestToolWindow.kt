package paol0b.azuredevops.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBSplitter
import paol0b.azuredevops.actions.CreatePullRequestAction
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.AzureDevOpsConfigService
import paol0b.azuredevops.services.GitRepositoryService
import paol0b.azuredevops.services.PullRequestCommentsService
import paol0b.azuredevops.services.PullRequestsPollingService
import paol0b.azuredevops.toolwindow.list.NewPullRequestListPanel
import java.awt.BorderLayout
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComboBox
import javax.swing.JPanel

/**
 * Main panel of the Pull Request ToolWindow.
 * Supports two UI modes:
 * - Modern (GitHub-style): flat JBList with rich rendering, integrated search/filters
 * - Classic (legacy): tree-based list with details splitter
 *
 * The UI mode can be toggled via the toolbar settings button.
 */
class PullRequestToolWindow(private val project: Project) {

    companion object {
        private const val UI_MODE_KEY = "azuredevops.useGithubStyleUI"
    }

    private val mainPanel = SimpleToolWindowPanel(true, true)
    private val pollingService = PullRequestsPollingService.getInstance(project)
    private var isInitialLoadDone: Boolean = false

    // Modern GitHub-style UI
    private var newListPanel: NewPullRequestListPanel? = null

    // Classic legacy UI
    private var legacyListPanel: PullRequestListPanel? = null
    private var legacyDetailsPanel: PullRequestDetailsPanel? = null

    init {
        buildUI()
        pollingService.startPolling { refreshActivePanel() }
    }

    fun loadPullRequestsIfNeeded() {
        if (!isInitialLoadDone) {
            isInitialLoadDone = true
            refreshActivePanel()
        }
    }

    fun getContent(): JPanel = mainPanel

    fun dispose() {
        try { pollingService.stopPolling() } catch (_: Exception) {}
    }

    // ── UI Mode Management ──

    private fun isNewUIEnabled(): Boolean =
        PropertiesComponent.getInstance(project).getBoolean(UI_MODE_KEY, true)

    private fun setNewUIEnabled(enabled: Boolean) =
        PropertiesComponent.getInstance(project).setValue(UI_MODE_KEY, enabled, true)

    private fun buildUI() {
        mainPanel.removeAll()
        newListPanel = null
        legacyListPanel = null
        legacyDetailsPanel = null

        if (isNewUIEnabled()) buildNewUI() else buildLegacyUI()

        mainPanel.revalidate()
        mainPanel.repaint()
    }

    private fun buildNewUI() {
        val panel = NewPullRequestListPanel(project) { /* No details panel in modern view */ }
        newListPanel = panel
        mainPanel.toolbar = createNewUIToolbar()
        mainPanel.setContent(panel.getComponent())
    }

    private fun buildLegacyUI() {
        val details = PullRequestDetailsPanel(project)
        val list = PullRequestListPanel(project) { pr -> details.setPullRequest(pr) }
        legacyListPanel = list
        legacyDetailsPanel = details

        val splitter = JBSplitter(true, 0.4f).apply {
            firstComponent = list.getComponent()
            secondComponent = details.getComponent()
            setResizeEnabled(true)
            setShowDividerControls(true)
            setShowDividerIcon(true)
            setHonorComponentsMinimumSize(true)
        }

        mainPanel.toolbar = createLegacyToolbar(list)
        mainPanel.setContent(splitter)
    }

    private fun toggleUIMode() {
        setNewUIEnabled(!isNewUIEnabled())
        isInitialLoadDone = false
        buildUI()
        loadPullRequestsIfNeeded()
    }

    private fun refreshActivePanel() {
        newListPanel?.refreshPullRequests() ?: legacyListPanel?.refreshPullRequests()
    }

    private fun getSelectedPR(): PullRequest? =
        newListPanel?.getSelectedPullRequest() ?: legacyListPanel?.getSelectedPullRequest()

    // ── Modern UI Toolbar ──

    private fun createNewUIToolbar(): JPanel {
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("Switch to Classic View", "Switch to classic tree view", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) = toggleUIMode()
            })
            addSeparator()
            add(object : AnAction("New Pull Request", "Create a new Pull Request", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    ActionManager.getInstance().tryToExecute(
                        CreatePullRequestAction(), e.inputEvent, null, e.place, true
                    )
                    refreshActivePanel()
                }
            })
            addSeparator()
            add(object : AnAction("Open in Browser", "Open selected PR in browser", AllIcons.Ide.External_link_arrow) {
                override fun actionPerformed(e: AnActionEvent) {
                    getSelectedPR()?.let { pr ->
                        getPullRequestWebUrl(pr)?.let { url -> openInBrowser(url) }
                    }
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = getSelectedPR() != null
                }
            })
        }

        val toolbar = ActionManager.getInstance().createActionToolbar("AzureDevOpsPRToolbar", actionGroup, true)
        toolbar.targetComponent = mainPanel
        return JPanel(BorderLayout()).apply { add(toolbar.component, BorderLayout.WEST) }
    }

    // ── Classic Legacy Toolbar ──

    private fun createLegacyToolbar(listPanel: PullRequestListPanel): JPanel {
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("Switch to Modern View", "Switch to modern GitHub-style view", AllIcons.General.Settings) {
                override fun actionPerformed(e: AnActionEvent) = toggleUIMode()
            })
            addSeparator()
            add(object : AnAction("New Pull Request", "Create a new Pull Request", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    ActionManager.getInstance().tryToExecute(
                        CreatePullRequestAction(), e.inputEvent, null, e.place, true
                    )
                    listPanel.refreshPullRequests()
                }
            })
            addSeparator()
            add(object : AnAction("Refresh", "Refresh Pull Requests", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    listPanel.refreshPullRequests()
                }
            })
            addSeparator()
            add(object : AnAction("Open in Browser", "Open selected PR in browser", AllIcons.Ide.External_link_arrow) {
                override fun actionPerformed(e: AnActionEvent) {
                    listPanel.getSelectedPullRequest()?.let { pr ->
                        getPullRequestWebUrl(pr)?.let { url -> openInBrowser(url) }
                    }
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = listPanel.getSelectedPullRequest() != null
                }
            })
        }

        val toolbar = ActionManager.getInstance().createActionToolbar("AzureDevOpsPRToolbar", actionGroup, true)
        toolbar.targetComponent = mainPanel

        // Filter controls for legacy mode
        val orgButton = JButton("📦 Org").apply {
            toolTipText = "Show Pull Requests from all organization repositories"
        }
        val filterCombo = JComboBox(arrayOf("Active", "Completed", "Abandoned", "All")).apply {
            selectedItem = "Active"
            maximumSize = java.awt.Dimension(120, 30)
            toolTipText = "Filter by PR Status"
        }

        filterCombo.addActionListener {
            val status = when (filterCombo.selectedItem) {
                "Active" -> "active"
                "Completed" -> "completed"
                "Abandoned" -> "abandoned"
                "All" -> "all"
                else -> "active"
            }
            listPanel.setFilterStatus(status)
            if (filterCombo.selectedItem == "Active" && !listPanel.getShowAllOrganizationPrs()) {
                listPanel.setShowAllOrganizationPrs(true)
                updateOrgButton(orgButton, true)
            }
            if (filterCombo.selectedItem != "Active" && listPanel.getShowAllOrganizationPrs()) {
                listPanel.setShowAllOrganizationPrs(false)
                updateOrgButton(orgButton, false)
            }
        }

        orgButton.addActionListener {
            val newState = !listPanel.getShowAllOrganizationPrs()
            listPanel.setShowAllOrganizationPrs(newState)
            updateOrgButton(orgButton, newState)
        }

        val toolbarPanel = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            val filterPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(Box.createHorizontalStrut(10))
                add(filterCombo)
                add(Box.createHorizontalStrut(10))
                add(orgButton)
                add(Box.createHorizontalStrut(10))
            }
            add(filterPanel, BorderLayout.EAST)
        }
        return toolbarPanel
    }

    private fun updateOrgButton(button: JButton, enabled: Boolean) {
        button.text = if (enabled) "✓ 📦 Org" else "📦 Org"
        button.toolTipText = if (enabled) "✓ Showing PRs from all organization repositories"
            else "Show Pull Requests from all organization repositories"
    }

    // ── Helper Methods ──

    private fun showCurrentBranchPRComments() {
        val gitService = GitRepositoryService.getInstance(project)
        val currentBranch = gitService.getCurrentBranch()

        if (currentBranch == null) {
            Messages.showWarningDialog(
                project,
                "No active Git branch.",
                "Unable to Show Comments"
            )
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val pullRequest = apiClient.findPullRequestForBranch(currentBranch.displayName)

                ApplicationManager.getApplication().invokeLater {
                    if (pullRequest == null) {
                        Messages.showMessageDialog(
                            project,
                            "The branch '${currentBranch.displayName}' does not have an active Pull Request.\n\n" +
                                    "Create a Pull Request for this branch to view comments.",
                            "No Pull Request",
                            Messages.getInformationIcon()
                        )
                    } else {
                        val fileEditorManager = FileEditorManager.getInstance(project)
                        val selectedEditor = fileEditorManager.selectedTextEditor
                        val selectedFile = fileEditorManager.selectedFiles.firstOrNull()

                        if (selectedEditor != null && selectedFile != null) {
                            val commentsService = PullRequestCommentsService.getInstance(project)
                            commentsService.loadCommentsInEditor(selectedEditor, selectedFile, pullRequest)

                            Messages.showMessageDialog(
                                project,
                                "Comments loaded for PR #${pullRequest.pullRequestId}:\n${pullRequest.title}\n\n" +
                                        "Comments are highlighted in the code.\n" +
                                        "Click the icon in the gutter to view and reply.",
                                "Comments Loaded",
                                Messages.getInformationIcon()
                            )
                        } else {
                            Messages.showMessageDialog(
                                project,
                                "PR found: #${pullRequest.pullRequestId} - ${pullRequest.title}\n\n" +
                                        "Open a file to view comments.",
                                "Pull Request Found",
                                Messages.getInformationIcon()
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Error while searching for the Pull Request:\n${e.message}",
                        "Error"
                    )
                }
            }
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
        } catch (_: Exception) {}
    }
}
