package paol0b.azuredevops.toolwindow.workitem.board

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.JsonPatchOperation
import paol0b.azuredevops.model.WorkItem
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.util.NotificationUtil
import java.awt.*
import javax.swing.*

/**
 * Full-featured Kanban board panel showing work items organized in columns by state.
 * Designed to be used as the content of an editor tab for maximum space.
 * Supports drag-and-drop between columns to change state, and auto-refresh.
 */
class BoardViewPanel(
    private val project: Project,
    private val iterationPath: String?,
    private val iterationName: String
) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(BoardViewPanel::class.java)
    private val columnsPanel: JPanel
    private val statusLabel: JLabel
    private val countLabel: JBLabel
    private val columnPanels = mutableMapOf<String, BoardColumnPanel>()
    private var cachedWorkItems: List<WorkItem> = emptyList()
    private var refreshTimer: Timer? = null

    // Standard column order
    private val COLUMN_ORDER = listOf("New", "Active", "Resolved", "Closed")

    init {
        background = UIUtil.getPanelBackground()

        // Header toolbar
        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(12, 16, 8, 16)

            val titleLabel = JBLabel().apply {
                text = if (iterationName.isNotBlank()) "Board — $iterationName" else "Work Item Board"
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 16f)
            }
            add(titleLabel, BorderLayout.WEST)

            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = false

                countLabel = JBLabel("0 items")
                countLabel.foreground = JBColor.GRAY
                add(countLabel)

                val refreshButton = JButton("Refresh", AllIcons.Actions.Refresh).apply {
                    addActionListener { refresh() }
                    isFocusPainted = false
                }
                add(refreshButton)
            }
            add(rightPanel, BorderLayout.EAST)
        }
        add(headerPanel, BorderLayout.NORTH)

        // Columns area
        columnsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            border = JBUI.Borders.empty(0, 12, 12, 12)
        }

        val columnsScroll = JBScrollPane(columnsPanel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
            verticalScrollBar.unitIncrement = 16
            horizontalScrollBar.unitIncrement = 16
        }
        add(columnsScroll, BorderLayout.CENTER)

        // Status bar
        statusLabel = JLabel("Click Refresh to load").apply {
            border = JBUI.Borders.empty(4, 16)
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.GRAY
        }
        add(statusLabel, BorderLayout.SOUTH)
    }

    fun refresh() {
        statusLabel.text = "Loading board..."
        statusLabel.icon = AllIcons.Process.Step_1

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val items = if (iterationPath != null) {
                    apiClient.getMyWorkItems(iterationPath = iterationPath)
                } else {
                    apiClient.getMyWorkItems()
                }
                cachedWorkItems = items

                ApplicationManager.getApplication().invokeLater {
                    rebuildColumns()
                    countLabel.text = "${items.size} items"
                    statusLabel.text = "Loaded ${items.size} work item(s) — drag cards to change state"
                    statusLabel.icon = AllIcons.General.InspectionsOK
                }
            } catch (e: Exception) {
                logger.warn("Failed to load board: ${e.message}")
                ApplicationManager.getApplication().invokeLater {
                    statusLabel.text = "Error: ${e.message?.take(80)}"
                    statusLabel.icon = AllIcons.General.Error
                }
            }
        }
    }

    private fun rebuildColumns() {
        columnsPanel.removeAll()
        columnPanels.clear()

        val grouped = cachedWorkItems.groupBy { it.getState() }

        // All states in order
        val allStates = COLUMN_ORDER.toMutableList()
        grouped.keys.forEach { state ->
            if (state !in allStates) allStates.add(state)
        }

        allStates.forEach { state ->
            val items = grouped[state] ?: emptyList()
            val column = BoardColumnPanel(project, state, items, getColumnColor(state)) { workItem, targetState ->
                onItemDropped(workItem, targetState)
            }
            columnPanels[state] = column
            columnsPanel.add(column)
            columnsPanel.add(Box.createHorizontalStrut(JBUI.scale(10)))
        }

        columnsPanel.revalidate()
        columnsPanel.repaint()
    }

    private fun getColumnColor(state: String): Color = WorkItem.stateColor(state)

    private fun onItemDropped(workItem: WorkItem, targetState: String) {
        if (workItem.getState() == targetState) return

        statusLabel.text = "Moving #${workItem.id} to $targetState..."
        statusLabel.icon = AllIcons.Process.Step_1

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val operations = listOf(
                    JsonPatchOperation("replace", "/fields/System.State", targetState)
                )
                apiClient.updateWorkItem(workItem.id, operations)

                ApplicationManager.getApplication().invokeLater {
                    NotificationUtil.info(project, "State Changed",
                        "#${workItem.id} → $targetState")
                    refresh()
                }
            } catch (e: Exception) {
                logger.error("Failed to move work item #${workItem.id} to $targetState", e)
                ApplicationManager.getApplication().invokeLater {
                    NotificationUtil.error(project, "Move Failed",
                        "Could not change state: ${e.message?.take(80)}")
                    refresh()
                }
            }
        }
    }

    fun startAutoRefresh() {
        if (refreshTimer != null) return
        refreshTimer = Timer(30_000) { refresh() }.apply {
            isRepeats = true
            start()
        }
    }

    fun stopAutoRefresh() {
        refreshTimer?.stop()
        refreshTimer = null
    }

    fun dispose() {
        stopAutoRefresh()
    }
}
