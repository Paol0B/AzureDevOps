package paol0b.azuredevops.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.model.PullRequestStatus
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import javax.swing.*
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Panel that shows the list of Pull Requests with improved UI
 */
class PullRequestListPanel(
    private val project: Project,
    private val onSelectionChanged: (PullRequest?) -> Unit
) {

    private val panel: JPanel
    private val tree: Tree
    private val treeModel: DefaultTreeModel
    private val rootNode: DefaultMutableTreeNode
    private var currentFilter = "active"
    private val statusLabel: JLabel
    private var lastSelectedPrId: Int? = null  // Backup of last selected PR ID
    private var cachedPullRequests: List<PullRequest> = emptyList()  // Cache for comparison

    init {
        rootNode = DefaultMutableTreeNode("Pull Requests")
        treeModel = DefaultTreeModel(rootNode)
        tree = Tree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = PullRequestCellRenderer()
            border = JBUI.Borders.empty(8, 12)
            rowHeight = 0 // Auto-calculate based on content
            toggleClickCount = 0 // Disable expand on double-click
        }

        // Setup UI helper for the tree with improved search
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)

        // Listener for selection with visual feedback
        tree.addTreeSelectionListener(TreeSelectionListener {
            val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val pr = selectedNode?.userObject as? PullRequest
            
            // Save the selected PR ID for backup
            lastSelectedPrId = pr?.pullRequestId
            
            onSelectionChanged(pr)
        })
        
        // Status label with modern design
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

    fun refreshPullRequests() {
        statusLabel.text = "Loading Pull Requests..."
        statusLabel.icon = AllIcons.Process.Step_1
        
        // Save the currently selected PR ID (with fallback to last known)
        val selectedPrId = getSelectedPullRequest()?.pullRequestId ?: lastSelectedPrId
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Loading Pull Requests...",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val apiClient = AzureDevOpsApiClient.getInstance(project)
                    val pullRequests = apiClient.getPullRequests(status = currentFilter)

                    ApplicationManager.getApplication().invokeLater {
                        // Only update UI if data has changed
                        if (hasDataChanged(pullRequests)) {
                            cachedPullRequests = pullRequests
                            updateTreeWithPullRequests(pullRequests, selectedPrId)
                            statusLabel.text = "Loaded ${pullRequests.size} Pull Request(s)"
                            statusLabel.icon = AllIcons.General.InspectionsOK
                        } else {
                            // Data hasn't changed, just update status without tree refresh
                            statusLabel.text = "${pullRequests.size} Pull Request(s) (up to date)"
                            statusLabel.icon = AllIcons.General.InspectionsOK
                        }
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        val isConfigError = e.message?.contains("not configured", ignoreCase = true) == true
                        if (isConfigError) {
                            // Don't show error in tree for config issues, only in status label
                            rootNode.removeAllChildren()
                            treeModel.reload()
                            statusLabel.text = "Azure DevOps not configured"
                            statusLabel.icon = AllIcons.General.Warning
                        } else {
                            updateTreeWithError(e.message ?: "Unknown error")
                            statusLabel.text = "Error loading Pull Requests"
                            statusLabel.icon = AllIcons.General.Error
                        }
                    }
                }
            }
        })
    }

    fun setFilterStatus(status: String) {
        currentFilter = status
        refreshPullRequests()
    }

    fun getSelectedPullRequest(): PullRequest? {
        val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        return selectedNode?.userObject as? PullRequest
    }

    private fun updateTreeWithPullRequests(pullRequests: List<PullRequest>, previouslySelectedPrId: Int? = null) {
        rootNode.removeAllChildren()

        if (pullRequests.isEmpty()) {
            val emptyNode = DefaultMutableTreeNode("No Pull Requests")
            rootNode.add(emptyNode)
        } else {
            // Group by status
            val active = pullRequests.filter { it.status == PullRequestStatus.Active }
            val completed = pullRequests.filter { it.status == PullRequestStatus.Completed }
            val abandoned = pullRequests.filter { it.status == PullRequestStatus.Abandoned }

            if (active.isNotEmpty()) {
                val activeFolder = DefaultMutableTreeNode("Active (${active.size})")
                active.forEach { pr ->
                    activeFolder.add(DefaultMutableTreeNode(pr))
                }
                rootNode.add(activeFolder)
            }

            if (completed.isNotEmpty() && currentFilter == "all") {
                val completedFolder = DefaultMutableTreeNode("Completed (${completed.size})")
                completed.forEach { pr ->
                    completedFolder.add(DefaultMutableTreeNode(pr))
                }
                rootNode.add(completedFolder)
            }

            if (abandoned.isNotEmpty() && currentFilter == "all") {
                val abandonedFolder = DefaultMutableTreeNode("Abandoned (${abandoned.size})")
                abandoned.forEach { pr ->
                    abandonedFolder.add(DefaultMutableTreeNode(pr))
                }
                rootNode.add(abandonedFolder)
            }
        }

        treeModel.reload()
        
        // Expand all nodes first
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }
        
        // Restore selection after tree is fully expanded and rendered
        if (previouslySelectedPrId != null) {
            // Use invokeLater with a small delay to ensure tree is fully rendered
            javax.swing.SwingUtilities.invokeLater {
                // Double-check tree is ready
                javax.swing.SwingUtilities.invokeLater {
                    restoreSelection(previouslySelectedPrId)
                }
            }
        }
    }
    
    /**
     * Checks if pull request data has changed compared to cache
     */
    private fun hasDataChanged(newPullRequests: List<PullRequest>): Boolean {
        // If size is different, data has changed
        if (cachedPullRequests.size != newPullRequests.size) {
            return true
        }
        
        // Compare each PR by ID and key properties
        val cachedMap = cachedPullRequests.associateBy { it.pullRequestId }
        
        for (newPr in newPullRequests) {
            val cachedPr = cachedMap[newPr.pullRequestId]
            
            // If PR doesn't exist in cache, data has changed
            if (cachedPr == null) {
                return true
            }
            
            // Compare key properties that affect display
            if (cachedPr.status != newPr.status ||
                cachedPr.title != newPr.title ||
                cachedPr.isDraft != newPr.isDraft ||
                cachedPr.reviewers?.size != newPr.reviewers?.size) {
                return true
            }
            
            // Compare reviewer votes (most likely to change)
            val cachedReviewers = cachedPr.reviewers?.associateBy { it.id } ?: emptyMap()
            val newReviewers = newPr.reviewers ?: emptyList()
            
            for (newReviewer in newReviewers) {
                val cachedReviewer = cachedReviewers[newReviewer.id]
                if (cachedReviewer?.vote != newReviewer.vote) {
                    return true
                }
            }
        }
        
        // No changes detected
        return false
    }

    /**
     * Restores the selection of a PR after refresh
     */
    private fun restoreSelection(prId: Int) {
        // Search for the node corresponding to the PR
        for (i in 0 until rootNode.childCount) {
            val folderNode = rootNode.getChildAt(i) as? DefaultMutableTreeNode ?: continue
            for (j in 0 until folderNode.childCount) {
                val prNode = folderNode.getChildAt(j) as? DefaultMutableTreeNode ?: continue
                val pr = prNode.userObject as? PullRequest ?: continue
                if (pr.pullRequestId == prId) {
                    // Select this node
                    val path = javax.swing.tree.TreePath(prNode.path)
                    tree.selectionPath = path
                    tree.scrollPathToVisible(path)
                    return
                }
            }
        }
    }

    private fun updateTreeWithError(errorMessage: String) {
        rootNode.removeAllChildren()
        val errorNode = DefaultMutableTreeNode("Error: $errorMessage")
        rootNode.add(errorNode)
        treeModel.reload()
    }

    /**
     * Custom renderer for PRs in the tree with improved design
     */
    private class PullRequestCellRenderer : ColoredTreeCellRenderer() {
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
                is PullRequest -> {
                    // Modern icon based on status with better visuals
                    icon = when (userObject.status) {
                        PullRequestStatus.Active -> {
                            if (userObject.isDraft == true) AllIcons.Vcs.Patch_applied 
                            else AllIcons.Vcs.Branch
                        }
                        PullRequestStatus.Completed -> AllIcons.RunConfigurations.TestPassed
                        PullRequestStatus.Abandoned -> AllIcons.RunConfigurations.TestFailed
                        else -> AllIcons.Vcs.Branch
                    }

                    // PR ID with modern styling
                    val idColor = when (userObject.status) {
                        PullRequestStatus.Active -> SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            JBColor(Color(0, 122, 204), Color(0, 164, 239))
                        )
                        PullRequestStatus.Completed -> SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            JBColor(Color(106, 153, 85), Color(106, 153, 85))
                        )
                        else -> SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES
                    }
                    append("#${userObject.pullRequestId}", idColor)
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    
                    // Draft badge with modern styling
                    if (userObject.isDraft == true) {
                        append("DRAFT", SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            JBColor(Color(255, 165, 0), Color(255, 140, 0))
                        ))
                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }

                    // PR title with better typography
                    val titleAttrs = if (userObject.status == PullRequestStatus.Active) {
                        SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    } else {
                        SimpleTextAttributes.GRAYED_ATTRIBUTES
                    }
                    append(userObject.title, titleAttrs)

                    // Branch info with modern arrow and colors
                    append("\n    ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("${userObject.getSourceBranchName()}", SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN,
                        JBColor(Color(34, 139, 34), Color(50, 205, 50))
                    ))
                    append(" → ", SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_BOLD,
                        JBColor.GRAY
                    ))
                    append("${userObject.getTargetBranchName()}", SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN,
                        JBColor(Color(70, 130, 180), Color(135, 206, 250))
                    ))
                    
                    // Author info with modern styling
                    userObject.createdBy?.displayName?.let { author ->
                        append("  •  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                        append(author, SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_ITALIC,
                            JBColor(Color(100, 150, 200), Color(120, 170, 220))
                        ))
                    }
                }
                is String -> {
                    // Modern folder nodes with better visual hierarchy
                    when {
                        userObject.startsWith("Active") -> {
                            icon = AllIcons.Actions.Commit
                            append(userObject, SimpleTextAttributes(
                                SimpleTextAttributes.STYLE_BOLD,
                                JBColor(Color(34, 139, 34), Color(40, 167, 69))
                            ))
                        }
                        userObject.startsWith("Completed") -> {
                            icon = AllIcons.RunConfigurations.TestPassed
                            append(userObject, SimpleTextAttributes(
                                SimpleTextAttributes.STYLE_BOLD,
                                JBColor(Color(106, 153, 85), Color(106, 153, 85))
                            ))
                        }
                        userObject.startsWith("Abandoned") -> {
                            icon = AllIcons.RunConfigurations.TestFailed
                            append(userObject, SimpleTextAttributes(
                                SimpleTextAttributes.STYLE_BOLD,
                                JBColor(Color(220, 53, 69), Color(200, 35, 51))
                            ))
                        }
                        else -> {
                            icon = AllIcons.General.Information
                            append(userObject, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        }
                    }
                }
            }
        }
    }
}
