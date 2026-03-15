package paol0b.azuredevops.toolwindow.workitem

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.WorkItemTabService
import paol0b.azuredevops.ui.CreateWorkItemDialog
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.*

/**
 * Main container panel for the Work Items tool window.
 * Uses the same filter-panel + JBList approach as the PR tool window.
 * Board view opens as a full editor tab for maximum space.
 */
class WorkItemToolWindow(private val project: Project) {

    private val logger = Logger.getInstance(WorkItemToolWindow::class.java)
    private val mainPanel: SimpleToolWindowPanel
    val workItemListPanel: WorkItemListPanel
    private var sprintViewPanel: SprintViewPanel? = null
    private var isInitialLoadDone = false
    private var isIterationsLoaded = false

    // Content card layout for List/Sprint switching
    private val contentPanel = JPanel(CardLayout())
    private val VIEW_LIST = "list"
    private val VIEW_SPRINT = "sprint"

    init {
        workItemListPanel = WorkItemListPanel(project)
        contentPanel.add(workItemListPanel.getComponent(), VIEW_LIST)

        mainPanel = SimpleToolWindowPanel(true, true).apply {
            toolbar = createToolbar()
            setContent(contentPanel)
        }

        loadIterations()
    }

    fun loadWorkItemsIfNeeded() {
        if (!isIterationsLoaded) {
            loadIterations()
        }
        if (!isInitialLoadDone) {
            isInitialLoadDone = true
            workItemListPanel.refreshWorkItems()
        }
        workItemListPanel.startAutoRefresh()
    }

    fun getContent(): JPanel = mainPanel

    private fun createToolbar(): JPanel {
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("New Work Item", "Create a new work item", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    CreateWorkItemDialog.showDialog(project) { created ->
                        if (created != null) {
                            workItemListPanel.refreshWorkItems()
                        }
                    }
                }
            })

            addSeparator()

            add(object : AnAction("Refresh", "Refresh work items", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    refreshAll()
                }
            })

            addSeparator()

            // View modes
            add(object : AnAction("List View", "Show as flat list", AllIcons.Actions.GroupBy) {
                override fun actionPerformed(e: AnActionEvent) {
                    sprintViewPanel?.stopAutoRefresh()
                    val cardLayout = contentPanel.layout as CardLayout
                    cardLayout.show(contentPanel, VIEW_LIST)
                }
            })
            add(object : AnAction("Sprint View", "Show grouped by sprint state", AllIcons.Actions.GroupByPackage) {
                override fun actionPerformed(e: AnActionEvent) {
                    if (sprintViewPanel == null) {
                        sprintViewPanel = SprintViewPanel(project)
                        contentPanel.add(sprintViewPanel!!.getComponent(), VIEW_SPRINT)
                    }
                    sprintViewPanel?.refresh()
                    val cardLayout = contentPanel.layout as CardLayout
                    cardLayout.show(contentPanel, VIEW_SPRINT)
                }
            })
            add(object : AnAction("Open Board", "Open Kanban board as editor tab", AllIcons.Actions.MoveToWindow) {
                override fun actionPerformed(e: AnActionEvent) {
                    WorkItemTabService.getInstance(project).openBoardTab(null, "")
                }
            })

            addSeparator()

            add(object : AnAction("Open in Browser", "Open selected work item in browser", AllIcons.Ide.External_link_arrow) {
                override fun actionPerformed(e: AnActionEvent) {
                    workItemListPanel.getSelectedWorkItem()?.getWebUrl()?.let { url ->
                        if (url.isNotBlank()) {
                            try {
                                if (java.awt.Desktop.isDesktopSupported()) {
                                    java.awt.Desktop.getDesktop().browse(java.net.URI(url))
                                }
                            } catch (ex: Exception) {
                                logger.warn("Failed to open in browser: ${ex.message}")
                            }
                        }
                    }
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = workItemListPanel.getSelectedWorkItem() != null
                }
            })
        }

        val toolbar = ActionManager.getInstance().createActionToolbar("AzureDevOpsWorkItemToolbar", actionGroup, true)
        toolbar.targetComponent = mainPanel

        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
        }
    }

    private fun loadIterations() {
        if (isIterationsLoaded) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val iterations = apiClient.getIterations()

                ApplicationManager.getApplication().invokeLater {
                    workItemListPanel.updateIterations(iterations)
                    isIterationsLoaded = true
                }
            } catch (e: Exception) {
                logger.warn("Failed to load iterations: ${e.message}")
            }
        }
    }

    fun refreshAll() {
        isIterationsLoaded = false
        loadIterations()
        workItemListPanel.refreshWorkItems()
    }

    fun dispose() {
        workItemListPanel.stopAutoRefresh()
        sprintViewPanel?.stopAutoRefresh()
    }
}
