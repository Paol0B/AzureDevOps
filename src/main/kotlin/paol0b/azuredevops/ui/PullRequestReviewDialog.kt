package paol0b.azuredevops.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.model.PullRequestChange
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * Dialog for a complete PR review with file list and diff viewer
 */
class PullRequestReviewDialog(
    private val project: Project,
    private val pullRequest: PullRequest,
    private val fileChanges: List<PullRequestChange>
) : DialogWrapper(project, true) {

    private val logger = Logger.getInstance(PullRequestReviewDialog::class.java)
    private val rootNode = DefaultMutableTreeNode("Files")
    private val fileTreeModel = DefaultTreeModel(rootNode)
    private val fileTree: Tree
    private val avatarLabel = JLabel()

    init {
        title = "Review #${pullRequest.pullRequestId}: ${pullRequest.title}"

        buildFileTree()
        fileTree = Tree(fileTreeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            rowHeight = 0
            cellRenderer = FileTreeCellRenderer()
            selectionModel.selectionMode = TreeSelectionModel.DISCONTIGUOUS_TREE_SELECTION
            TreeUIHelper.getInstance().installTreeSpeedSearch(this)

            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                    if (e?.clickCount != 2) {
                        return
                    }
                    val path = getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val fileNode = node.userObject as? FileNode ?: return
                    openFileDiff(fileNode.change)
                }
            })
        }

        avatarLabel.preferredSize = Dimension(40, 40)
        loadAvatar(pullRequest.createdBy?.imageUrl, avatarLabel)
        
        init()
        
        // No initial automatic selection - user clicks the file
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8)
        }

        panel.add(createHeaderPanel(), BorderLayout.NORTH)

        val leftPanel = createFilesPanel()
        val rightPanel = createReviewSummaryPanel()

        val splitter = com.intellij.ui.JBSplitter(false, 0.42f).apply {
            firstComponent = leftPanel
            secondComponent = rightPanel
        }

        panel.add(splitter, BorderLayout.CENTER)
        panel.preferredSize = Dimension(900, 560)

        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(
            ShowCombinedDiffAction(),
            okAction
        )
    }
    
    /**
     * Action to show the combined diff of selected files
     */
    private inner class ShowCombinedDiffAction : AbstractAction("Show Combined Diff") {
        init {
            putValue(Action.MNEMONIC_KEY, 'D'.code)
        }
        
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
            val selectedChanges = getSelectedChanges()
            if (selectedChanges.isEmpty()) {
                JOptionPane.showMessageDialog(
                    contentPane,
                    "Select at least one file in the tree",
                    "No File Selected",
                    JOptionPane.WARNING_MESSAGE
                )
                return
            }
            // Open the diffs of the selected files in sequence
            selectedChanges.forEach { change ->
                openFileDiff(change)
            }
        }
    }

    /**
     * Opens the diff for a file
     */
    private fun openFileDiff(change: PullRequestChange) {
        val path = change.item?.path ?: return
        
        logger.info("Opening diff for: $path")
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                
                // Get new content (source commit)
                val sourceCommit = pullRequest.lastMergeSourceCommit?.commitId
                val newContent = if (sourceCommit != null && change.changeType?.lowercase() != "delete") {
                    apiClient.getFileContent(sourceCommit, path)
                } else ""
                
                // Get old content (target commit)
                val targetCommit = pullRequest.lastMergeTargetCommit?.commitId
                val oldContent = if (targetCommit != null && change.changeType?.lowercase() != "add") {
                    try {
                        apiClient.getFileContent(targetCommit, path)
                    } catch (e: Exception) {
                        logger.info("File is new (doesn't exist in base): ${e.message}")
                        ""
                    }
                } else ""
                
                // Open diff in the UI thread
                ApplicationManager.getApplication().invokeLater {
                    showDiffViewer(path, oldContent, newContent)
                }
                
            } catch (e: Exception) {
                logger.error("Failed to load file diff", e)
            }
        }
    }

    /**
     * Shows the diff viewer
     */
    private fun showDiffViewer(filePath: String, oldContent: String, newContent: String) {
        val contentFactory = DiffContentFactory.getInstance()
        val fileTypeManager = FileTypeManager.getInstance()
        
        val fileName = filePath.substringAfterLast('/')
        val fileType = fileTypeManager.getFileTypeByFileName(fileName)
        
        val oldDiffContent = contentFactory.create(oldContent, fileType)
        val newDiffContent = contentFactory.create(newContent, fileType)
        
        val diffRequest = SimpleDiffRequest(
            "PR #${pullRequest.pullRequestId}: $fileName",
            oldDiffContent,
            newDiffContent,
            "Base (${pullRequest.targetRefName?.substringAfterLast('/')})",
            "Changes (${pullRequest.sourceRefName?.substringAfterLast('/')})"
        )
        
        DiffManager.getInstance().showDiff(project, diffRequest)
    }

    private fun createHeaderPanel(): JComponent {
        val headerPanel = JPanel(BorderLayout(12, 0)).apply {
            border = JBUI.Borders.empty(6, 6, 10, 6)
        }

        val titlePanel = JPanel(BorderLayout()).apply {
            val titleLabel = JBLabel(pullRequest.title).apply {
                font = font.deriveFont(Font.BOLD, 16f)
            }
            val subtitle = JBLabel(
                "PR #${pullRequest.pullRequestId}  •  ${pullRequest.status.getDisplayName()}  •  ${pullRequest.getSourceBranchName()} → ${pullRequest.getTargetBranchName()}"
            ).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.PLAIN, 11f)
            }
            add(titleLabel, BorderLayout.NORTH)
            add(subtitle, BorderLayout.SOUTH)
        }

        val authorPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            border = JBUI.Borders.empty(2, 0)
            val authorName = pullRequest.createdBy?.displayName ?: "Unknown author"
            val authorLabel = JBLabel(authorName).apply {
                font = font.deriveFont(Font.BOLD, 12f)
            }
            add(avatarLabel)
            add(authorLabel)
        }

        val badgesPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            pullRequest.isDraft?.takeIf { it }?.let {
                add(createBadge("DRAFT", JBColor(Color(255, 165, 0), Color(255, 165, 0))))
            }
            pullRequest.hasAutoComplete().takeIf { it }?.let {
                add(createBadge("AUTO-COMPLETE", JBColor(Color(72, 166, 70), Color(72, 166, 70))))
            }
            pullRequest.hasConflicts().takeIf { it }?.let {
                add(createBadge("CONFLICTS", JBColor(Color(210, 70, 70), Color(210, 70, 70))))
            }
        }

        headerPanel.add(authorPanel, BorderLayout.WEST)
        headerPanel.add(titlePanel, BorderLayout.CENTER)
        headerPanel.add(badgesPanel, BorderLayout.EAST)

        return headerPanel
    }

    private fun createFilesPanel(): JComponent {
        val leftPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 0, 1)
        }

        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 10)
        }

        val titleLabel = JBLabel("Files Changed (${fileChanges.size})").apply {
            font = font.deriveFont(Font.BOLD, 12f)
        }
        headerPanel.add(titleLabel, BorderLayout.WEST)

        val buttonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply {
            val selectAllBtn = JButton("All").apply {
                toolTipText = "Select all files"
                addActionListener { selectAllFiles() }
            }
            val deselectAllBtn = JButton("None").apply {
                toolTipText = "Deselect all files"
                addActionListener { fileTree.clearSelection() }
            }
            add(selectAllBtn)
            add(deselectAllBtn)
        }
        headerPanel.add(buttonsPanel, BorderLayout.EAST)

        leftPanel.add(headerPanel, BorderLayout.NORTH)
        leftPanel.add(JBScrollPane(fileTree), BorderLayout.CENTER)
        leftPanel.minimumSize = Dimension(320, 0)

        return leftPanel
    }

    private fun createReviewSummaryPanel(): JComponent {
        val summaryPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(12)
        }

        val summaryHeader = JBLabel("Review Summary").apply {
            font = font.deriveFont(Font.BOLD, 13f)
        }

        val countsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 6)).apply {
            val summary = computeChangeSummary()
            if (summary.added > 0) {
                add(createBadge("+${summary.added} added", JBColor(Color(76, 175, 80), Color(76, 175, 80))))
            }
            if (summary.modified > 0) {
                add(createBadge("~${summary.modified} modified", JBColor(Color(66, 133, 244), Color(66, 133, 244))))
            }
            if (summary.deleted > 0) {
                add(createBadge("-${summary.deleted} deleted", JBColor(Color(219, 68, 55), Color(219, 68, 55))))
            }
        }

        val infoLabel = JBLabel(
            "<html><b>Tips</b><br><br>" +
                "• Select one or more files in the tree<br>" +
                "• Double-click a file to open the diff<br>" +
                "• Use <b>Show Combined Diff</b> to open multiple diffs</html>"
        )

        summaryPanel.add(summaryHeader, BorderLayout.NORTH)
        summaryPanel.add(countsPanel, BorderLayout.CENTER)
        summaryPanel.add(infoLabel, BorderLayout.SOUTH)

        return summaryPanel
    }

    private fun createBadge(text: String, background: JBColor): JComponent {
        return JBLabel(text).apply {
            isOpaque = true
            this.background = background
            foreground = Color.WHITE
            border = JBUI.Borders.empty(2, 8)
            font = font.deriveFont(Font.BOLD, 11f)
        }
    }

    private fun selectAllFiles() {
        val paths = mutableListOf<javax.swing.tree.TreePath>()
        collectFilePaths(rootNode, javax.swing.tree.TreePath(rootNode), paths)
        fileTree.selectionPaths = if (paths.isEmpty()) null else paths.toTypedArray()
    }

    private fun collectFilePaths(
        node: DefaultMutableTreeNode,
        path: javax.swing.tree.TreePath,
        result: MutableList<javax.swing.tree.TreePath>
    ) {
        if (node.userObject is FileNode) {
            result.add(path)
            return
        }
        for (i in 0 until node.childCount) {
            val child = node.getChildAt(i) as DefaultMutableTreeNode
            collectFilePaths(child, path.pathByAddingChild(child), result)
        }
    }

    private fun getSelectedChanges(): List<PullRequestChange> {
        val paths = fileTree.selectionPaths ?: return emptyList()
        return paths.mapNotNull { path ->
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return@mapNotNull null
            val fileNode = node.userObject as? FileNode ?: return@mapNotNull null
            fileNode.change
        }.distinct()
    }

    private fun computeChangeSummary(): ChangeSummary {
        var added = 0
        var modified = 0
        var deleted = 0

        fileChanges.forEach { change ->
            when (change.changeType?.lowercase()) {
                "add" -> added += 1
                "edit" -> modified += 1
                "delete" -> deleted += 1
            }
        }

        return ChangeSummary(added, modified, deleted)
    }

    private fun buildFileTree() {
        rootNode.removeAllChildren()
        val nodeByPath = mutableMapOf<String, DefaultMutableTreeNode>()
        nodeByPath[""] = rootNode

        fileChanges.forEach { change ->
            val path = change.item?.path ?: return@forEach
            val cleanPath = path.trim('/')
            val segments = if (cleanPath.isBlank()) emptyList() else cleanPath.split('/')
            val fileName = segments.lastOrNull() ?: cleanPath
            val dirSegments = if (segments.size > 1) segments.dropLast(1) else emptyList()

            var currentPath = ""
            var parent = rootNode
            for (segment in dirSegments) {
                currentPath = if (currentPath.isEmpty()) segment else "$currentPath/$segment"
                val dirNode = nodeByPath.getOrPut(currentPath) {
                    DefaultMutableTreeNode(DirectoryNode(segment))
                }
                if (dirNode.parent == null) {
                    parent.add(dirNode)
                }
                parent = dirNode
            }

            val fileNode = DefaultMutableTreeNode(
                FileNode(
                    fileName = fileName,
                    folderPath = dirSegments.joinToString("/"),
                    changeType = change.changeType ?: "unknown",
                    change = change,
                    fullPath = path
                )
            )
            parent.add(fileNode)
        }

        fileTreeModel.reload()
    }

    private fun loadAvatar(imageUrl: String?, targetLabel: JLabel) {
        if (imageUrl.isNullOrBlank()) {
            targetLabel.icon = AllIcons.General.User
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val uri = java.net.URI(imageUrl)
                val url = uri.toURL()
                val image = javax.imageio.ImageIO.read(url)
                val scaledImage = image.getScaledInstance(
                    targetLabel.preferredSize.width,
                    targetLabel.preferredSize.height,
                    java.awt.Image.SCALE_SMOOTH
                )

                ApplicationManager.getApplication().invokeLater {
                    targetLabel.icon = ImageIcon(scaledImage)
                }
            } catch (e: Exception) {
                logger.debug("Failed to load avatar", e)
                ApplicationManager.getApplication().invokeLater {
                    targetLabel.icon = AllIcons.General.User
                }
            }
        }
    }

    private inner class FileTreeCellRenderer : ColoredTreeCellRenderer() {
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
            when (val userObject = node.userObject) {
                is DirectoryNode -> {
                    icon = AllIcons.Nodes.Folder
                    append(userObject.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                }
                is FileNode -> {
                    val fileType = FileTypeManager.getInstance().getFileTypeByFileName(userObject.fileName)
                    icon = fileType.icon ?: AllIcons.FileTypes.Text

                    val changePrefix = when (userObject.changeType.lowercase()) {
                        "add" -> "+ "
                        "edit" -> "~ "
                        "delete" -> "- "
                        "rename" -> "→ "
                        else -> "• "
                    }
                    val changeColor = when (userObject.changeType.lowercase()) {
                        "add" -> JBColor(Color(76, 175, 80), Color(76, 175, 80))
                        "edit" -> JBColor(Color(66, 133, 244), Color(66, 133, 244))
                        "delete" -> JBColor(Color(219, 68, 55), Color(219, 68, 55))
                        else -> JBColor.GRAY
                    }

                    append(changePrefix, SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, changeColor))
                    append(userObject.fileName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    if (userObject.folderPath.isNotBlank()) {
                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        append(userObject.folderPath, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
                else -> {
                    append(userObject?.toString() ?: "", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                }
            }
        }
    }

    private data class DirectoryNode(val name: String)

    private data class FileNode(
        val fileName: String,
        val folderPath: String,
        val changeType: String,
        val change: PullRequestChange,
        val fullPath: String
    )

    private data class ChangeSummary(
        val added: Int,
        val modified: Int,
        val deleted: Int
    )
}
