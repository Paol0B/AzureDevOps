package paol0b.azuredevops.toolwindow.review

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.PullRequestChange
import paol0b.azuredevops.services.PrReviewStateService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeCellRenderer
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * File tree panel for PR review (Azure DevOps style)
 * Shows hierarchical file structure with checkboxes and status badges
 */
class FileTreePanel(
    private val project: Project,
    private val pullRequestId: Int
) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(FileTreePanel::class.java)
    private val reviewStateService = PrReviewStateService.getInstance(project)
    
    private val rootNode = DefaultMutableTreeNode("Files")
    private val treeModel = DefaultTreeModel(rootNode)
    private val tree = Tree(treeModel)
    
    // Track file changes and their tree nodes
    private val fileNodeMap = mutableMapOf<String, FileTreeNode>()
    
    // Listeners for file selection
    private val fileSelectionListeners = mutableListOf<(PullRequestChange) -> Unit>()

    init {
        setupTree()
        setupUI()
    }

    private fun setupTree() {
        tree.apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = FileTreeCellRenderer()
            
            // Handle selection
            addTreeSelectionListener { event ->
                val node = event.path?.lastPathComponent as? DefaultMutableTreeNode
                val userObject = node?.userObject
                
                if (userObject is FileTreeNode) {
                    // Notify listeners that a file was selected
                    fileSelectionListeners.forEach { it(userObject.change) }
                }
            }
            
            // Handle mouse clicks for the checkbox
            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode
                    val userObject = node?.userObject as? FileTreeNode ?: return
                    
                    // Define checkbox area (roughly first 24 pixels)
                    val rowBounds = tree.getPathBounds(path) ?: return
                    val checkboxWidth = 24
                    
                    if (e.x >= rowBounds.x && e.x <= rowBounds.x + checkboxWidth) {
                        userObject.toggleReviewed()
                        treeModel.nodeChanged(node)
                        tree.repaint(rowBounds)
                        e.consume()
                    }
                }
            })
        }
    }

    private fun setupUI() {
        val scrollPane = JBScrollPane(tree).apply {
            border = JBUI.Borders.empty()
            minimumSize = Dimension(200, 0)
            preferredSize = Dimension(300, 0)
        }
        
        add(scrollPane, BorderLayout.CENTER)
    }

    /**
     * Load file changes into the tree
     */
    fun loadFileChanges(changes: List<PullRequestChange>) {
        rootNode.removeAllChildren()
        fileNodeMap.clear()
        
        // Group files by directory
        val filesByDirectory = changes.groupBy { change ->
            val path = change.item?.path ?: ""
            val lastSlash = path.lastIndexOf('/')
            if (lastSlash > 0) path.substring(0, lastSlash) else ""
        }
        
        // Build tree structure
        filesByDirectory.entries.sortedBy { it.key }.forEach { (directory, files) ->
            if (directory.isEmpty()) {
                // Root level files
                files.forEach { change ->
                    val fileNode = FileTreeNode(change, pullRequestId, reviewStateService)
                    val treeNode = DefaultMutableTreeNode(fileNode)
                    rootNode.add(treeNode)
                    fileNodeMap[change.item?.path ?: ""] = fileNode
                }
            } else {
                // Create directory node
                val dirNode = createDirectoryNode(directory)
                
                // Add files to directory
                files.forEach { change ->
                    val fileNode = FileTreeNode(change, pullRequestId, reviewStateService)
                    val treeNode = DefaultMutableTreeNode(fileNode)
                    dirNode.add(treeNode)
                    fileNodeMap[change.item?.path ?: ""] = fileNode
                }
                
                rootNode.add(dirNode)
            }
        }
        
        treeModel.reload()
        
        // Expand all directories by default
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }
        
        logger.info("Loaded ${changes.size} file changes into tree")
    }

    /**
     * Create a hierarchical directory node
     */
    private fun createDirectoryNode(path: String): DefaultMutableTreeNode {
        val parts = path.split('/')
        var currentNode = rootNode
        var currentPath = ""
        
        for (part in parts) {
            currentPath = if (currentPath.isEmpty()) part else "$currentPath/$part"
            
            // Check if directory node already exists
            var found = false
            for (i in 0 until currentNode.childCount) {
                val child = currentNode.getChildAt(i) as DefaultMutableTreeNode
                if (child.userObject is DirectoryNode && (child.userObject as DirectoryNode).name == part) {
                    currentNode = child
                    found = true
                    break
                }
            }
            
            if (!found) {
                val dirNode = DefaultMutableTreeNode(DirectoryNode(part, currentPath))
                currentNode.add(dirNode)
                currentNode = dirNode
            }
        }
        
        return currentNode
    }

    /**
     * Add a file selection listener
     */
    fun addFileSelectionListener(listener: (PullRequestChange) -> Unit) {
        fileSelectionListeners.add(listener)
    }

    /**
     * Get the currently selected file change
     */
    fun getSelectedFileChange(): PullRequestChange? {
        val node = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        return (node?.userObject as? FileTreeNode)?.change
    }

    /**
     * Select a specific file by path
     */
    fun selectFile(filePath: String) {
        val fileNode = fileNodeMap[filePath] ?: return
        
        // Find the tree node
        val enumeration = rootNode.depthFirstEnumeration()
        while (enumeration.hasMoreElements()) {
            val node = enumeration.nextElement() as DefaultMutableTreeNode
            if (node.userObject == fileNode) {
                val path = TreePath(treeModel.getPathToRoot(node))
                tree.selectionPath = path
                tree.scrollPathToVisible(path)
                break
            }
        }
    }

    /**
     * Refresh the tree to update reviewed status
     */
    fun refreshTree() {
        treeModel.reload()
    }

    /**
     * File tree node with reviewed state
     */
    class FileTreeNode(
        val change: PullRequestChange,
        private val pullRequestId: Int,
        private val reviewStateService: PrReviewStateService
    ) {
        val fileName: String
            get() = change.item?.path?.substringAfterLast('/') ?: "Unknown"
        
        val filePath: String
            get() = change.item?.path ?: ""
        
        val changeType: String
            get() = change.changeType ?: "unknown"
        
        var isReviewed: Boolean
            get() = reviewStateService.isFileReviewed(pullRequestId, filePath)
            set(value) {
                if (value) {
                    reviewStateService.markFileAsReviewed(pullRequestId, filePath)
                } else {
                    reviewStateService.unmarkFileAsReviewed(pullRequestId, filePath)
                }
            }
        
        fun toggleReviewed() {
            isReviewed = !isReviewed
        }
    }

    /**
     * Directory node in the tree
     */
    data class DirectoryNode(
        val name: String,
        val fullPath: String
    )

    /**
     * Custom cell renderer for file tree
     */
    private inner class FileTreeCellRenderer : DefaultTreeCellRenderer() {
        override fun getTreeCellRendererComponent(
            tree: JTree?,
            value: Any?,
            sel: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ): Component {
            val component = super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus)
            
            val node = value as? DefaultMutableTreeNode
            val userObject = node?.userObject
            
            when (userObject) {
                is FileTreeNode -> {
                    // Render file with checkbox and status badge
                    val panel = JPanel(BorderLayout()).apply {
                        isOpaque = false
                    }
                    
                    // Checkbox
                    val checkbox = JCheckBox().apply {
                        isSelected = userObject.isReviewed
                        isOpaque = false
                        addActionListener {
                            userObject.toggleReviewed()
                            treeModel.nodeChanged(node)
                        }
                    }
                    
                    // File label with change type indicator
                    val changeIcon = when (userObject.changeType.lowercase()) {
                        "add" -> "🟢 "
                        "edit" -> "🟡 "
                        "delete" -> "🔴 "
                        "rename" -> "🔵 "
                        else -> "• "
                    }
                    
                    val label = JBLabel("$changeIcon${userObject.fileName}")
                    
                    panel.add(checkbox, BorderLayout.WEST)
                    panel.add(label, BorderLayout.CENTER)
                    
                    return panel
                }
                
                is DirectoryNode -> {
                    // Render directory
                    text = "📁 ${userObject.name}"
                }
            }
            
            return component
        }
    }
}
