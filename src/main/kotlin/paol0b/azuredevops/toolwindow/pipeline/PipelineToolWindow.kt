package paol0b.azuredevops.toolwindow.pipeline

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.BorderLayout
import javax.swing.*

/**
 * Main container panel for the Pipeline tool window.
 * Uses [PipelineFilterPanel] (with [FilterChipComponent]) for filtering,
 * matching the PR tool window's UX.
 */
class PipelineToolWindow(private val project: Project) {

    private val logger = Logger.getInstance(PipelineToolWindow::class.java)
    private val mainPanel: SimpleToolWindowPanel
    val pipelineListPanel: PipelineListPanel
    private val filterPanel: PipelineFilterPanel
    private var isInitialLoadDone = false
    private var isDefinitionsLoading = false
    private var isDefinitionsLoaded = false

    // Cached definitions
    private var cachedDefinitions: List<paol0b.azuredevops.model.BuildDefinition> = emptyList()

    init {
        pipelineListPanel = PipelineListPanel(project)

        filterPanel = PipelineFilterPanel(project) { searchValue ->
            pipelineListPanel.applyFilter(searchValue)
        }

        // Content: filter panel on top, list below
        val contentPanel = JPanel(BorderLayout()).apply {
            add(filterPanel.getComponent(), BorderLayout.NORTH)
            add(pipelineListPanel.getComponent(), BorderLayout.CENTER)
        }

        mainPanel = SimpleToolWindowPanel(true, true).apply {
            toolbar = createToolbar()
            setContent(contentPanel)
        }

        // Load definitions in background
        loadDefinitions()
    }

    fun loadPipelinesIfNeeded() {
        if (!isDefinitionsLoaded) {
            loadDefinitions()
        }
        if (!isInitialLoadDone) {
            isInitialLoadDone = true
            pipelineListPanel.refreshBuilds()
        }
        pipelineListPanel.startAutoRefresh()
    }

    fun getContent(): JPanel = mainPanel

    private fun createToolbar(): JPanel {
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("Run Pipeline", "Queue a new pipeline run", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    val dialog = RunPipelineDialog(project, cachedDefinitions)
                    if (dialog.showAndGet()) {
                        dialog.getSelectedDefinitionId()?.let { defId ->
                            val branch = dialog.getSelectedBranch()
                            ApplicationManager.getApplication().executeOnPooledThread {
                                try {
                                    val apiClient = AzureDevOpsApiClient.getInstance(project)
                                    apiClient.queueBuild(defId, branch)
                                    ApplicationManager.getApplication().invokeLater {
                                        pipelineListPanel.refreshBuilds()
                                    }
                                } catch (ex: Exception) {
                                    logger.error("Failed to queue build", ex)
                                    ApplicationManager.getApplication().invokeLater {
                                        JOptionPane.showMessageDialog(
                                            mainPanel,
                                            "Failed to run pipeline: ${ex.message}",
                                            "Error",
                                            JOptionPane.ERROR_MESSAGE
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            })

            addSeparator()

            add(object : AnAction("Refresh", "Refresh pipeline list", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    refreshDefinitionsAndBuilds()
                }
            })

            addSeparator()

            add(object : AnAction("Open in Browser", "Open selected pipeline in browser", AllIcons.Ide.External_link_arrow) {
                override fun actionPerformed(e: AnActionEvent) {
                    pipelineListPanel.getSelectedBuild()?.getWebUrl()?.let { url ->
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
                    e.presentation.isEnabled = pipelineListPanel.getSelectedBuild() != null
                }
            })
        }

        val toolbar = ActionManager.getInstance().createActionToolbar("AzureDevOpsPipelineToolbar", actionGroup, true)
        toolbar.targetComponent = mainPanel

        return JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
        }
    }

    private fun loadDefinitions() {
        if (isDefinitionsLoading || isDefinitionsLoaded) return

        isDefinitionsLoading = true
        logger.info("Starting to load build definitions...")

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val definitions = apiClient.getBuildDefinitions()

                cachedDefinitions = definitions.sortedBy { it.name }

                ApplicationManager.getApplication().invokeLater {
                    filterPanel.updateDefinitions(cachedDefinitions)
                    isDefinitionsLoaded = true
                    isDefinitionsLoading = false
                    logger.info("Loaded ${definitions.size} build definitions")
                }
            } catch (e: Exception) {
                logger.error("Failed to load build definitions: ${e.message}", e)
                isDefinitionsLoading = false
            }
        }
    }

    fun refreshDefinitionsAndBuilds() {
        isDefinitionsLoaded = false
        isDefinitionsLoading = false
        loadDefinitions()
        pipelineListPanel.refreshBuilds()
    }

    fun dispose() {
        pipelineListPanel.stopAutoRefresh()
    }
}
