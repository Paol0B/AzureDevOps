package paol0b.azuredevops.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import paol0b.azuredevops.actions.CreatePullRequestAction
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.GitRepositoryService
import paol0b.azuredevops.services.PullRequestCommentsService
import javax.swing.JPanel

/**
 * Main panel of the Pull Request ToolWindow
 * Similar to the Commit/PR window in Visual Studio
 */
class PullRequestToolWindow(private val project: Project) {

    private val mainPanel: SimpleToolWindowPanel
    private val pullRequestListPanel: PullRequestListPanel
    private val pullRequestDetailsPanel: PullRequestDetailsPanel
    private val pollingService = paol0b.azuredevops.services.PullRequestsPollingService.getInstance(project)
    private var isInitialLoadDone: Boolean = false

    init {
        pullRequestListPanel = PullRequestListPanel(project) { selectedPR ->
            onPullRequestSelected(selectedPR)
        }
        
        pullRequestDetailsPanel = PullRequestDetailsPanel(project)

        // Vertical splitter with better proportions and resize support
        val splitter = JBSplitter(true, 0.4f).apply {
            firstComponent = pullRequestListPanel.getComponent()
            secondComponent = pullRequestDetailsPanel.getComponent()
            setResizeEnabled(true)
            setShowDividerControls(true)
            setShowDividerIcon(true)
            setHonorComponentsMinimumSize(true)
        }

        // Panel with toolbar and improved layout
        mainPanel = SimpleToolWindowPanel(true, true).apply {
            toolbar = createToolbar()
            setContent(splitter)
        }

        // Don't load PRs at startup - wait for tab to be visible
        
        // Start polling to automatically update the PR list
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
            // Action to show comments for the current branch
            add(object : AnAction("Show PR Comments", "Show comments for current branch's Pull Request", AllIcons.General.InlineVariables) {
                override fun actionPerformed(e: AnActionEvent) {
                    showCurrentBranchPRComments()
                }

                override fun update(e: AnActionEvent) {
                    // The action is always visible
                    e.presentation.isEnabledAndVisible = true
                }
            })

            addSeparator()

            // Action for creating a new PR
            add(object : AnAction("New Pull Request", "Create a new Pull Request", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    // Use ActionManager to properly trigger the action
                    val createPRAction = CreatePullRequestAction()
                    ActionManager.getInstance().tryToExecute(
                        createPRAction,
                        e.inputEvent,
                        null,
                        e.place,
                        true
                    )
                    // Refresh after the creation
                    pullRequestListPanel.refreshPullRequests()
                }
            })

            addSeparator()

            // Action for refresh
            add(object : AnAction("Refresh", "Refresh Pull Requests", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    pullRequestListPanel.refreshPullRequests()
                }
            })

            // Action to filter by status
            add(object : ToggleAction("Show Only Active", "Show only active Pull Requests", AllIcons.General.Filter) {
                private var showOnlyActive = true

                override fun isSelected(e: AnActionEvent): Boolean = showOnlyActive

                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    showOnlyActive = state
                    pullRequestListPanel.setFilterStatus(if (state) "active" else "all")
                }
            })

            addSeparator()

            // Action to open selected PR in browser
            add(object : AnAction("Open in Browser", "Open selected PR in browser", AllIcons.Ide.External_link_arrow) {
                override fun actionPerformed(e: AnActionEvent) {
                    pullRequestListPanel.getSelectedPullRequest()?.let { pr ->
                        openInBrowser(pr.getWebUrl())
                    }
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = pullRequestListPanel.getSelectedPullRequest() != null
                }
            })
        }

        val toolbar = ActionManager.getInstance().createActionToolbar("AzureDevOpsPRToolbar", actionGroup, true)
        toolbar.targetComponent = mainPanel
        return toolbar.component as JPanel
    }

    private fun onPullRequestSelected(pullRequest: PullRequest?) {
        pullRequestDetailsPanel.setPullRequest(pullRequest)
    }

    /**
     * Shows the comments of the PR associated with the current branch
     */
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
                        // Load comments in the open editor
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

    private fun openInBrowser(url: String) {
        try {
            if (java.awt.Desktop.isDesktopSupported()) {
                java.awt.Desktop.getDesktop().browse(java.net.URI(url))
            }
        } catch (e: Exception) {
            // Log error
        }
    }

    /**
     * Called when the tool window is being disposed or the plugin is unloaded to stop polling.
     */
    fun dispose() {
        try {
            pollingService.stopPolling()
        } catch (e: Exception) {
            // ignore
        }
    }
}
