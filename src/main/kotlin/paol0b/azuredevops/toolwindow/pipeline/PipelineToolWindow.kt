package paol0b.azuredevops.toolwindow.pipeline

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.BuildDefinition
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/**
 * Main container panel for the Pipeline tool window.
 * Holds the toolbar (with filters and run button) and the [PipelineListPanel].
 * Mirrors the structure of [paol0b.azuredevops.toolwindow.PullRequestToolWindow].
 */
class PipelineToolWindow(private val project: Project) {

    private val logger = Logger.getInstance(PipelineToolWindow::class.java)
    private val mainPanel: SimpleToolWindowPanel
    val pipelineListPanel: PipelineListPanel
    private var isInitialLoadDone = false
    private var isDefinitionsLoading = false
    private var isDefinitionsLoaded = false

    // Filter controls
    private val resultComboBox = ComboBox(arrayOf(
        "All", "Succeeded", "Failed", "Canceled", "In Progress"
    )).apply {
        selectedItem = "All"
        maximumSize = Dimension(130, 30)
        toolTipText = "Filter by Result"
    }

    private val definitionComboBox = ComboBox(arrayOf("All Pipelines")).apply {
        selectedItem = "All Pipelines"
        maximumSize = Dimension(200, 30)
        preferredSize = Dimension(200, 30)
        // Set a prototype value to prevent width from changing when items are loaded
        setPrototypeDisplayValue("All Pipelines - Very Long Pipeline Name XXX")
        toolTipText = "Filter by Pipeline"
        
        // Custom renderer with tooltip support
        renderer = object : com.intellij.ui.SimpleListCellRenderer<Any>() {
            override fun customize(list: javax.swing.JList<out Any>, value: Any?, index: Int, selected: Boolean, hasFocus: Boolean) {
                text = value?.toString() ?: ""
                // Add tooltip with full text for long names
                if (value != null) {
                    icon = null
                    toolTipText = value.toString()
                }
            }
        }
        
        addPopupMenuListener(object : javax.swing.event.PopupMenuListener {
            override fun popupMenuWillBecomeVisible(e: javax.swing.event.PopupMenuEvent?) {
                // If combo is empty or only has "All Pipelines" when opened, try loading
                if (itemCount <= 1 && !isDefinitionsLoaded && !isDefinitionsLoading) {
                    logger.info("Combo opened with no items, triggering definitions load")
                    loadDefinitions()
                }
            }

            override fun popupMenuWillBecomeInvisible(e: javax.swing.event.PopupMenuEvent?) {}
            override fun popupMenuCanceled(e: javax.swing.event.PopupMenuEvent?) {}
        })
    }

    private val branchField = JBTextField().apply {
        emptyText.text = "Branch filter..."
        maximumSize = Dimension(140, 30)
        preferredSize = Dimension(90, 30)
        toolTipText = "Filter by branch name (press Enter to apply)"
    }

    private val userComboBox = ComboBox(arrayOf("All Users", "Me")).apply {
        selectedItem = "All Users"
        maximumSize = Dimension(130, 30)
        toolTipText = "Filter by User"
    }

    // Cached definitions for the combo
    private var cachedDefinitions: List<BuildDefinition> = emptyList()

    init {
        pipelineListPanel = PipelineListPanel(project)

        mainPanel = SimpleToolWindowPanel(true, true).apply {
            toolbar = createToolbar()
            setContent(pipelineListPanel.getComponent())
        }

        // Wire up filter listeners
        resultComboBox.addActionListener {
            val value = when (resultComboBox.selectedItem) {
                "Succeeded" -> "succeeded"
                "Failed" -> "failed"
                "Canceled" -> "canceled"
                "In Progress" -> null // use statusFilter instead
                else -> null
            }
            // Handle "In Progress" specially — it's a status, not a result
            if (resultComboBox.selectedItem == "In Progress") {
                pipelineListPanel.setResultFilter(null)
                pipelineListPanel.setStatusFilter("inProgress")
            } else {
                pipelineListPanel.setStatusFilter(null)
                pipelineListPanel.setResultFilter(value)
            }
        }

        definitionComboBox.addActionListener {
            val idx = definitionComboBox.selectedIndex
            if (idx <= 0) {
                pipelineListPanel.setDefinitionFilter(null)
            } else {
                val defIdx = idx - 1
                if (defIdx < cachedDefinitions.size) {
                    pipelineListPanel.setDefinitionFilter(cachedDefinitions[defIdx].id)
                }
            }
        }

        branchField.addActionListener {
            val branch = branchField.text.trim().ifEmpty { null }
            pipelineListPanel.setBranchFilter(branch)
        }

        userComboBox.addActionListener {
            val value = when (userComboBox.selectedItem) {
                "Me" -> "Me"
                else -> null
            }
            // "Me" maps to current user; handled differently in API
            pipelineListPanel.setUserFilter(if (value == "Me") "__ME__" else null)
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
    }

    fun getContent(): JPanel = mainPanel

    private fun createToolbar(): JPanel {
        val actionGroup = DefaultActionGroup().apply {
            // Run new pipeline
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

            // Refresh
            add(object : AnAction("Refresh", "Refresh pipeline list", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    refreshDefinitionsAndBuilds()
                }
            })

            addSeparator()

            // Open in browser
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

        val toolbarPanel = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)

            // Filter controls on the right
            val filterPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(Box.createHorizontalStrut(6))
                add(resultComboBox)
                add(Box.createHorizontalStrut(6))
                add(branchField)
                add(Box.createHorizontalStrut(4))
                add(definitionComboBox)
                add(Box.createHorizontalStrut(6))
            }
            add(filterPanel, BorderLayout.EAST)
        }

        return toolbarPanel
    }

    /**
     * Load pipeline definitions in background and populate the filter combo.
     */
    private fun loadDefinitions() {
        if (isDefinitionsLoading || isDefinitionsLoaded) return
        
        isDefinitionsLoading = true
        logger.info("Starting to load build definitions...")
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val definitions = apiClient.getBuildDefinitions()
                
                if (definitions.isEmpty()) {
                    logger.warn("No build definitions returned from API")
                } else {
                    logger.info("Loaded ${definitions.size} build definitions")
                }
                
                cachedDefinitions = definitions.sortedBy { it.name }

                ApplicationManager.getApplication().invokeLater {
                    updateDefinitionsCombo()
                    isDefinitionsLoaded = true
                    isDefinitionsLoading = false
                    logger.info("Build definitions combo updated successfully")
                }
            } catch (e: Exception) {
                logger.error("Failed to load build definitions: ${e.message}", e)
                isDefinitionsLoading = false
                // Don't mark as loaded on error, allow retry
            }
        }
    }

    /**
     * Update the definitions combo box with the cached definitions.
     */
    private fun updateDefinitionsCombo() {
        definitionComboBox.removeAllItems()
        definitionComboBox.addItem("All Pipelines")
        cachedDefinitions.forEach { def ->
            definitionComboBox.addItem(def.getDisplayName())
        }
    }

    /**
     * Refresh definitions and builds together.
     */
    fun refreshDefinitionsAndBuilds() {
        isDefinitionsLoaded = false
        isDefinitionsLoading = false
        loadDefinitions()
        pipelineListPanel.refreshBuilds()
    }

    fun dispose() {
        // cleanup if needed
    }
}
