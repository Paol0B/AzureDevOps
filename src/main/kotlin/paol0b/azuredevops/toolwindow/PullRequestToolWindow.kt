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
 * Pannello principale del ToolWindow delle Pull Request
 * Simile alla finestra Commit/PR di Visual Studio
 */
class PullRequestToolWindow(private val project: Project) {

    private val mainPanel: SimpleToolWindowPanel
    private val pullRequestListPanel: PullRequestListPanel
    private val pullRequestDetailsPanel: PullRequestDetailsPanel

    init {
        pullRequestListPanel = PullRequestListPanel(project) { selectedPR ->
            onPullRequestSelected(selectedPR)
        }
        
        pullRequestDetailsPanel = PullRequestDetailsPanel(project)

        // Splitter verticale: lista sopra, dettagli sotto (come Visual Studio)
        val splitter = JBSplitter(true, 0.5f).apply {
            firstComponent = JBScrollPane(pullRequestListPanel.getComponent())
            secondComponent = JBScrollPane(pullRequestDetailsPanel.getComponent())
        }

        // Pannello con toolbar
        mainPanel = SimpleToolWindowPanel(true, true).apply {
            toolbar = createToolbar()
            setContent(splitter)
        }

        // Carica le PR all'avvio
        pullRequestListPanel.refreshPullRequests()
    }

    fun getContent(): JPanel = mainPanel

    private fun createToolbar(): JPanel {
        val actionGroup = DefaultActionGroup().apply {
            // Action per mostrare i commenti del branch corrente
            add(object : AnAction("Show PR Comments", "Show comments for current branch's Pull Request", AllIcons.General.InlineVariables) {
                override fun actionPerformed(e: AnActionEvent) {
                    showCurrentBranchPRComments()
                }

                override fun update(e: AnActionEvent) {
                    // L'azione è sempre visibile
                    e.presentation.isEnabledAndVisible = true
                }
            })

            addSeparator()

            // Action per creare una nuova PR
            add(object : AnAction("New Pull Request", "Create a new Pull Request", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    CreatePullRequestAction().actionPerformed(e)
                    // Refresh dopo la creazione
                    pullRequestListPanel.refreshPullRequests()
                }
            })

            addSeparator()

            // Action per refresh
            add(object : AnAction("Refresh", "Refresh Pull Requests", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    pullRequestListPanel.refreshPullRequests()
                }
            })

            // Action per filtrare per stato
            add(object : ToggleAction("Show Only Active", "Show only active Pull Requests", AllIcons.General.Filter) {
                private var showOnlyActive = true

                override fun isSelected(e: AnActionEvent): Boolean = showOnlyActive

                override fun setSelected(e: AnActionEvent, state: Boolean) {
                    showOnlyActive = state
                    pullRequestListPanel.setFilterStatus(if (state) "active" else "all")
                }
            })

            addSeparator()

            // Action per aprire PR selezionata nel browser
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
     * Mostra i commenti della PR associata al branch corrente
     */
    private fun showCurrentBranchPRComments() {
        val gitService = GitRepositoryService.getInstance(project)
        val currentBranch = gitService.getCurrentBranch()

        if (currentBranch == null) {
            Messages.showWarningDialog(
                project,
                "Nessun branch Git attivo.",
                "Impossibile Visualizzare Commenti"
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
                            "Il branch '${currentBranch.displayName}' non ha una Pull Request attiva.\n\n" +
                                    "Crea una Pull Request per questo branch per visualizzare i commenti.",
                            "Nessuna Pull Request",
                            Messages.getInformationIcon()
                        )
                    } else {
                        // Carica i commenti nell'editor aperto
                        val fileEditorManager = FileEditorManager.getInstance(project)
                        val selectedEditor = fileEditorManager.selectedTextEditor
                        val selectedFile = fileEditorManager.selectedFiles.firstOrNull()

                        if (selectedEditor != null && selectedFile != null) {
                            val commentsService = PullRequestCommentsService.getInstance(project)
                            commentsService.loadCommentsInEditor(selectedEditor, selectedFile, pullRequest)

                            Messages.showMessageDialog(
                                project,
                                "Commenti caricati per PR #${pullRequest.pullRequestId}:\n${pullRequest.title}\n\n" +
                                        "I commenti sono evidenziati nel codice.\n" +
                                        "Clicca sull'icona nella gutter per visualizzare e rispondere.",
                                "Commenti Caricati",
                                Messages.getInformationIcon()
                            )
                        } else {
                            Messages.showMessageDialog(
                                project,
                                "PR trovata: #${pullRequest.pullRequestId} - ${pullRequest.title}\n\n" +
                                        "Apri un file per visualizzare i commenti.",
                                "Pull Request Trovata",
                                Messages.getInformationIcon()
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Errore durante la ricerca della Pull Request:\n${e.message}",
                        "Errore"
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
}
