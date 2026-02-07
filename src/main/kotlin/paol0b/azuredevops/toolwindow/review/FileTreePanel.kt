package paol0b.azuredevops.toolwindow.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.PullRequestChange
import paol0b.azuredevops.services.PrReviewStateService
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.Font
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
    
    // Track expanded directories to preserve state during refresh
    private val expandedDirectories = mutableSetOf<String>()
    
    // Cache of last loaded changes for comparison
    private var cachedChanges: List<PullRequestChange> = emptyList()

    // All changes (unfiltered) for filter support
    private var allChanges: List<PullRequestChange> = emptyList()
    
    // Current filter mode
    private var currentFilterMode = FilterMode.ALL

    // Comment counts per file path
    private val commentCountMap = mutableMapOf<String, Int>()

    /** Filter mode for the file tree */
    enum class FilterMode { ALL, REVIEWED, UNREVIEWED }

    init {
        setupTree()
        setupUI()
    }

    private fun setupTree() {
        tree.apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = FileTreeCellRenderer()
            rowHeight = 22 // Slightly taller for better touch target and spacing
            
            // Add expansion listener to track expanded directories
            addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
                override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent?) {
                    event?.path?.lastPathComponent?.let { node ->
                        if (node is DefaultMutableTreeNode) {
                            val userObject = node.userObject
                            if (userObject is DirectoryNode) {
                                expandedDirectories.add(getDirectoryPath(node))
                            }
                        }
                    }
                }

                override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent?) {
                    event?.path?.lastPathComponent?.let { node ->
                        if (node is DefaultMutableTreeNode) {
                            val userObject = node.userObject
                            if (userObject is DirectoryNode) {
                                expandedDirectories.remove(getDirectoryPath(node))
                            }
                        }
                    }
                }
            })
            
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
        // Store unfiltered changes for later filtering (only on "All" mode loads)
        if (currentFilterMode == FilterMode.ALL) {
            allChanges = changes
        }

        // Check if data has actually changed
        if (!hasDataChanged(changes)) {
            logger.info("File changes data unchanged, skipping tree reload")
            return
        }
        
        // Save currently selected file path before reload
        val selectedFilePath = getSelectedFileChange()?.item?.path
        
        rootNode.removeAllChildren()
        fileNodeMap.clear()
        
        // Sort changes by path for consistent tree building
        val sortedChanges = changes.sortedBy { it.item?.path ?: "" }
        
        sortedChanges.forEach { change ->
            val fullPath = change.item?.path ?: return@forEach
            // Azure DevOps paths usually start with /, remove it for processing
            val cleanPath = if (fullPath.startsWith("/")) fullPath.substring(1) else fullPath
            
            if (cleanPath.isEmpty()) return@forEach
            
            val parts = cleanPath.split('/')
            val dirParts = parts.dropLast(1)
            
            // Get or create parent folder
            val parentNode = getOrCreateDirectoryNode(dirParts)
            
            // Create file node
            val fileNode = FileTreeNode(change, pullRequestId, reviewStateService)
            val treeNode = DefaultMutableTreeNode(fileNode)
            parentNode.add(treeNode)
            fileNodeMap[fullPath] = fileNode
        }
        
        // Update cache
        cachedChanges = changes
        
        treeModel.reload()
        
        // Restore expansion state
        restoreExpansionState()
        
        // Restore selection if possible
        if (selectedFilePath != null) {
            javax.swing.SwingUtilities.invokeLater {
                selectFile(selectedFilePath)
            }
        }
        
        logger.info("Loaded ${changes.size} file changes into tree")
    }

    /**
     * Get or create directory structure recursively
     */
    private fun getOrCreateDirectoryNode(parts: List<String>): DefaultMutableTreeNode {
        var currentNode = rootNode
        
        for (part in parts) {
            if (part.isEmpty()) continue
            
            var foundNode: DefaultMutableTreeNode? = null
            for (i in 0 until currentNode.childCount) {
                val child = currentNode.getChildAt(i) as DefaultMutableTreeNode
                val userObject = child.userObject
                if (userObject is DirectoryNode && userObject.name == part) {
                    foundNode = child
                    break
                }
            }
            
            if (foundNode == null) {
                val newDir = DefaultMutableTreeNode(DirectoryNode(part))
                currentNode.add(newDir)
                currentNode = newDir
            } else {
                currentNode = foundNode
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
        // Save currently selected file path before reload
        val selectedFilePath = getSelectedFileChange()?.item?.path
        
        treeModel.reload()
        
        // Restore expansion state
        restoreExpansionState()
        
        // Restore selection if possible
        if (selectedFilePath != null) {
            javax.swing.SwingUtilities.invokeLater {
                selectFile(selectedFilePath)
            }
        }
    }

    /**
     * Set filter mode and rebuild tree accordingly
     */
    fun setFilterMode(mode: FilterMode) {
        if (currentFilterMode == mode) return
        currentFilterMode = mode
        rebuildFilteredTree()
    }

    /**
     * Update comment counts from loaded threads and repaint
     */
    fun updateCommentCounts(threads: List<CommentThread>) {
        commentCountMap.clear()
        for (thread in threads) {
            val path = thread.getFilePath() ?: continue
            if (thread.isDeleted == true) continue
            commentCountMap[path] = (commentCountMap[path] ?: 0) + (thread.comments?.size ?: 0)
        }
        // Repaint tree so badges are shown
        tree.repaint()
    }

    /**
     * Get comment count for a file path
     */
    fun getCommentCount(filePath: String): Int = commentCountMap[filePath] ?: 0

    /**
     * Rebuild the tree applying the current filter
     */
    private fun rebuildFilteredTree() {
        val filtered = when (currentFilterMode) {
            FilterMode.ALL -> allChanges
            FilterMode.REVIEWED -> allChanges.filter { change ->
                val path = change.item?.path ?: ""
                reviewStateService.isFileReviewed(pullRequestId, path)
            }
            FilterMode.UNREVIEWED -> allChanges.filter { change ->
                val path = change.item?.path ?: ""
                !reviewStateService.isFileReviewed(pullRequestId, path)
            }
        }
        // Force reload cache so loadFileChanges will process it
        cachedChanges = emptyList()
        loadFileChanges(filtered)
    }
    
    /**
     * Check if the file changes data has changed compared to cached data
     */
    private fun hasDataChanged(newChanges: List<PullRequestChange>): Boolean {
        // If size is different, data has changed
        if (cachedChanges.size != newChanges.size) {
            return true
        }
        
        // Compare each change by path
        val cachedPaths = cachedChanges.mapNotNull { it.item?.path }.toSet()
        val newPaths = newChanges.mapNotNull { it.item?.path }.toSet()
        
        return cachedPaths != newPaths
    }
    
    /**
     * Get the full path of a directory node by traversing up the tree
     */
    private fun getDirectoryPath(node: DefaultMutableTreeNode): String {
        val parts = mutableListOf<String>()
        var current: DefaultMutableTreeNode? = node
        
        while (current != null && current != rootNode) {
            val userObject = current.userObject
            if (userObject is DirectoryNode) {
                parts.add(0, userObject.name)
            }
            current = current.parent as? DefaultMutableTreeNode
        }
        
        return parts.joinToString("/")
    }
    
    /**
     * Restore the expansion state of directories after tree reload
     */
    private fun restoreExpansionState() {
        val isFirstLoad = expandedDirectories.isEmpty()
        
        for (i in 0 until tree.rowCount) {
            val path = tree.getPathForRow(i) ?: continue
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: continue
            val userObject = node.userObject
            
            if (userObject is DirectoryNode) {
                val dirPath = getDirectoryPath(node)
                
                if (isFirstLoad) {
                    // On first load, expand all directories
                    expandedDirectories.add(dirPath)
                    tree.expandPath(path)
                } else if (expandedDirectories.contains(dirPath)) {
                    // Restore previously expanded state
                    tree.expandPath(path)
                }
            }
        }
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
        val name: String
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
                    // Render file with checkbox and specific icon
                    val panel = JPanel(BorderLayout(4, 0)).apply {
                        isOpaque = false
                    }
                    
                    // Checkbox
                    val checkbox = JCheckBox().apply {
                        isSelected = userObject.isReviewed
                        isOpaque = false
                    }
                    
                    // File Label with Icon
                    val nameLabel = JBLabel(userObject.fileName).apply {
                        // Get file type icon
                        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(userObject.fileName)
                        icon = fileType.icon
                        
                        // Status color similar to git status
                        foreground = when (userObject.changeType.lowercase()) {
                            "add" -> JBColor(0x2E8B57, 0x629755) // Greenish
                            "delete" -> JBColor.RED
                            "rename" -> JBColor.BLUE
                            else -> if (sel) JBColor.WHITE else JBColor.BLACK
                        }
                        
                        // Font style
                        if (userObject.changeType.equals("delete", ignoreCase = true)) {
                            // Strike-through logic could go here, but simple color is safe
                        }
                    }
                    
                    panel.add(checkbox, BorderLayout.WEST)
                    panel.add(nameLabel, BorderLayout.CENTER)

                    // Comment count badge
                    val commentCount = getCommentCount(userObject.filePath)
                    if (commentCount > 0) {
                        val badge = JBLabel(" $commentCount ").apply {
                            isOpaque = true
                            background = JBColor(Color(0, 120, 212), Color(55, 148, 255))
                            foreground = Color.WHITE
                            font = font.deriveFont(Font.BOLD, 10f)
                            border = JBUI.Borders.empty(1, 4, 1, 4)
                        }
                        panel.add(badge, BorderLayout.EAST)
                    }
                    
                    return panel
                }
                
                is DirectoryNode -> {
                    // Render directory with folder icon
                    icon = AllIcons.Nodes.Folder
                    text = userObject.name
                }
            }
            
            return component
        }
    }
}
