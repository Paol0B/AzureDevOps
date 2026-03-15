package paol0b.azuredevops.toolwindow.workitem

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.TeamIteration
import paol0b.azuredevops.model.WorkItem
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.*
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Sprint view showing work items grouped by state within the current iteration.
 * Header shows sprint name, date range, and progress bar.
 */
class SprintViewPanel(private val project: Project) {

    private val logger = Logger.getInstance(SprintViewPanel::class.java)
    private val panel: JPanel
    private val tree: Tree
    private val treeModel: DefaultTreeModel
    private val rootNode: DefaultMutableTreeNode
    private val statusLabel: JLabel
    private val sprintHeaderLabel: JBLabel
    private val progressBar: JProgressBar

    private var currentIteration: TeamIteration? = null
    private var cachedWorkItems: List<WorkItem> = emptyList()
    private var refreshTimer: Timer? = null

    init {
        rootNode = DefaultMutableTreeNode("Sprint")
        treeModel = DefaultTreeModel(rootNode)
        tree = Tree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = SprintCellRenderer()
            border = JBUI.Borders.empty(6, 12)
            rowHeight = 0
        }

        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)

        // Double-click opens detail tab
        tree.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val workItem = node.userObject as? WorkItem ?: return
                    WorkItemToolWindowFactory.openWorkItemDetailTab(project, workItem)
                }
            }
        })

        sprintHeaderLabel = JBLabel("Loading sprint...").apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 14f)
            border = JBUI.Borders.empty(8, 12, 4, 12)
        }

        progressBar = JProgressBar(0, 100).apply {
            isStringPainted = true
            string = "0%"
            preferredSize = Dimension(0, JBUI.scale(18))
            border = JBUI.Borders.empty(0, 12, 8, 12)
        }

        statusLabel = JLabel("Ready").apply {
            border = JBUI.Borders.empty(8, 12)
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.GRAY
        }

        panel = JPanel(BorderLayout()).apply {
            val headerPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(sprintHeaderLabel)
                add(progressBar)
            }
            add(headerPanel, BorderLayout.NORTH)

            val scrollPane = JBScrollPane(tree).apply {
                border = JBUI.Borders.empty()
                verticalScrollBar.unitIncrement = 16
            }
            add(scrollPane, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
    }

    fun getComponent(): JPanel = panel

    fun refresh() {
        statusLabel.text = "Loading sprint..."
        statusLabel.icon = AllIcons.Process.Step_1

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)

                // Get current iteration
                val iteration = apiClient.getCurrentIteration()
                currentIteration = iteration

                if (iteration == null) {
                    ApplicationManager.getApplication().invokeLater {
                        sprintHeaderLabel.text = "No current sprint found"
                        progressBar.value = 0
                        progressBar.string = "N/A"
                        rootNode.removeAllChildren()
                        rootNode.add(DefaultMutableTreeNode("No current iteration configured for this team"))
                        treeModel.reload()
                        statusLabel.text = "No sprint"
                        statusLabel.icon = AllIcons.General.Warning
                    }
                    return@executeOnPooledThread
                }

                // Get work items for this iteration
                val wiql = """
                    SELECT [System.Id]
                    FROM WorkItems
                    WHERE [System.TeamProject] = @project
                    AND [System.IterationPath] UNDER '${iteration.path}'
                    ORDER BY [System.State] ASC, [System.ChangedDate] DESC
                """.trimIndent()

                val workItems = apiClient.getWorkItemsByWiql(wiql)
                cachedWorkItems = workItems

                ApplicationManager.getApplication().invokeLater {
                    updateSprintHeader(iteration, workItems)
                    updateTreeGroupedByState(workItems)
                    statusLabel.text = "Loaded ${workItems.size} work item(s)"
                    statusLabel.icon = AllIcons.General.InspectionsOK
                }
            } catch (e: Exception) {
                logger.warn("Failed to load sprint: ${e.message}")
                ApplicationManager.getApplication().invokeLater {
                    statusLabel.text = "Error: ${e.message?.take(60)}"
                    statusLabel.icon = AllIcons.General.Error
                }
            }
        }
    }

    private fun updateSprintHeader(iteration: TeamIteration, workItems: List<WorkItem>) {
        val dateRange = iteration.getDateRange()
        val suffix = if (dateRange.isNotBlank()) "  ($dateRange)" else ""
        sprintHeaderLabel.text = "${iteration.name}$suffix"

        val total = workItems.size
        val completed = workItems.count {
            val state = it.getState().lowercase()
            state == "closed" || state == "done" || state == "resolved"
        }
        val pct = if (total > 0) (completed * 100 / total) else 0
        progressBar.value = pct
        progressBar.string = "$completed / $total ($pct%)"
    }

    private fun updateTreeGroupedByState(workItems: List<WorkItem>) {
        rootNode.removeAllChildren()

        if (workItems.isEmpty()) {
            rootNode.add(DefaultMutableTreeNode("No work items in this sprint"))
            treeModel.reload()
            return
        }

        // Group by state
        val grouped = workItems.groupBy { it.getState() }
        val stateOrder = listOf("New", "Active", "Resolved", "Closed")

        // Add states in order, then any remaining
        val orderedStates = stateOrder.filter { grouped.containsKey(it) } +
                grouped.keys.filter { it !in stateOrder }

        orderedStates.forEach { state ->
            val items = grouped[state] ?: return@forEach
            val groupNode = DefaultMutableTreeNode("$state (${items.size})")
            items.forEach { item ->
                groupNode.add(DefaultMutableTreeNode(item))
            }
            rootNode.add(groupNode)
        }

        treeModel.reload()

        // Expand all groups
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
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

    // Cell renderer for sprint view
    private inner class SprintCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean,
            expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            val userObject = node.userObject

            when (userObject) {
                is WorkItem -> {
                    icon = getTypeIcon(userObject.getWorkItemType())
                    append(userObject.getTitle(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("#${userObject.id}", SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN, userObject.getTypeColor()
                    ))
                    userObject.getAssignedTo()?.let { name ->
                        append("  ·  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                        append(name, SimpleTextAttributes(SimpleTextAttributes.STYLE_ITALIC, JBColor.GRAY))
                    }
                }
                is String -> {
                    if (node.parent == rootNode) {
                        // Group node
                        icon = AllIcons.Nodes.Folder
                        append(userObject, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    } else {
                        icon = AllIcons.General.Information
                        append(userObject, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                    }
                }
            }
        }

        private fun getTypeIcon(type: String): Icon = WorkItem.typeIcon(type)
    }
}
