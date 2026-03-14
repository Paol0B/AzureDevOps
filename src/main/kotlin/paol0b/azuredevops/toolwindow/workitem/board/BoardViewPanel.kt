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
import paol0b.azuredevops.model.WorkItemTypeState
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

    /** Column definitions read from Azure DevOps (work item type states). Empty until first refresh. */
    private var boardColumns: List<WorkItemTypeState> = emptyList()
    private var isBoardColumnsLoaded = false

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

                // Fetch board column definitions from Azure DevOps (work item type states)
                if (!isBoardColumnsLoaded) {
                    try {
                        val types = apiClient.getWorkItemTypes()
                        // Collect all unique states across all work item types, preserving order
                        val seen = linkedSetOf<String>()
                        val stateList = mutableListOf<WorkItemTypeState>()
                        for (type in types) {
                            for (state in type.states.orEmpty()) {
                                val name = state.name ?: continue
                                if (seen.add(name)) {
                                    stateList.add(state)
                                }
                            }
                        }
                        boardColumns = stateList
                        isBoardColumnsLoaded = true
                    } catch (e: Exception) {
                        isBoardColumnsLoaded = true // don't retry on every refresh
                        logger.warn("Failed to load work item types for board columns, will use states from items", e)
                    }
                }

                // Fetch ALL work items (not just assigned to me) so unassigned items are visible
                val items = if (iterationPath != null) {
                    apiClient.getAllWorkItems(iterationPath = iterationPath)
                } else {
                    apiClient.getAllWorkItems()
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

        // Build ordered column list from Azure DevOps states
        val orderedStates = mutableListOf<String>()
        val stateColorMap = mutableMapOf<String, Color>()

        for (col in boardColumns) {
            val name = col.name ?: continue
            if (name !in orderedStates) {
                orderedStates.add(name)
                // Parse hex color from Azure DevOps (e.g. "b2b2b2")
                col.color?.let { hex ->
                    try {
                        stateColorMap[name] = Color(Integer.parseInt(hex.removePrefix("#"), 16))
                    } catch (_: NumberFormatException) { }
                }
            }
        }

        // Add any states present in items but not in the type definitions
        for (state in grouped.keys) {
            if (state !in orderedStates) orderedStates.add(state)
        }

        // If we got no columns at all (API failed), fall back to states from the items themselves
        if (orderedStates.isEmpty()) {
            orderedStates.addAll(grouped.keys.sorted())
        }

        for (state in orderedStates) {
            val items = grouped[state] ?: emptyList()
            val color = stateColorMap[state] ?: WorkItem.stateColor(state)
            val column = BoardColumnPanel(project, state, items, color) { workItem, targetState ->
                onItemDropped(workItem, targetState)
            }
            columnPanels[state] = column
            columnsPanel.add(column)
            columnsPanel.add(Box.createHorizontalStrut(JBUI.scale(10)))
        }

        columnsPanel.revalidate()
        columnsPanel.repaint()
    }

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
