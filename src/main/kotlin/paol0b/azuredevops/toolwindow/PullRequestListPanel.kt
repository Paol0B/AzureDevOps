package paol0b.azuredevops.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.model.PullRequestStatus
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.BorderLayout
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

    init {
        rootNode = DefaultMutableTreeNode("Pull Requests")
        treeModel = DefaultTreeModel(rootNode)
        tree = Tree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = PullRequestCellRenderer()
            border = JBUI.Borders.empty(5)
        }

        // Setup UI helper for the tree
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)

        // Listener for selection
        tree.addTreeSelectionListener(TreeSelectionListener {
            val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val pr = selectedNode?.userObject as? PullRequest
            onSelectionChanged(pr)
        })
        
        // Status label at the bottom
        statusLabel = JLabel("Ready").apply {
            border = JBUI.Borders.empty(5, 10)
            font = font.deriveFont(Font.PLAIN, 11f)
        }

        panel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(tree), BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
    }

    fun getComponent(): JPanel = panel

    fun refreshPullRequests() {
        statusLabel.text = "Loading Pull Requests..."
        statusLabel.icon = AllIcons.Process.Step_1
        
        // Save the currently selected PR
        val selectedPrId = getSelectedPullRequest()?.pullRequestId
        
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
                        updateTreeWithPullRequests(pullRequests, selectedPrId)
                        statusLabel.text = "Loaded ${pullRequests.size} Pull Request(s)"
                        statusLabel.icon = AllIcons.General.InspectionsOK
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        updateTreeWithError(e.message ?: "Unknown error")
                        statusLabel.text = "Error loading Pull Requests"
                        statusLabel.icon = AllIcons.General.Error
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
            val emptyNode = DefaultMutableTreeNode("No Pull Requests found")
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
        
        // Expand all nodes
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }
        
        // Restore selection if there was a selected PR
        if (previouslySelectedPrId != null) {
            restoreSelection(previouslySelectedPrId)
        }
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
                    // Icon and color based on status
                    icon = when (userObject.status) {
                        PullRequestStatus.Active -> {
                            if (userObject.isDraft == true) AllIcons.Vcs.Patch_applied 
                            else AllIcons.Vcs.Branch
                        }
                        PullRequestStatus.Completed -> AllIcons.Process.ProgressPauseSmall
                        PullRequestStatus.Abandoned -> AllIcons.Process.Stop
                        else -> AllIcons.Vcs.Branch
                    }

                    // PR ID with color
                    val idColor = when (userObject.status) {
                        PullRequestStatus.Active -> SimpleTextAttributes.LINK_BOLD_ATTRIBUTES
                        PullRequestStatus.Completed -> SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES
                        else -> SimpleTextAttributes.GRAY_ATTRIBUTES
                    }
                    append("PR #${userObject.pullRequestId} ", idColor)
                    
                    // Draft badge
                    if (userObject.isDraft == true) {
                        append("[DRAFT] ", SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            java.awt.Color(255, 165, 0)
                        ))
                    }

                    // PR title
                    val titleAttrs = if (userObject.status == PullRequestStatus.Active) {
                        SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    } else {
                        SimpleTextAttributes.GRAYED_ATTRIBUTES
                    }
                    append(userObject.title, titleAttrs)

                    // Branch info on new visual line
                    append("  ")
                    append("${userObject.getSourceBranchName()}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    append(" → ", SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN,
                        java.awt.Color(120, 120, 120)
                    ))
                    append("${userObject.getTargetBranchName()}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    
                    // Author info
                    userObject.createdBy?.displayName?.let { author ->
                        append("  •  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                        append("by $author", SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_ITALIC,
                            java.awt.Color(100, 150, 200)
                        ))
                    }
                }
                is String -> {
                    // Folder nodes with improved icons and styles
                    when {
                        userObject.startsWith("Active") -> {
                            icon = AllIcons.Nodes.Folder
                            append(userObject, SimpleTextAttributes(
                                SimpleTextAttributes.STYLE_BOLD,
                                java.awt.Color(34, 139, 34)
                            ))
                        }
                        userObject.startsWith("Completed") -> {
                            icon = AllIcons.Nodes.Folder
                            append(userObject, SimpleTextAttributes(
                                SimpleTextAttributes.STYLE_BOLD,
                                java.awt.Color(100, 100, 100)
                            ))
                        }
                        userObject.startsWith("Abandoned") -> {
                            icon = AllIcons.Nodes.Folder
                            append(userObject, SimpleTextAttributes(
                                SimpleTextAttributes.STYLE_BOLD,
                                java.awt.Color(178, 34, 34)
                            ))
                        }
                        else -> {
                            append(userObject, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        }
                    }
                }
            }
        }
    }
}
