package paol0b.azuredevops.toolwindow.pipeline

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.*
import paol0b.azuredevops.services.AvatarService
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.PipelineTabService
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel

/**
 * Detail tab panel for a single pipeline build.
 * Opened as a closable tab when the user double-clicks on a build in [PipelineListPanel].
 *
 * Layout:
 *   - Header: pipeline name, build number, repo/branch info
 *   - Info section: dates, duration, requester (with avatar), result badge
 *   - Actions: "Show Diagram" and "Re-run" buttons
 *   - Stage/Job/Task tree with durations and result icons
 *
 * Clicking a Task node opens its log in a virtual file editor tab.
 */
class PipelineDetailTabPanel(
    private val project: Project,
    private val build: PipelineBuild
) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(PipelineDetailTabPanel::class.java)
    private val apiClient = AzureDevOpsApiClient.getInstance(project)
    private val avatarService = AvatarService.getInstance(project)
    private val pipelineTabService = PipelineTabService.getInstance(project)

    private val treeRootNode = DefaultMutableTreeNode("Timeline")
    private val treeModel = DefaultTreeModel(treeRootNode)
    private val timelineTree = Tree(treeModel)

    private var timeline: BuildTimeline? = null
    private var refreshTimer: Timer? = null
    private val REFRESH_INTERVAL = 15_000

    init {
        background = UIUtil.getPanelBackground()
        setupUI()
        loadTimeline()

        // Auto-refresh if the build is still running
        if (build.isRunning()) {
            startAutoRefresh()
        }
    }

    // ========================
    //  UI Setup
    // ========================

    private fun setupUI() {
        val scrollContent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(0)
        }

        // Header
        scrollContent.add(createHeaderPanel())
        scrollContent.add(createSeparator())

        // Info section
        scrollContent.add(createInfoPanel())
        scrollContent.add(createSeparator())

        // Actions
        scrollContent.add(createActionsPanel())
        scrollContent.add(createSeparator())

        // Timeline tree
        scrollContent.add(createTimelineTreeSection())

        val scrollPane = JBScrollPane(scrollContent).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 16
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(scrollPane, BorderLayout.CENTER)
    }

    // ========================
    //  Header
    // ========================

    private fun createHeaderPanel(): JPanel {
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(12, 14, 8, 14)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // Pipeline name + build number
        val titleText = "${escapeHtml(build.getDefinitionName())} #${escapeHtml(build.buildNumber ?: "")}"
        val titleLabel = JBLabel("<html><b style='font-size:13px;'>$titleText</b></html>").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(6)
        }
        header.add(titleLabel)

        // Branch + repo info
        val branchPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            background = UIUtil.getPanelBackground()
            alignmentX = Component.LEFT_ALIGNMENT
        }

        val repoName = build.repository?.name
        if (!repoName.isNullOrBlank()) {
            branchPanel.add(JBLabel(repoName).apply {
                icon = AllIcons.Vcs.Branch
                foreground = JBColor(Color(70, 130, 180), Color(100, 149, 237))
                font = font.deriveFont(Font.BOLD, 12f)
            })
            branchPanel.add(JBLabel(" / ").apply {
                foreground = JBColor.GRAY
            })
        }

        branchPanel.add(JBLabel(build.getBranchName()).apply {
            icon = AllIcons.Vcs.BranchNode
            foreground = JBColor(Color(34, 139, 34), Color(50, 200, 50))
            font = font.deriveFont(Font.BOLD, 12f)
        })

        if (!build.sourceVersion.isNullOrBlank()) {
            branchPanel.add(Box.createHorizontalStrut(12))
            branchPanel.add(JBLabel(build.sourceVersion.take(8)).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.PLAIN, 11f)
                toolTipText = build.sourceVersion
            })
        }

        header.add(branchPanel)

        // Result badge
        val badgePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            background = UIUtil.getPanelBackground()
            alignmentX = Component.LEFT_ALIGNMENT
        }
        val resultBadge = createResultBadge(build)
        badgePanel.add(resultBadge)

        if (build.isRunning()) {
            badgePanel.add(createBadge("IN PROGRESS", JBColor(Color(0, 120, 212), Color(60, 150, 240))))
        }

        header.add(badgePanel)

        return header
    }

    // ========================
    //  Info Section
    // ========================

    private fun createInfoPanel(): JPanel {
        val info = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(8, 14, 8, 14)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // Requested by (with avatar)
        val requester = build.requestedFor ?: build.requestedBy
        if (requester != null) {
            val requesterRow = JPanel(FlowLayout(FlowLayout.LEFT, 8, 2)).apply {
                background = UIUtil.getPanelBackground()
                alignmentX = Component.LEFT_ALIGNMENT
            }
            requesterRow.add(JBLabel("Requested by:").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(11f)
            })

            val avatarIcon = avatarService.getAvatar(requester.imageUrl, 22) {
                requesterRow.repaint()
            }
            requesterRow.add(JBLabel(avatarIcon))
            requesterRow.add(JBLabel(requester.displayName ?: "Unknown").apply {
                font = font.deriveFont(Font.BOLD, 12f)
            })
            info.add(requesterRow)
        }

        // Start time
        val startRow = createInfoRow("Started:", build.getFormattedStartTime())
        info.add(startRow)

        // Finish time
        val finishRow = createInfoRow("Finished:", build.getFormattedFinishTime())
        info.add(finishRow)

        // Duration
        val durationText = build.getDuration()
        if (durationText.isNotBlank()) {
            info.add(createInfoRow("Duration:", durationText))
        }

        return info
    }

    private fun createInfoRow(label: String, value: String): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 6, 1)).apply {
            background = UIUtil.getPanelBackground()
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 24)

            add(JBLabel(label).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(11f)
            })
            add(JBLabel(value).apply {
                font = font.deriveFont(12f)
            })
        }
    }

    // ========================
    //  Actions
    // ========================

    private fun createActionsPanel(): JPanel {
        val actions = JPanel(FlowLayout(FlowLayout.LEFT, 8, 6)).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4, 14, 4, 14)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // Show Diagram
        val diagramButton = JButton("Show Diagram").apply {
            icon = AllIcons.Graph.Layout
            toolTipText = "Open a visual stage diagram for this build"
            addActionListener { openDiagram() }
        }
        actions.add(diagramButton)

        // Re-run Pipeline
        val rerunButton = JButton("Re-run Pipeline").apply {
            icon = AllIcons.Actions.Restart
            toolTipText = "Queue a new run of this pipeline"
            addActionListener { rerunPipeline() }
        }
        actions.add(rerunButton)

        // Open in Browser
        val webUrl = build.getWebUrl()
        if (webUrl.isNotBlank()) {
            val browserButton = JButton("Open in Browser").apply {
                icon = AllIcons.Ide.External_link_arrow
                toolTipText = "View this build in Azure DevOps"
                addActionListener {
                    try {
                        if (Desktop.isDesktopSupported()) {
                            Desktop.getDesktop().browse(java.net.URI(webUrl))
                        }
                    } catch (ex: Exception) {
                        logger.warn("Failed to open browser: ${ex.message}")
                    }
                }
            }
            actions.add(browserButton)
        }

        return actions
    }

    // ========================
    //  Timeline Tree
    // ========================

    private fun createTimelineTreeSection(): JPanel {
        val section = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4, 14, 14, 14)
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(0, 400)
            minimumSize = Dimension(0, 200)
        }

        // Section title
        section.add(JBLabel("Stages / Jobs / Tasks").apply {
            font = font.deriveFont(Font.BOLD, 12f)
            border = JBUI.Borders.emptyBottom(4)
        }, BorderLayout.NORTH)

        // Configure tree
        timelineTree.apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = TimelineTreeCellRenderer()

            // Click on a task opens its log
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    if (e.clickCount >= 1) {
                        val node = lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                        val record = node.userObject as? TimelineRecord ?: return
                        if (record.type == "Task" && record.hasLog()) {
                            openLog(record)
                        }
                    }
                }
            })
        }

        val treeScrollPane = JBScrollPane(timelineTree).apply {
            border = JBUI.Borders.empty()
        }
        section.add(treeScrollPane, BorderLayout.CENTER)

        // Loading indicator
        treeRootNode.add(DefaultMutableTreeNode("Loading timeline..."))
        treeModel.reload()

        return section
    }

    // ========================
    //  Data Loading
    // ========================

    private fun loadTimeline() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val tl = apiClient.getBuildTimeline(build.id)
                timeline = tl

                ApplicationManager.getApplication().invokeLater {
                    populateTimelineTree(tl)
                }
            } catch (e: Exception) {
                logger.error("Failed to load build timeline", e)
                ApplicationManager.getApplication().invokeLater {
                    treeRootNode.removeAllChildren()
                    treeRootNode.add(DefaultMutableTreeNode("Failed to load timeline: ${e.message}"))
                    treeModel.reload()
                }
            }
        }
    }

    private fun populateTimelineTree(tl: BuildTimeline?) {
        treeRootNode.removeAllChildren()

        if (tl == null || tl.records.isNullOrEmpty()) {
            treeRootNode.add(DefaultMutableTreeNode("No timeline data available"))
            treeModel.reload()
            return
        }

        val roots = tl.getRootRecords()
        if (roots.isEmpty()) {
            treeRootNode.add(DefaultMutableTreeNode("No timeline data available"))
            treeModel.reload()
            return
        }

        val visited = mutableSetOf<String>()
        for (root in roots) {
            treeRootNode.add(buildRecordSubtree(tl, root, visited))
        }

        treeModel.reload()

        // Expand all stages by default
        for (i in 0 until timelineTree.rowCount) {
            timelineTree.expandRow(i)
        }
    }

    private fun buildRecordSubtree(
        tl: BuildTimeline,
        record: TimelineRecord,
        visited: MutableSet<String>
    ): DefaultMutableTreeNode {
        val node = DefaultMutableTreeNode(record)
        val recordId = record.id
        if (recordId == null || !visited.add(recordId)) {
            return node
        }

        val children = tl.getChildren(recordId)
        for (child in children) {
            node.add(buildRecordSubtree(tl, child, visited))
        }
        return node
    }

    // ========================
    //  Actions
    // ========================

    private fun openLog(record: TimelineRecord) {
        val logId = record.log?.id ?: return
        pipelineTabService.openLogTab(build, record)
    }

    private fun openDiagram() {
        val tl = timeline
        if (tl == null) {
            JOptionPane.showMessageDialog(this, "Timeline not loaded yet. Please wait.", "Info", JOptionPane.INFORMATION_MESSAGE)
            return
        }
        pipelineTabService.openDiagramTab(build, tl)
    }

    private fun rerunPipeline() {
        val defId = build.definition?.id ?: return
        val branch = build.sourceBranch

        val confirmed = JOptionPane.showConfirmDialog(
            this,
            "Re-run pipeline '${build.getDefinitionName()}' on branch '${build.getBranchName()}'?",
            "Confirm Re-run",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )
        if (confirmed != JOptionPane.YES_OPTION) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                apiClient.queueBuild(defId, branch)
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(this, "Pipeline queued successfully!", "Success", JOptionPane.INFORMATION_MESSAGE)
                }
            } catch (e: Exception) {
                logger.error("Failed to re-run pipeline", e)
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(this, "Failed: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
                }
            }
        }
    }

    // ========================
    //  Auto-refresh
    // ========================

    private fun startAutoRefresh() {
        refreshTimer = Timer(REFRESH_INTERVAL) {
            if (!build.isRunning()) {
                refreshTimer?.stop()
                return@Timer
            }
            loadTimeline()
        }.apply {
            isRepeats = true
            start()
        }
    }

    fun dispose() {
        refreshTimer?.stop()
        refreshTimer = null
    }

    // ========================
    //  Cell Renderer
    // ========================

    /**
     * Renders stage/job/task nodes in the timeline tree with icons, names, durations, and result colors.
     */
    private inner class TimelineTreeCellRenderer : DefaultTreeCellRenderer() {

        init {
            backgroundNonSelectionColor = UIUtil.getPanelBackground()
        }

        override fun getTreeCellRendererComponent(
            tree: JTree, value: Any?, selected: Boolean,
            expanded: Boolean, leaf: Boolean, row: Int, hasFocus: Boolean
        ): Component {
            val component = super.getTreeCellRendererComponent(tree, value, selected, expanded, leaf, row, hasFocus)

            val node = value as? DefaultMutableTreeNode ?: return component
            val record = node.userObject

            if (record is TimelineRecord) {
                // Icon based on type and result
                icon = getRecordIcon(record)

                // Build display text
                val name = record.name ?: "Unknown"
                val duration = record.getDuration()
                val durationSuffix = if (duration.isNotBlank()) "  ($duration)" else ""

                // Color based on result
                foreground = when {
                    selected -> UIUtil.getTreeSelectionForeground(true)
                    record.isFailed() -> JBColor(Color(220, 53, 69), Color(230, 70, 70))
                    record.isCanceled() -> JBColor(Color(180, 160, 50), Color(200, 180, 80))
                    record.isSkipped() -> JBColor.GRAY
                    record.isRunning() -> JBColor(Color(0, 120, 212), Color(60, 150, 240))
                    record.isSucceeded() -> foreground // default
                    else -> foreground
                }

                // Font based on type
                font = when (record.type) {
                    "Stage" -> font.deriveFont(Font.BOLD, 13f)
                    "Job" -> font.deriveFont(Font.BOLD, 12f)
                    else -> font.deriveFont(Font.PLAIN, 12f)
                }

                text = "$name$durationSuffix"

                // Tooltip
                toolTipText = buildString {
                    append("$name (${record.type ?: ""})")
                    if (duration.isNotBlank()) append(" — $duration")
                    record.result?.let { append(" — $it") }
                    if ((record.errorCount ?: 0) > 0) append(" — ${record.errorCount} error(s)")
                    if ((record.warningCount ?: 0) > 0) append(" — ${record.warningCount} warning(s)")
                    if (record.type == "Task" && record.hasLog()) append("\nClick to view log")
                }
            } else if (record is String) {
                icon = AllIcons.General.Information
                text = record
            }

            return component
        }

        private fun getRecordIcon(record: TimelineRecord): Icon = when {
            record.isRunning() -> AllIcons.Process.Step_1
            record.isSucceeded() -> AllIcons.RunConfigurations.TestPassed
            record.isFailed() -> AllIcons.RunConfigurations.TestFailed
            record.isCanceled() -> AllIcons.RunConfigurations.TestTerminated
            record.isSkipped() -> AllIcons.RunConfigurations.TestSkipped
            record.isPending() -> AllIcons.RunConfigurations.TestNotRan
            else -> when (record.type) {
                "Stage" -> AllIcons.Nodes.Module
                "Phase" -> AllIcons.Nodes.Folder
                "Checkpoint" -> AllIcons.Nodes.Folder
                "Job" -> AllIcons.Nodes.ConfigFolder
                "Task" -> AllIcons.Nodes.Plugin
                else -> AllIcons.General.Information
            }
        }
    }

    // ========================
    //  Utilities
    // ========================

    private fun createResultBadge(b: PipelineBuild): JComponent {
        val (text, color) = when {
            b.isSucceeded() -> "SUCCEEDED" to JBColor(Color(34, 139, 34), Color(50, 200, 50))
            b.isFailed() -> "FAILED" to JBColor(Color(220, 53, 69), Color(230, 70, 70))
            b.isCanceled() -> "CANCELED" to JBColor(Color(180, 160, 50), Color(200, 180, 80))
            b.isPartiallySucceeded() -> "PARTIAL" to JBColor(Color(255, 165, 0), Color(255, 140, 0))
            b.isRunning() -> "RUNNING" to JBColor(Color(0, 120, 212), Color(60, 150, 240))
            else -> (b.result?.getDisplayName() ?: "UNKNOWN") to JBColor.GRAY
        }
        return createBadge(text, color)
    }

    private fun createBadge(text: String, color: JBColor): JComponent {
        return JBLabel(text).apply {
            isOpaque = true
            background = color
            foreground = Color.WHITE
            border = JBUI.Borders.empty(3, 10, 3, 10)
            font = font.deriveFont(Font.BOLD, 10f)
        }
    }

    private fun createSeparator(): JComponent {
        return JSeparator().apply {
            maximumSize = Dimension(Int.MAX_VALUE, 1)
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
