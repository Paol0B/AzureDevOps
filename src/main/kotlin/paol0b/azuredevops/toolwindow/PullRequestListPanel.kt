package paol0b.azuredevops.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.actions.CompletePullRequestAction
import paol0b.azuredevops.actions.EnterPullRequestBranchAction
import paol0b.azuredevops.actions.SetAutoCompletePullRequestAction
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.model.PullRequestStatus
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Wrapper class to represent a project node in the tree
 */
data class ProjectNode(val name: String, val prCount: Int)

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
    private var showAllOrganizationPrs = false
    private val statusLabel: JLabel
    private var lastSelectedPrId: Int? = null  // Backup of last selected PR ID
    private var cachedPullRequests: List<PullRequest> = emptyList()  // Cache for comparison
    private var isErrorState: Boolean = false  // Track if the tree is currently showing an error
    private val expandedNodes = mutableSetOf<String>()  // Track expanded project nodes

    init {
        rootNode = DefaultMutableTreeNode("Pull Requests")
        treeModel = DefaultTreeModel(rootNode)
        tree = Tree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = PullRequestCellRenderer()
            border = JBUI.Borders.empty(10, 16)
            rowHeight = 0 // Auto-calculate based on content
            
            // Better visual spacing and design
            putClientProperty("JTree.lineStyle", "Horizontal")
        }

        // Setup UI helper for the tree with improved search
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        
        // Add listener to track expand/collapse - add it ONCE here, not on every refresh
        addTreeExpandListener()

        // Listener for selection with visual feedback
        tree.addTreeSelectionListener(TreeSelectionListener {
            val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val pr = selectedNode?.userObject as? PullRequest
            
            // Save the selected PR ID for backup
            lastSelectedPrId = pr?.pullRequestId
            
            onSelectionChanged(pr)
        })
        
        // Add context menu for PR items
        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showContextMenu(e)
                }
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    showContextMenu(e)
                }
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val pr = node.userObject as? PullRequest ?: return
                    // Double-click opens a review tab in the tool window
                    PullRequestToolWindowFactory.openPrReviewTab(project, pr)
                }
            }
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

    /**
     * Set the cross-org mode
     */
    fun setShowAllOrganizationPrs(enabled: Boolean) {
        if (showAllOrganizationPrs != enabled) {
            showAllOrganizationPrs = enabled
            // Only clear expanded state when disabling cross-repo (projects disappear)
            if (!enabled) {
                expandedNodes.clear()
            }
            refreshPullRequests()
        }
    }
    
    /**
     * Get current cross-org mode state
     */
    fun getShowAllOrganizationPrs(): Boolean = showAllOrganizationPrs

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
                    val pullRequests = if (showAllOrganizationPrs) {
                        apiClient.getAllOrganizationPullRequests(status = currentFilter, top = 100)
                    } else {
                        apiClient.getPullRequests(status = currentFilter)
                    }

                    ApplicationManager.getApplication().invokeLater {
                        // Only update UI if data has changed or if recovering from an error
                        if (isErrorState || hasDataChanged(pullRequests)) {
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
                            // Config error is a form of error state where we want to refresh when fixed
                            isErrorState = true
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
        if (currentFilter != status) {
            currentFilter = status
            // Don't clear expanded state when changing filter - keep user's preferences
            refreshPullRequests()
        }
    }
    
    /**
     * Get current filter status
     */
    fun getFilterStatus(): String = currentFilter

    fun getSelectedPullRequest(): PullRequest? {
        val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        return selectedNode?.userObject as? PullRequest
    }

    private fun updateTreeWithPullRequests(pullRequests: List<PullRequest>, previouslySelectedPrId: Int? = null) {
        rootNode.removeAllChildren()
        isErrorState = false

        if (pullRequests.isEmpty()) {
            val emptyNode = DefaultMutableTreeNode("No Pull Requests")
            rootNode.add(emptyNode)
        } else {
            if (showAllOrganizationPrs) {
                // Group by Project - clean structure without intermediate status nodes
                val byProject = pullRequests.groupBy { it.repository?.project?.name ?: "Unknown Project" }
                
                for ((projectName, prsInProject) in byProject.toSortedMap()) {
                    // Create a wrapper object to distinguish project nodes
                    val projectNode = DefaultMutableTreeNode(ProjectNode(projectName, prsInProject.size))
                    
                    // Add PRs directly under project
                    prsInProject.sortedByDescending { it.pullRequestId }.forEach { pr ->
                        projectNode.add(DefaultMutableTreeNode(pr))
                    }
                    
                    rootNode.add(projectNode)
                }
            } else {
                // No cross-repo: show PRs directly
                pullRequests.sortedByDescending { it.pullRequestId }.forEach { pr ->
                    rootNode.add(DefaultMutableTreeNode(pr))
                }
            }
        }

        treeModel.reload()
        
        // Apply saved expansion state
        val isFirstLoad = expandedNodes.isEmpty() && showAllOrganizationPrs
        
        for (i in 0 until tree.rowCount) {
            val path = tree.getPathForRow(i) ?: continue
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: continue
            val userObject = node.userObject
            
            // For project nodes, apply saved expansion state
            if (userObject is ProjectNode) {
                if (isFirstLoad) {
                    // On first load, expand all projects for better UX
                    expandedNodes.add(userObject.name)
                    tree.expandPath(path)
                } else if (expandedNodes.contains(userObject.name)) {
                    // Only expand nodes that are in the saved state
                    // Don't force collapse others - let them keep their current state
                    tree.expandPath(path)
                }
            }
        }
        
        // Restore selection after tree is fully expanded and rendered
        if (previouslySelectedPrId != null) {
            javax.swing.SwingUtilities.invokeLater {
                javax.swing.SwingUtilities.invokeLater {
                    restoreSelection(previouslySelectedPrId)
                }
            }
        }
    }
    
    /**
     * Add listener to track when nodes are expanded/collapsed
     */
    private fun addTreeExpandListener() {
        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent?) {
                event?.path?.lastPathComponent?.let { node ->
                    if (node is DefaultMutableTreeNode) {
                        val userObject = node.userObject
                        if (userObject is ProjectNode) {
                            expandedNodes.add(userObject.name)
                        }
                    }
                }
            }

            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent?) {
                event?.path?.lastPathComponent?.let { node ->
                    if (node is DefaultMutableTreeNode) {
                        val userObject = node.userObject
                        if (userObject is ProjectNode) {
                            expandedNodes.remove(userObject.name)
                        }
                    }
                }
            }
        })
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
        // Search for the node corresponding to the PR in the new structure
        fun searchInNode(parentNode: DefaultMutableTreeNode): Boolean {
            for (i in 0 until parentNode.childCount) {
                val childNode = parentNode.getChildAt(i) as? DefaultMutableTreeNode ?: continue
                val userObject = childNode.userObject
                
                if (userObject is PullRequest && userObject.pullRequestId == prId) {
                    // Found it!
                    val path = javax.swing.tree.TreePath(childNode.path)
                    tree.selectionPath = path
                    tree.scrollPathToVisible(path)
                    return true
                }
                
                // Recursively search in children (for project nodes)
                if (searchInNode(childNode)) {
                    return true
                }
            }
            return false
        }
        
        searchInNode(rootNode)
    }

    /**
     * Show context menu for PR items
     */
    private fun showContextMenu(e: MouseEvent) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val pr = node.userObject as? PullRequest ?: return
        
        // Only show menu for active PRs
        if (!pr.isActive()) {
            return
        }
        
        // Select the item before showing menu
        tree.selectionPath = path
        
        val popup = JBPopupMenu()

        // Open PR review in tool window tab
        val openReviewItem = JMenuItem("Open Review in Tab")
        openReviewItem.addActionListener {
            PullRequestToolWindowFactory.openPrReviewTab(project, pr)
        }
        popup.add(openReviewItem)

        popup.addSeparator()
        
        // Add "Enter This Branch" action - always available for active PRs
        val enterBranchItem = JMenuItem("Enter This Branch")
        enterBranchItem.addActionListener {
            val branchService = paol0b.azuredevops.services.PullRequestBranchService.getInstance(project)
            branchService.enterPullRequestBranch(pr)
        }
        popup.add(enterBranchItem)
        
        // Determine which completion action to show based on PR state
        val showCompletePR = pr.isReadyToComplete()  // Show Complete if all checks passed and ready
        val showAutoComplete = !pr.hasAutoComplete() && !pr.isReadyToComplete()  // Show Auto-Complete if not set and not ready
        
        if (showCompletePR || showAutoComplete) {
            popup.addSeparator()
        }
        
        // Add "Complete PR..." action - only if mergeable
        if (showCompletePR) {
            val completePrItem = JMenuItem("Complete PR...")
            completePrItem.addActionListener {
                val completePrAction = CompletePullRequestAction(pr) {
                    // Refresh the PR list after completion
                    refreshPullRequests()
                }
                completePrAction.performCompletePR(project)
            }
            popup.add(completePrItem)
        }
        
        // Add "Set Auto-Complete..." action - only if not mergeable and not already set
        if (showAutoComplete) {
            val autoCompleteItem = JMenuItem("Set Auto-Complete...")
            autoCompleteItem.addActionListener {
                val autoCompleteAction = SetAutoCompletePullRequestAction(pr) {
                    // Refresh the PR list after setting auto-complete
                    refreshPullRequests()
                }
                autoCompleteAction.performSetAutoComplete(project)
            }
            popup.add(autoCompleteItem)
        }
        
        popup.show(tree, e.x, e.y)
    }

    private fun updateTreeWithError(errorMessage: String) {
        isErrorState = true
        rootNode.removeAllChildren()
        val errorNode = DefaultMutableTreeNode("Error: $errorMessage")
        rootNode.add(errorNode)
        treeModel.reload()
    }

    /**
     * Custom renderer for PRs in the tree with improved design
     */
    private inner class PullRequestCellRenderer : ColoredTreeCellRenderer() {
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
                is ProjectNode -> {
                    // ===== PROJECT NODE - Premium Design =====
                    // Project icon (same as in project tree)
                    icon = AllIcons.Nodes.Project
                    
                    // Project name with bold, large styling
                    append(userObject.name, SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_BOLD,
                        JBColor(Color(0, 95, 184), Color(100, 180, 255))
                    ))
                    
                    // PR count badge with modern styling
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("${userObject.prCount}", SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_BOLD,
                        JBColor(Color(255, 255, 255), Color(45, 45, 45))
                    ))
                    append(" PR${if (userObject.prCount != 1) "s" else ""}", SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN,
                        JBColor.GRAY
                    ))
                }
                
                is PullRequest -> {
                    // ===== PULL REQUEST NODE =====
                    // Icon based on status with better visuals
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
                    
                    // Status badges
                    if (userObject.isDraft == true) {
                        append("DRAFT", SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            JBColor(Color(255, 165, 0), Color(255, 140, 0))
                        ))
                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                    
                    if (userObject.hasAutoComplete()) {
                        append("AUTO-COMPLETE", SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            JBColor(Color(106, 153, 85), Color(106, 200, 85))
                        ))
                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                    
                    if (userObject.isReadyToComplete() && !userObject.hasAutoComplete()) {
                        append("✓ READY", SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            JBColor(Color(34, 139, 34), Color(50, 205, 50))
                        ))
                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                    
                    if (userObject.hasConflicts()) {
                        append("⚠ CONFLICTS", SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            JBColor(Color(220, 50, 50), Color(255, 80, 80))
                        ))
                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }

                    // PR title
                    val titleAttrs = if (userObject.status == PullRequestStatus.Active) {
                        SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    } else {
                        SimpleTextAttributes.GRAYED_ATTRIBUTES
                    }
                    append(userObject.title, titleAttrs)

                    // Repository name (only in cross-repo mode)
                    if (showAllOrganizationPrs) {
                        val repoName = userObject.repository?.name ?: "Unknown"
                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        append("[${repoName}]", SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_ITALIC,
                            JBColor(Color(128, 128, 128), Color(128, 128, 128))
                        ))
                    }

                    // Branch info on second line
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
                    
                    // Author info
                    userObject.createdBy?.displayName?.let { author ->
                        append("  •  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                        append(author, SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_ITALIC,
                            JBColor(Color(100, 150, 200), Color(120, 170, 220))
                        ))
                    }
                }
                
                is String -> {
                    // Fallback for other string nodes (like "No Pull Requests", errors, etc.)
                    icon = AllIcons.General.Information
                    append(userObject, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
            }
        }
    }
}
