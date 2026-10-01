package paol0b.azuredevops.toolwindow.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckboxTreeBase
import com.intellij.ui.CheckboxTreeListener
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.PullRequestChange
import paol0b.azuredevops.model.displayChangeLabel
import paol0b.azuredevops.model.effectivePath
import paol0b.azuredevops.model.primaryChangeType
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.ReviewedFilesState
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import java.awt.*
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * File tree panel for PR review.
 *
 * Built on IntelliJ's [CheckboxTree] platform component (the same base used by
 * the JetBrains GitHub plugin).  Features:
 *   - Checkbox (left)        – syncs version-specific "reviewed" marks with Azure DevOps
 *   - File-type icon         – from IntelliJ's FileTypeManager
 *   - Filename in colour     – green=added, red=deleted, blue=renamed, default=modified
 *   - Strikethrough          – for deleted files
 *   - Comment bubble (right) – shows thread count
 *   - Change-type badge      – small coloured chip: A / M / D / R / ~
 */
class FileTreePanel(
    private val project: Project,
    private val pullRequestId: Int,
    private val externalProjectName: String? = null,
    private val externalRepositoryId: String? = null,
    private val readReviewStatus: () -> ReviewedFilesState = {
        AzureDevOpsApiClient.getInstance(project).getReviewedFiles(pullRequestId, externalProjectName, externalRepositoryId)
    },
    private val writeReviewStatus: (PullRequestChange, Boolean) -> ReviewedFilesState = { change, reviewed ->
        AzureDevOpsApiClient.getInstance(project).setFileReviewed(pullRequestId, change, reviewed, externalProjectName, externalRepositoryId)
    }
) : JPanel(BorderLayout()), Disposable {

    private var reviewedState: ReviewedFilesState? = null
    private var reviewSyncBusy = false
    private var disposed = false
    private var restoringSelection = false
    private val reviewStatusLabel = JBLabel("Review status not loaded")
    private val reviewSyncButton = JButton("Sync").apply {
        toolTipText = "Refresh reviewed-file status from Azure DevOps"
        addActionListener { syncReviewedFiles() }
    }

    // ── Node payload types ──────────────────────────────────────────────

    /** Payload held by a [CheckedTreeNode] (leaf = file). */
    data class FileTreeData(
        val change: PullRequestChange,
        private val readReviewed: (PullRequestChange) -> Boolean
    ) {
        val fileName: String   get() = change.effectivePath().substringAfterLast('/').ifEmpty { "Unknown" }
        val filePath: String   get() = change.effectivePath()
        val changeType: String get() = change.primaryChangeType()
        val isReviewed: Boolean get() = readReviewed(change)
    }

    /** Payload held by a plain [DefaultMutableTreeNode] (non-leaf = directory). */
    data class DirectoryData(val name: String)

    enum class FilterMode { ALL, REVIEWED, UNREVIEWED }

    // ── Mutable state ───────────────────────────────────────────────────

    private var currentFilterMode = FilterMode.ALL
    private var allChanges: List<PullRequestChange> = emptyList()
    private var cachedChanges: List<PullRequestChange> = emptyList()
    private val commentCountMap = mutableMapOf<String, Int>()
    private val fileNodeMap = mutableMapOf<String, CheckedTreeNode>()
    private val fileSelectionListeners = mutableListOf<(PullRequestChange) -> Unit>()

    // ── CheckboxTree ────────────────────────────────────────────────────

    private val rootNode = CheckedTreeNode("root")

    /**
     * Cell renderer built on [CheckboxTree.CheckboxTreeCellRenderer].
     *
     * [CheckboxTreeCellRenderer.getTreeCellRendererComponent] calls removeAll() then
     * re-adds the checkbox (WEST) and ColoredTreeCellRenderer (CENTER) on every paint,
     * then invokes [customizeRenderer]. We add a fixed [badgesPanel] to EAST inside
     * that callback after clearing its previous children.
     */
    private val cellRenderer = object : CheckboxTree.CheckboxTreeCellRenderer(true, false) {

        // Re-used panel – cleared and re-populated on every render call.
        private val badgesPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }

        override fun customizeRenderer(
            tree: JTree,
            value: Any,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            badgesPanel.removeAll()
            add(badgesPanel, BorderLayout.EAST)

            val node = value as? DefaultMutableTreeNode ?: return
            val obj = node.userObject

            when (obj) {
                is FileTreeData -> {
                    val ft = FileTypeManager.getInstance().getFileTypeByFileName(obj.fileName)
                    textRenderer.apply {
                        icon = ft.icon
                        val style = if (obj.changeType.equals("delete", ignoreCase = true))
                            SimpleTextAttributes.STYLE_STRIKEOUT else SimpleTextAttributes.STYLE_PLAIN
                        append(obj.fileName, SimpleTextAttributes(style, changeTypeColor(obj.changeType)))
                        toolTipText = buildTooltip(obj)
                    }
                    val count = commentCountMap[obj.filePath] ?: 0
                    if (count > 0) {
                        val accent = JBColor(Color(0x007ACC), Color(0x56A3F5))
                        badgesPanel.add(JBLabel(AllIcons.General.Balloon).apply {
                            foreground = accent
                            toolTipText = "$count comment thread(s)"
                        })
                        badgesPanel.add(JBLabel("$count").apply {
                            foreground = accent
                            font = JBUI.Fonts.smallFont()
                        })
                    }
                    badgesPanel.add(createChangeBadge(obj.changeType))
                }

                is DirectoryData -> {
                    textRenderer.apply {
                        icon = AllIcons.Nodes.Folder
                        append(obj.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                }
            }
        }

        private fun buildTooltip(obj: FileTreeData): String {
            val path  = obj.filePath.replace("&", "&amp;").replace("<", "&lt;")
            val label = obj.change.displayChangeLabel().replace("&", "&amp;").replace("<", "&lt;")
            return "<html><b>$path</b><br>Status: $label</html>"
        }
    }

    private val tree = CheckboxTree(
        cellRenderer,
        rootNode,
        CheckboxTreeBase.CheckPolicy(false, false, false, false)
    )

    // CheckboxTree creates its own DefaultTreeModel; we access it here.
    private val treeModel: DefaultTreeModel get() = tree.model as DefaultTreeModel

    init {
        tree.apply {
            isRootVisible = false
            showsRootHandles = true
            addCheckboxTreeListener(object : CheckboxTreeListener {
                override fun nodeStateChanged(node: CheckedTreeNode) {
                    val data = node.userObject as? FileTreeData ?: return
                    setReviewed(data.change, node.isChecked)
                }
            })
            addTreeSelectionListener { event ->
                if (restoringSelection) return@addTreeSelectionListener
                val node = event.path?.lastPathComponent as? CheckedTreeNode ?: return@addTreeSelectionListener
                val data = node.userObject as? FileTreeData ?: return@addTreeSelectionListener
                fileSelectionListeners.forEach { it(data.change) }
            }
        }
        add(JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(4, 6)
            add(reviewStatusLabel, BorderLayout.CENTER)
            add(reviewSyncButton, BorderLayout.EAST)
        }, BorderLayout.NORTH)
        add(JBScrollPane(tree).apply {
            border = JBUI.Borders.empty()
            minimumSize = Dimension(200, 0)
            preferredSize = Dimension(300, 0)
        }, BorderLayout.CENTER)
    }

    // ── Public API ──────────────────────────────────────────────────────

    fun loadFileChanges(changes: List<PullRequestChange>) {
        allChanges = changes
        renderFilteredChanges()
        syncReviewedFiles()
    }

    private fun renderFilteredChanges() {
        val changes = when (currentFilterMode) {
            FilterMode.ALL -> allChanges
            FilterMode.REVIEWED -> allChanges.filter { isReviewed(it) }
            FilterMode.UNREVIEWED -> allChanges.filter { !isReviewed(it) }
        }
        if (!hasDataChanged(changes)) {
            refreshTree()
            return
        }

        val previouslySelected = getSelectedFileChange()?.item?.path
        rootNode.removeAllChildren()
        fileNodeMap.clear()

        changes
            .filter { it.item?.gitObjectType?.equals("tree", ignoreCase = true) != true } // skip directory entries
            .sortedBy { it.effectivePath() }
            .forEach { change ->
            val fullPath = change.effectivePath()
            if (fullPath.isBlank()) return@forEach
            val cleanPath = fullPath.removePrefix("/")
            if (cleanPath.isEmpty()) return@forEach
            val parts = cleanPath.split('/')
            val parentNode = getOrCreateDirNode(parts.dropLast(1))
            val data = FileTreeData(change, ::isReviewed)
            val fileNode = CheckedTreeNode(data).apply {
                isChecked = data.isReviewed
                isEnabled = reviewedState != null && !reviewSyncBusy
            }
            parentNode.add(fileNode)
            fileNodeMap[fullPath] = fileNode
        }

        cachedChanges = changes
        restoringSelection = true
        try {
            treeModel.reload()
            expandAll()
            previouslySelected?.let { selectFile(it) }
        } finally { restoringSelection = false }
    }

    fun addFileSelectionListener(listener: (PullRequestChange) -> Unit) {
        fileSelectionListeners.add(listener)
    }

    fun getSelectedFileChange(): PullRequestChange? {
        val node = tree.lastSelectedPathComponent as? CheckedTreeNode ?: return null
        return (node.userObject as? FileTreeData)?.change
    }

    fun selectFile(filePath: String) {
        val node = fileNodeMap[filePath] ?: return
        val path = TreePath(treeModel.getPathToRoot(node))
        tree.selectionPath = path
        tree.scrollPathToVisible(path)
    }

    /** Apply the latest server snapshot without sending checkbox writes. */
    fun refreshTree() {
        fileNodeMap.values.forEach { node ->
            (node.userObject as? FileTreeData)?.let {
                node.isChecked = it.isReviewed
                node.isEnabled = reviewedState != null && !reviewSyncBusy
            }
        }
        tree.repaint()
    }

    fun setFilterMode(mode: FilterMode) {
        if (currentFilterMode == mode) return
        currentFilterMode = mode
        renderFilteredChanges()
    }

    private fun isReviewed(change: PullRequestChange): Boolean = reviewedState?.isReviewed(change) == true

    fun syncReviewedFiles() {
        if (reviewSyncBusy || disposed) return
        updateReviewedFiles("Syncing review status…", readReviewStatus)
    }

    private fun setReviewed(change: PullRequestChange, reviewed: Boolean) {
        if (reviewSyncBusy || reviewedState == null || disposed) { refreshTree(); return }
        updateReviewedFiles("Saving review status…") {
            writeReviewStatus(change, reviewed)
        }
    }

    private fun updateReviewedFiles(message: String, fetch: () -> ReviewedFilesState) {
        reviewSyncBusy = true
        reviewSyncButton.isEnabled = false
        reviewStatusLabel.text = message
        refreshTree()
        ApplicationManager.getApplication().executeOnPooledThread {
            val result = runCatching(fetch)
            ApplicationManager.getApplication().invokeLater {
                if (disposed || project.isDisposed) return@invokeLater
                reviewSyncBusy = false
                reviewSyncButton.isEnabled = true
                result.onSuccess {
                    reviewedState = it
                    reviewStatusLabel.text = "Review status synced"
                    reviewStatusLabel.toolTipText = "Synced with your Azure DevOps account; marks apply to this file version"
                }.onFailure {
                    // Keep the last confirmed snapshot. Never replace an API failure with unchecked files.
                    reviewStatusLabel.text = "Review status sync failed"
                    reviewStatusLabel.toolTipText = it.message ?: "Click Sync to retry"
                }
                renderFilteredChanges()
            }
        }
    }

    override fun dispose() { disposed = true }

    fun updateCommentCounts(threads: List<CommentThread>) {
        commentCountMap.clear()
        threads.filter { it.isDeleted != true }.forEach { thread ->
            val path = thread.getFilePath() ?: return@forEach
            commentCountMap[path] = (commentCountMap[path] ?: 0) + (thread.comments?.size ?: 0)
        }
        tree.repaint()
    }

    fun getCommentCount(filePath: String): Int = commentCountMap[filePath] ?: 0

    // ── Private helpers ─────────────────────────────────────────────────

    private fun hasDataChanged(new: List<PullRequestChange>): Boolean {
        return cachedChanges != new
    }

    private fun getOrCreateDirNode(parts: List<String>): DefaultMutableTreeNode {
        var current: DefaultMutableTreeNode = rootNode
        for (part in parts) {
            if (part.isEmpty()) continue
            val existing = (0 until current.childCount)
                .map { current.getChildAt(it) as DefaultMutableTreeNode }
                .firstOrNull { (it.userObject as? DirectoryData)?.name == part }
            current = existing
                ?: DefaultMutableTreeNode(DirectoryData(part)).also { current.add(it) }
        }
        return current
    }

    private fun expandAll() {
        fun rec(node: DefaultMutableTreeNode) {
            for (i in 0 until node.childCount) {
                val child = node.getChildAt(i) as DefaultMutableTreeNode
                if (child.userObject is DirectoryData) {
                    tree.expandPath(TreePath(treeModel.getPathToRoot(child)))
                    rec(child)
                }
            }
        }
        rec(rootNode)
    }

    /** Foreground colour for the filename; null = default tree colour (for modify/other). */
    private fun changeTypeColor(type: String): Color? = when (type.lowercase()) {
        "add"    -> JBColor(Color(0x2A7A3B), Color(0x57A65B))
        "delete" -> JBColor(Color(0xB22222), Color(0xE06C75))
        "rename" -> JBColor(Color(0x2171B5), Color(0x56B6C2))
        else     -> null
    }

    /**
     * A HiDPI-painted rounded chip with a single capital letter.
     *   A = added  M = modified  D = deleted  R = renamed  ~ = other
     */
    private fun createChangeBadge(changeType: String): JComponent {
        val (letter, bg, fg) = when (changeType.lowercase()) {
            "add"            -> Triple("A",
                JBColor(Color(0xD4EDDA), Color(0x1E4620)),
                JBColor(Color(0x155724), Color(0x66BB6A)))
            "delete"         -> Triple("D",
                JBColor(Color(0xF8D7DA), Color(0x4A1515)),
                JBColor(Color(0x721C24), Color(0xEF9A9A)))
            "rename"         -> Triple("R",
                JBColor(Color(0xD1ECF1), Color(0x1A3A4A)),
                JBColor(Color(0x0C5460), Color(0x4FC3F7)))
            "edit", "modify" -> Triple("M",
                JBColor(Color(0xD6EAF8), Color(0x1A2E4A)),
                JBColor(Color(0x154360), Color(0x64B5F6)))
            else             -> Triple("~",
                JBColor(Color(0xEEEEEE), Color(0x3C3F41)),
                JBColor(Color(0x555555), Color(0xAAAAAA)))
        }
        return object : JComponent() {
            init {
                preferredSize = Dimension(JBUI.scale(16), JBUI.scale(14))
                isOpaque = false
                toolTipText = changeType.replaceFirstChar { it.uppercaseChar() }
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = bg
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(4), JBUI.scale(4))
                g2.color = fg
                g2.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
                val fm = g2.fontMetrics
                g2.drawString(
                    letter,
                    (width - fm.stringWidth(letter)) / 2,
                    (height + fm.ascent - fm.descent) / 2
                )
                g2.dispose()
            }
        }
    }
}
