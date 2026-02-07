package paol0b.azuredevops.toolwindow.pipeline

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.BuildResult
import paol0b.azuredevops.model.PipelineBuild
import paol0b.azuredevops.services.AvatarService
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Panel that shows the list of Pipeline builds with filtering and double-click support.
 * Mirrors the pattern of [paol0b.azuredevops.toolwindow.PullRequestListPanel].
 */
class PipelineListPanel(
    private val project: Project
) {

    private val logger = Logger.getInstance(PipelineListPanel::class.java)
    private val panel: JPanel
    private val tree: Tree
    private val treeModel: DefaultTreeModel
    private val rootNode: DefaultMutableTreeNode
    private val statusLabel: JLabel
    private val avatarService = AvatarService.getInstance(project)

    // Filters
    private var resultFilter: String? = null    // null = all, "succeeded", "failed", etc.
    private var statusFilter: String? = null    // null = all, "completed", "inProgress"
    private var branchFilter: String? = null    // null = all
    private var userFilter: String? = null      // null = all
    private var definitionFilter: Int? = null   // null = all

    // State
    private var cachedBuilds: List<PipelineBuild> = emptyList()
    private var isErrorState: Boolean = false
    private var cachedHash: String = ""

    // Auto-refresh
    private var refreshTimer: Timer? = null
    private val REFRESH_INTERVAL = 30_000 // 30 seconds

    // Retry with exponential backoff
    private var retryCount: Int = 0
    private var retryTimer: Timer? = null
    private val MAX_RETRY_DELAY = 30_000 // 30 seconds max

    init {
        rootNode = DefaultMutableTreeNode("Pipelines")
        treeModel = DefaultTreeModel(rootNode)
        tree = Tree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = PipelineBuildCellRenderer()
            border = JBUI.Borders.empty(6, 12)
            rowHeight = 0 // Auto-calculate based on content
            putClientProperty("JTree.lineStyle", "Horizontal")
        }

        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)

        // Double-click opens pipeline detail tab
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val build = node.userObject as? PipelineBuild ?: return
                    PipelineToolWindowFactory.openPipelineDetailTab(project, build)
                }
            }
        })

        statusLabel = JLabel("Ready").apply {
            border = JBUI.Borders.empty(8, 12)
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.GRAY
        }

        panel = JPanel(BorderLayout()).apply {
            val scrollPane = JBScrollPane(tree).apply {
                border = JBUI.Borders.empty()
                verticalScrollBar.unitIncrement = 16
            }
            add(scrollPane, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
            minimumSize = Dimension(250, 0)
        }
    }

    fun getComponent(): JPanel = panel

    // region Filter Setters

    fun setResultFilter(result: String?) {
        if (resultFilter != result) {
            resultFilter = result
            refreshBuilds()
        }
    }

    fun setStatusFilter(status: String?) {
        if (statusFilter != status) {
            statusFilter = status
            refreshBuilds()
        }
    }

    fun setBranchFilter(branch: String?) {
        if (branchFilter != branch) {
            branchFilter = branch
            refreshBuilds()
        }
    }

    fun setUserFilter(user: String?) {
        if (userFilter != user) {
            userFilter = user
            refreshBuilds()
        }
    }

    fun setDefinitionFilter(defId: Int?) {
        if (definitionFilter != defId) {
            definitionFilter = defId
            refreshBuilds()
        }
    }

    // endregion

    fun refreshBuilds() {
        statusLabel.text = "Loading pipelines..."
        statusLabel.icon = AllIcons.Process.Step_1

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val builds = apiClient.getBuilds(
                    definitionId = definitionFilter,
                    requestedFor = userFilter,
                    branchName = branchFilter,
                    statusFilter = statusFilter,
                    resultFilter = resultFilter,
                    top = 50
                )

                ApplicationManager.getApplication().invokeLater {
                    retryCount = 0
                    retryTimer?.stop()
                    retryTimer = null

                    val newHash = computeBuildsHash(builds)
                    if (!isErrorState && newHash == cachedHash && cachedBuilds.isNotEmpty()) {
                        // No changes — just update status quietly
                        statusLabel.text = "${builds.size} pipeline run(s) — up to date"
                        statusLabel.icon = AllIcons.General.InspectionsOK
                        return@invokeLater
                    }

                    cachedBuilds = builds
                    cachedHash = newHash
                    isErrorState = false
                    updateTreeWithBuilds(builds)
                    statusLabel.text = "Loaded ${builds.size} pipeline run(s)"
                    statusLabel.icon = AllIcons.General.InspectionsOK
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    val isConfigError = e.message?.contains("not configured", ignoreCase = true) == true ||
                            e.message?.contains("Authentication required", ignoreCase = true) == true
                    if (isConfigError) {
                        rootNode.removeAllChildren()
                        treeModel.reload()
                        statusLabel.text = "Azure DevOps not configured"
                        statusLabel.icon = AllIcons.General.Warning
                        isErrorState = true
                    } else {
                        // Only show error if we had no data before
                        if (cachedBuilds.isEmpty()) {
                            updateTreeWithError(e.message ?: "Unknown error")
                        }
                        statusLabel.text = "Error loading pipelines"
                        statusLabel.icon = AllIcons.General.Error
                        isErrorState = true
                        scheduleRetry()
                    }
                }
            }
        }
    }

    // ========================
    //  Auto-refresh
    // ========================

    fun startAutoRefresh() {
        if (refreshTimer != null) return
        refreshTimer = Timer(REFRESH_INTERVAL) {
            refreshBuilds()
        }.apply {
            isRepeats = true
            start()
        }
    }

    fun stopAutoRefresh() {
        refreshTimer?.stop()
        refreshTimer = null
        retryTimer?.stop()
        retryTimer = null
    }

    private fun scheduleRetry() {
        if (retryTimer != null) return
        val delay = minOf((1000L * (1 shl retryCount)).toInt(), MAX_RETRY_DELAY)
        retryCount++
        logger.info("Scheduling pipeline list retry in ${delay}ms (attempt $retryCount)")
        retryTimer = Timer(delay) {
            retryTimer = null
            refreshBuilds()
        }.apply {
            isRepeats = false
            start()
        }
    }

    // ========================
    //  Change Detection
    // ========================

    private fun computeBuildsHash(builds: List<PipelineBuild>): String {
        if (builds.isEmpty()) return ""
        val sb = StringBuilder()
        for (b in builds) {
            sb.append(b.id).append(':')
            sb.append(b.status?.toApiValue() ?: "-").append(':')
            sb.append(b.result?.toApiValue() ?: "-").append(':')
            sb.append(b.startTime ?: "-").append(':')
            sb.append(b.finishTime ?: "-").append('|')
        }
        return sb.toString().hashCode().toString()
    }

    fun getSelectedBuild(): PipelineBuild? {
        val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        return selectedNode?.userObject as? PipelineBuild
    }

    private fun updateTreeWithBuilds(builds: List<PipelineBuild>) {
        // Save selection
        val selectedBuild = getSelectedBuild()

        rootNode.removeAllChildren()

        if (builds.isEmpty()) {
            rootNode.add(DefaultMutableTreeNode("No pipeline runs found"))
        } else {
            builds.forEach { build ->
                rootNode.add(DefaultMutableTreeNode(build))
            }
        }

        treeModel.reload()

        // Expand root to show all items
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }

        // Restore selection
        if (selectedBuild != null) {
            for (i in 0 until rootNode.childCount) {
                val child = rootNode.getChildAt(i) as? DefaultMutableTreeNode ?: continue
                val build = child.userObject as? PipelineBuild ?: continue
                if (build.id == selectedBuild.id) {
                    tree.selectionPath = TreePath(arrayOf(rootNode, child))
                    break
                }
            }
        }
    }

    private fun updateTreeWithError(errorMessage: String) {
        isErrorState = true
        rootNode.removeAllChildren()
        rootNode.add(DefaultMutableTreeNode("Error: $errorMessage"))
        treeModel.reload()
    }

    private fun showContextMenu(e: MouseEvent) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val build = node.userObject as? PipelineBuild ?: return

        tree.selectionPath = path

        val popup = JBPopupMenu()

        val openDetailItem = JMenuItem("Open Pipeline Details")
        openDetailItem.addActionListener {
            PipelineToolWindowFactory.openPipelineDetailTab(project, build)
        }
        popup.add(openDetailItem)

        if (build.definition?.id != null) {
            popup.addSeparator()
            val rerunItem = JMenuItem("Re-run Pipeline")
            rerunItem.icon = AllIcons.Actions.Restart
            rerunItem.addActionListener {
                rerunPipeline(build)
            }
            popup.add(rerunItem)
        }

        val webUrl = build.getWebUrl()
        if (webUrl.isNotBlank()) {
            popup.addSeparator()
            val openInBrowserItem = JMenuItem("Open in Browser")
            openInBrowserItem.icon = AllIcons.Ide.External_link_arrow
            openInBrowserItem.addActionListener {
                try {
                    if (Desktop.isDesktopSupported()) {
                        Desktop.getDesktop().browse(java.net.URI(webUrl))
                    }
                } catch (ex: Exception) {
                    logger.warn("Failed to open in browser: ${ex.message}")
                }
            }
            popup.add(openInBrowserItem)
        }

        popup.show(tree, e.x, e.y)
    }

    private fun rerunPipeline(build: PipelineBuild) {
        val defId = build.definition?.id ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                apiClient.queueBuild(defId, build.sourceBranch)
                ApplicationManager.getApplication().invokeLater {
                    refreshBuilds()
                }
            } catch (e: Exception) {
                logger.error("Failed to re-run pipeline", e)
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(panel, "Failed to re-run: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
                }
            }
        }
    }

    // ========================
    //  Cell Renderer
    // ========================

    private inner class PipelineBuildCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            val userObject = node.userObject

            when (userObject) {
                is PipelineBuild -> renderBuild(userObject)
                is String -> {
                    icon = AllIcons.General.Information
                    append(userObject, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
            }
        }

        private fun renderBuild(build: PipelineBuild) {
            // Result icon
            icon = when {
                build.isSucceeded() -> AllIcons.RunConfigurations.TestPassed
                build.isFailed() -> AllIcons.RunConfigurations.TestFailed
                build.isPartiallySucceeded() -> AllIcons.General.Warning
                build.isCanceled() -> AllIcons.RunConfigurations.TestIgnored
                build.isRunning() -> AllIcons.Process.Step_1
                else -> AllIcons.RunConfigurations.TestUnknown
            }

            // Pipeline definition name (bold)
            append(build.getDefinitionName(), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
            append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)

            // Branch badge
            val branchName = build.getBranchName()
            if (branchName.isNotBlank()) {
                append(branchName, SimpleTextAttributes(
                    SimpleTextAttributes.STYLE_PLAIN,
                    JBColor(Color(34, 139, 34), Color(50, 200, 50))
                ))
                append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }

            // Result badge with color
            val resultColor = when {
                build.isSucceeded() -> JBColor(Color(34, 139, 34), Color(50, 200, 50))
                build.isFailed() -> JBColor(Color(220, 50, 50), Color(255, 80, 80))
                build.isPartiallySucceeded() -> JBColor(Color(255, 165, 0), Color(255, 140, 0))
                build.isCanceled() -> JBColor(Color(150, 150, 150), Color(120, 120, 120))
                build.isRunning() -> JBColor(Color(0, 122, 204), Color(0, 164, 239))
                else -> JBColor.GRAY
            }
            val resultText = when {
                build.isSucceeded() -> "✓"
                build.isFailed() -> "✗"
                build.isPartiallySucceeded() -> "⚠"
                build.isCanceled() -> "⊘"
                build.isRunning() -> "⟳"
                else -> "?"
            }
            append(resultText, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, resultColor))

            // Avatar (requested by user)
            // Note: avatar loaded via icon field, but Tree renderer uses a single icon.
            // We show the user name instead, as the tree cell supports only one icon (already used for result).

            // Second line: build id + user + relative date
            append("\n    ", SimpleTextAttributes.REGULAR_ATTRIBUTES)

            // Build ID
            append("!${build.id}", SimpleTextAttributes(
                SimpleTextAttributes.STYLE_PLAIN,
                JBColor(Color(0, 122, 204), Color(0, 164, 239))
            ))

            // Created by
            build.requestedFor?.displayName?.let { userName ->
                append("  ·  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                append(userName, SimpleTextAttributes(
                    SimpleTextAttributes.STYLE_ITALIC,
                    JBColor(Color(100, 150, 200), Color(120, 170, 220))
                ))
            }

            // Relative date
            val relDate = build.getRelativeDate()
            if (relDate.isNotBlank()) {
                append("  ·  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                append("created $relDate", SimpleTextAttributes(
                    SimpleTextAttributes.STYLE_PLAIN,
                    JBColor.GRAY
                ))
            }

            // Duration
            val duration = build.getDuration()
            if (duration.isNotBlank()) {
                append("  ·  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                append(duration, SimpleTextAttributes(
                    SimpleTextAttributes.STYLE_PLAIN,
                    JBColor.GRAY
                ))
            }
        }
    }
}
