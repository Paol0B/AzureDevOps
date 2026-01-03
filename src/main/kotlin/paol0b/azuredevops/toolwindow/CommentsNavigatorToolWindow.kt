package paol0b.azuredevops.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.GitRepositoryService
import paol0b.azuredevops.services.PullRequestCommentsService
import paol0b.azuredevops.ui.CommentThreadDialog
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * Tool Window for PR Comments Navigation
 * Features:
 * - Modern card-based layout
 * - Real-time search and filtering
 * - Better HTML escape handling
 * - Improved navigation controls
 * - Enhanced visual feedback
 */
class CommentsNavigatorToolWindow : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val panel = CommentsNavigatorPanel(project)
        val content = toolWindow.contentManager.factory.createContent(
            panel,
            "",
            false
        )
        toolWindow.contentManager.addContent(content)
        
        // Add listener to load comments when tab becomes visible
        toolWindow.addContentManagerListener(object : com.intellij.ui.content.ContentManagerListener {
            override fun selectionChanged(event: com.intellij.ui.content.ContentManagerEvent) {
                if (event.content == content && event.operation == com.intellij.ui.content.ContentManagerEvent.ContentOperation.add) {
                    // Tab just opened/became visible - load comments
                    panel.loadCommentsIfNeeded()
                }
            }
        })
        
        // Also add cleanup listener
        toolWindow.contentManager.addContentManagerListener(object : com.intellij.ui.content.ContentManagerListener {
            override fun contentRemoved(event: com.intellij.ui.content.ContentManagerEvent) {
                if (event.content == content) {
                    panel.dispose()
                }
            }
        })
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

/**
 * Main panel with modern UI
 */
class CommentsNavigatorPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(CommentsNavigatorPanel::class.java)
    private val commentsList: JBList<CommentItem>
    private val statusLabel: JLabel
    private val searchField: JBTextField = JBTextField()
    private val filterButtonGroup = ButtonGroup()
    private val autoShowCheckbox: JCheckBox
    private var currentPullRequest: PullRequest? = null
    private var allComments: MutableList<CommentItem> = mutableListOf()
    private var filteredComments: MutableList<CommentItem> = mutableListOf()
    private var currentFilter: CommentFilter = CommentFilter.ALL
    private var fileEditorListener: FileEditorManagerListener? = null
    private var lastCommentsHash: Int = 0
    private var autoRefreshTimer: javax.swing.Timer? = null
    private var isInitialLoadDone: Boolean = false
    
    private enum class CommentFilter {
        ALL, ACTIVE, RESOLVED, GENERAL
    }

    init {
        border = JBUI.Borders.empty()
        
        // Initialize checkbox
        autoShowCheckbox = JCheckBox("Auto-show comments on files", false).apply {
            toolTipText = "Automatically show PR comments when opening files"
            addActionListener {
                handleAutoShowToggle()
            }
        }
        
        // Header with search and filters
        val headerPanel = createHeaderPanel()
        add(headerPanel, BorderLayout.NORTH)
        
        // Initialize filter button styles
        updateFilterButtonStyles()

        // Comments list with modern card design
        commentsList = JBList<CommentItem>().apply {
            cellRenderer = ModernCommentItemRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            background = UIUtil.getPanelBackground()
            
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    selectedValue?.let { navigateToComment(it) }
                }
            }
            
            // Add keyboard shortcuts
            addKeyListener(object : KeyAdapter() {
                override fun keyPressed(e: KeyEvent) {
                    when (e.keyCode) {
                        KeyEvent.VK_ENTER -> selectedValue?.let { openCommentDialog(it) }
                    }
                }
            })
        }

        val scrollPane = JBScrollPane(commentsList).apply {
            border = JBUI.Borders.empty()
        }
        add(scrollPane, BorderLayout.CENTER)

        // Status bar
        statusLabel = JLabel("Loading...").apply {
            border = JBUI.Borders.empty(8, 12)
            foreground = UIUtil.getLabelDisabledForeground()
            font = font.deriveFont(Font.PLAIN, 11f)
        }
        add(statusLabel, BorderLayout.SOUTH)

        // Don't load comments immediately - wait for tab to be visible
        updateStatus("Ready - open tab to load comments")
        
        // Start auto-refresh timer (every 30 seconds)
        startAutoRefresh()
    }
    
    fun loadCommentsIfNeeded() {
        if (!isInitialLoadDone) {
            logger.info("Tab became visible - loading comments for first time")
            isInitialLoadDone = true
            loadComments()
        }
    }
    
    private fun startAutoRefresh() {
        // Auto-refresh every 30 seconds, but only update UI if there are changes
        autoRefreshTimer = javax.swing.Timer(30000) {
            if (currentPullRequest != null) {
                logger.info("Auto-refresh triggered")
                checkForCommentsChanges()
            }
        }.apply {
            isRepeats = true
            start()
        }
    }
    
    private fun checkForCommentsChanges() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val pr = currentPullRequest ?: return@executeOnPooledThread
                
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val threads = apiClient.getCommentThreads(pr.pullRequestId)
                
                // Calculate hash of current comments for comparison
                val newHash = calculateCommentsHash(threads)
                
                if (newHash != lastCommentsHash) {
                    logger.info("Comments changed detected (hash: $lastCommentsHash -> $newHash), updating UI")
                    lastCommentsHash = newHash
                    
                    ApplicationManager.getApplication().invokeLater {
                        loadComments()
                    }
                } else {
                    logger.info("No changes in comments, skipping UI update")
                    updateStatus("${statusLabel.text} · Checked ${java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"))}")
                }
            } catch (e: Exception) {
                logger.warn("Auto-refresh failed: ${e.message}")
            }
        }
    }
    
    private fun calculateCommentsHash(threads: List<CommentThread>): Int {
        // Create a stable hash based on: thread count, comment count, status, content
        return threads.hashCode() + threads.sumOf { thread ->
            var hash = thread.id ?: 0
            hash = 31 * hash + (thread.status?.hashCode() ?: 0)
            hash = 31 * hash + (thread.comments?.size ?: 0)
            hash = 31 * hash + (thread.comments?.firstOrNull()?.content?.hashCode() ?: 0)
            hash = 31 * hash + (thread.comments?.lastOrNull()?.content?.hashCode() ?: 0)
            hash
        }
    }

    private fun createHeaderPanel(): JPanel {
        val panel = JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(8, 12, 8, 12)
            background = UIUtil.getPanelBackground()
        }

        // Top row: checkbox and refresh (no title - already in tab)
        val topRow = JPanel(BorderLayout(8, 0)).apply {
            background = UIUtil.getPanelBackground()
        }
        
        // Left: Auto-show checkbox
        autoShowCheckbox.apply {
            background = UIUtil.getPanelBackground()
            font = font.deriveFont(Font.PLAIN, 12f)
        }
        topRow.add(autoShowCheckbox, BorderLayout.WEST)
        
        // Right: Refresh button
        val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
            toolTipText = "Refresh comments"
            isFocusable = false
            isContentAreaFilled = false
            addActionListener {
                refreshComments()
            }
        }
        topRow.add(refreshButton, BorderLayout.EAST)
        
        panel.add(topRow, BorderLayout.NORTH)

        // Search field
        searchField.apply {
            emptyText.text = "Search comments..."
            toolTipText = "Search by file name, author, or content"
            
            addKeyListener(object : KeyAdapter() {
                override fun keyReleased(e: KeyEvent) {
                    filterComments()
                }
            })
        }
        
        // Filter buttons panel with improved spacing
        val filterPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            background = UIUtil.getPanelBackground()
        }
        
        val allButton = createFilterButton("All", CommentFilter.ALL, true)
        val activeButton = createFilterButton("Active", CommentFilter.ACTIVE, false)
        val resolvedButton = createFilterButton("Resolved", CommentFilter.RESOLVED, false)
        val generalButton = createFilterButton("General", CommentFilter.GENERAL, false)
        
        filterButtonGroup.add(allButton)
        filterButtonGroup.add(activeButton)
        filterButtonGroup.add(resolvedButton)
        filterButtonGroup.add(generalButton)
        
        filterPanel.add(allButton)
        filterPanel.add(activeButton)
        filterPanel.add(resolvedButton)
        filterPanel.add(generalButton)
        
        // Container with proper spacing
        val container = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
            
            add(searchField)
            add(Box.createVerticalStrut(8))
            add(filterPanel)
        }
        
        panel.add(container, BorderLayout.CENTER)

        return panel
    }

    private fun createFilterButton(text: String, filter: CommentFilter, selected: Boolean): JToggleButton {
        return JToggleButton(text, selected).apply {
            isFocusable = false
            font = font.deriveFont(Font.PLAIN, 11f)
            border = JBUI.Borders.empty(4, 12)
            
            // Use theme-aware colors
            background = UIUtil.getPanelBackground()
            foreground = UIUtil.getLabelForeground()
            
            addActionListener {
                currentFilter = filter
                filterComments()
                updateFilterButtonStyles()
            }
        }
    }
    
    private fun updateFilterButtonStyles() {
        // Update all filter buttons to reflect selected state with proper colors
        for (button in filterButtonGroup.elements.toList()) {
            if (button is JToggleButton) {
                if (button.isSelected) {
                    button.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
                    button.font = button.font.deriveFont(Font.BOLD)
                } else {
                    button.foreground = UIUtil.getLabelForeground()
                    button.font = button.font.deriveFont(Font.PLAIN)
                }
            }
        }
    }

    private fun refreshComments() {
        updateStatus("Refreshing...")
        ApplicationManager.getApplication().executeOnPooledThread {
            val pollingService = paol0b.azuredevops.services.CommentsPollingService.getInstance(project)
            pollingService.refreshNow()
            ApplicationManager.getApplication().invokeLater {
                loadComments()
            }
        }
    }

    private fun loadComments() {
        updateStatus("Loading comments...")
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val gitService = GitRepositoryService.getInstance(project)
                val currentBranch = gitService.getCurrentBranch()
                
                if (currentBranch == null) {
                    updateStatus("No active branch")
                    ApplicationManager.getApplication().invokeLater {
                        commentsList.setListData(emptyArray())
                    }
                    return@executeOnPooledThread
                }

                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val pullRequest = apiClient.findPullRequestForBranch(currentBranch.displayName)

                if (pullRequest == null) {
                    updateStatus("No PR for branch ${currentBranch.displayName}")
                    ApplicationManager.getApplication().invokeLater {
                        commentsList.setListData(emptyArray())
                    }
                    return@executeOnPooledThread
                }

                currentPullRequest = pullRequest
                val threads = apiClient.getCommentThreads(pullRequest.pullRequestId)
                
                // Update hash for change detection
                lastCommentsHash = calculateCommentsHash(threads)
                
                // Build comment items with proper escaping
                allComments = threads
                    .filter { !it.isDeleted!! && it.comments?.isNotEmpty() == true }
                    .sortedWith(compareBy(
                        { it.isResolved() },
                        { it.getFilePath() ?: "zzz_general" },
                        { it.getRightFileStart() ?: 0 }
                    ))
                    .map { thread ->
                        CommentItem(
                            thread = thread,
                            filePath = thread.getFilePath() ?: "General PR Comment",
                            line = thread.getRightFileStart() ?: 0,
                            pullRequest = pullRequest,
                            isGeneralComment = thread.getFilePath() == null
                        )
                    }.toMutableList()

                ApplicationManager.getApplication().invokeLater {
                    filterComments()
                    val activeCount = allComments.count { !it.isResolved }
                    val totalCount = allComments.size
                    updateStatus("$activeCount active, $totalCount total in PR #${pullRequest.pullRequestId} · Auto-refresh: ON")
                    
                    // If auto-show is enabled, show comments on current file
                    if (autoShowCheckbox.isSelected) {
                        showCommentsOnCurrentFile()
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to load comments", e)
                updateStatus("Error: ${e.message}")
            }
        }
    }

    private fun filterComments() {
        val searchText = searchField.text.lowercase()
        
        filteredComments = allComments.filter { item ->
            // Apply filter
            val matchesFilter = when (currentFilter) {
                CommentFilter.ALL -> true
                CommentFilter.ACTIVE -> !item.isResolved
                CommentFilter.RESOLVED -> item.isResolved
                CommentFilter.GENERAL -> item.isGeneralComment
            }
            
            // Apply search
            val matchesSearch = if (searchText.isBlank()) {
                true
            } else {
                item.fileName.lowercase().contains(searchText) ||
                item.author.lowercase().contains(searchText) ||
                item.contentPlain.lowercase().contains(searchText)
            }
            
            matchesFilter && matchesSearch
        }.toMutableList()
        
        ApplicationManager.getApplication().invokeLater {
            commentsList.setListData(filteredComments.toTypedArray())
            if (filteredComments.isEmpty() && allComments.isNotEmpty()) {
                updateStatus("No comments match the current filter")
            }
        }
    }

    private fun navigateToComment(item: CommentItem) {
        if (item.isGeneralComment) {
            openCommentDialog(item)
            return
        }
        
        val projectBasePath = project.basePath ?: return
        val fullPath = "$projectBasePath/${item.filePath.trimStart('/')}"
        
        // First try: Direct path lookup (fast, no EDT restriction)
        var virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(fullPath)
        
        if (virtualFile != null) {
            // Fast path: file found directly
            openFileInEditor(virtualFile, item.line)
            return
        }
        
        // Slow path: Need to search by filename - must run on background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                logger.warn("File not found by path: $fullPath, searching by filename...")
                val fileName = item.filePath.substringAfterLast('/')
                val matchingFiles = FilenameIndex.getVirtualFilesByName(
                    fileName, 
                    GlobalSearchScope.projectScope(project)
                )
                
                val foundFile = if (matchingFiles.isNotEmpty()) {
                    matchingFiles.firstOrNull { file ->
                        file.path.endsWith(item.filePath.replace('\\', '/'))
                    } ?: matchingFiles.firstOrNull()
                } else {
                    null
                }
                
                // Back to EDT for UI operations
                ApplicationManager.getApplication().invokeLater {
                    if (foundFile != null) {
                        logger.info("Found file by filename search: ${foundFile.path}")
                        openFileInEditor(foundFile, item.line)
                    } else {
                        JOptionPane.showMessageDialog(
                            this,
                            "File not found: ${item.filePath}",
                            "Navigation Error",
                            JOptionPane.WARNING_MESSAGE
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Error searching for file: ${item.filePath}", e)
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(
                        this,
                        "Error navigating to file: ${e.message}",
                        "Navigation Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }
    
    private fun openFileInEditor(virtualFile: com.intellij.openapi.vfs.VirtualFile, line: Int) {
        ApplicationManager.getApplication().invokeLater {
            val descriptor = OpenFileDescriptor(project, virtualFile, line - 1, 0)
            val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            
            if (editor != null) {
                editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
            }
        }
    }
    
    private fun handleAutoShowToggle() {
        val isEnabled = autoShowCheckbox.isSelected
        logger.info("Auto-show comments ${if (isEnabled) "enabled" else "disabled"}")
        
        if (isEnabled) {
            // Force show comments on currently open file
            showCommentsOnCurrentFile()
            
            // Register listener for file changes
            registerFileChangeListener()
        } else {
            // Unregister listener
            unregisterFileChangeListener()
        }
    }
    
    private fun showCommentsOnCurrentFile() {
        val fileEditorManager = FileEditorManager.getInstance(project)
        val currentFile = fileEditorManager.selectedFiles.firstOrNull()
        val editor = fileEditorManager.selectedTextEditor
        
        if (currentFile != null && editor != null && currentPullRequest != null) {
            logger.info("Showing comments on current file: ${currentFile.name}")
            val commentsService = PullRequestCommentsService.getInstance(project)
            commentsService.loadCommentsInEditor(editor, currentFile, currentPullRequest!!)
        }
    }
    
    private fun registerFileChangeListener() {
        if (fileEditorListener != null) {
            logger.warn("File editor listener already registered")
            return
        }
        
        fileEditorListener = object : FileEditorManagerListener {
            override fun fileOpened(source: FileEditorManager, file: com.intellij.openapi.vfs.VirtualFile) {
                if (autoShowCheckbox.isSelected && currentPullRequest != null) {
                    logger.info("Auto-showing comments for newly opened file: ${file.name}")
                    val editor = source.selectedTextEditor
                    if (editor != null) {
                        val commentsService = PullRequestCommentsService.getInstance(project)
                        commentsService.loadCommentsInEditor(editor, file, currentPullRequest!!)
                    }
                }
            }
        }
        
        // Subscribe to file editor events
        project.messageBus.connect().subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            fileEditorListener!!
        )
        
        logger.info("File change listener registered for auto-show comments")
    }
    
    private fun unregisterFileChangeListener() {
        fileEditorListener = null
        logger.info("File change listener unregistered")
    }

    private fun openCommentDialog(item: CommentItem) {
        val commentsService = paol0b.azuredevops.services.PullRequestCommentsService.getInstance(project)
        val dialog = CommentThreadDialog(project, item.thread, item.pullRequest, commentsService)
        dialog.show()
    }

    private fun updateStatus(message: String) {
        ApplicationManager.getApplication().invokeLater {
            statusLabel.text = message
        }
    }
    
    // Cleanup when panel is disposed
    fun dispose() {
        autoRefreshTimer?.stop()
        autoRefreshTimer = null
        logger.info("Auto-refresh timer stopped")
    }
}

/**
 * Comment item with proper text escaping
 */
data class CommentItem(
    val thread: CommentThread,
    val filePath: String,
    val line: Int,
    val pullRequest: PullRequest,
    val isGeneralComment: Boolean = false
) {
    val fileName: String = if (isGeneralComment) filePath else filePath.substringAfterLast('/')
    val author: String = thread.comments?.firstOrNull()?.author?.displayName ?: "Unknown"
    
    // Escape HTML to prevent rendering issues
    private val rawContent: String = thread.comments?.firstOrNull()?.content ?: ""
    val contentPlain: String = escapeHtml(rawContent)
    val contentPreview: String = contentPlain.take(80).let { if (it.length < contentPlain.length) "$it..." else it }
    
    val isResolved: Boolean = thread.isResolved()
    val commentCount: Int = thread.comments?.size ?: 0
    
    val timestamp: String? = thread.comments?.firstOrNull()?.publishedDate?.let { dateStr ->
        try {
            val zonedDateTime = ZonedDateTime.parse(dateStr)
            val formatter = DateTimeFormatter.ofPattern("MMM dd, HH:mm")
            zonedDateTime.format(formatter)
        } catch (e: Exception) {
            null
        }
    }
    
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
            .replace("\n", " ")
            .replace("\r", "")
            .trim()
    }
}

/**
 * Modern card-based renderer
 */
class ModernCommentItemRenderer : DefaultListCellRenderer() {
    
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        
        if (value is CommentItem) {
            val panel = JPanel(BorderLayout(8, 4)).apply {
                border = JBUI.Borders.empty(8, 12)
                background = if (isSelected) {
                    UIUtil.getListSelectionBackground(true)
                } else {
                    when {
                        value.isResolved -> UIUtil.getPanelBackground()
                        value.isGeneralComment -> UIUtil.getPanelBackground()
                        else -> {
                            // Subtle highlight for active comments - theme aware
                            val baseColor = UIUtil.getPanelBackground()
                            JBColor(
                                // Light theme: slight yellow tint
                                Color(
                                    (baseColor.red + 255) / 2,
                                    (baseColor.green + 250) / 2,
                                    (baseColor.blue + 230) / 2
                                ),
                                // Dark theme: slight warm tint
                                Color(
                                    (baseColor.red * 1.1f).coerceAtMost(255f).toInt(),
                                    (baseColor.green * 1.05f).coerceAtMost(255f).toInt(),
                                    baseColor.blue
                                )
                            )
                        }
                    }
                }
            }
            
            // Left: Icon and status
            val iconLabel = JLabel().apply {
                icon = when {
                    value.isGeneralComment -> AllIcons.Toolwindows.ToolWindowMessages
                    value.isResolved -> AllIcons.RunConfigurations.TestPassed
                    else -> AllIcons.General.BalloonWarning
                }
                // Icons already have proper theme colors, no need to override
            }
            panel.add(iconLabel, BorderLayout.WEST)
            
            // Center: Content
            val contentPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                background = panel.background
            }
            
            // File and line
            val locationText = if (value.isGeneralComment) {
                value.fileName
            } else {
                "${value.fileName}:${value.line}"
            }
            
            val locationLabel = JLabel(locationText).apply {
                font = font.deriveFont(Font.BOLD, 12f)
                foreground = if (isSelected) {
                    UIUtil.getListSelectionForeground(true)
                } else {
                    UIUtil.getLabelForeground()
                }
            }
            contentPanel.add(locationLabel)
            
            // Author and time
            val metaText = buildString {
                append(value.author)
                value.timestamp?.let { append(" · $it") }
                if (value.commentCount > 1) {
                    append(" · ${value.commentCount} comments")
                }
            }
            
            val metaLabel = JLabel(metaText).apply {
                font = font.deriveFont(Font.PLAIN, 10f)
                foreground = if (isSelected) {
                    UIUtil.getListSelectionForeground(true)
                } else {
                    UIUtil.getLabelDisabledForeground()
                }
            }
            contentPanel.add(Box.createVerticalStrut(2))
            contentPanel.add(metaLabel)
            
            // Content preview
            if (value.contentPreview.isNotEmpty()) {
                val contentLabel = JLabel("<html>${value.contentPreview}</html>").apply {
                    font = font.deriveFont(Font.PLAIN, 11f)
                    foreground = if (isSelected) {
                        UIUtil.getListSelectionForeground(true)
                    } else {
                        UIUtil.getLabelForeground()
                    }
                }
                contentPanel.add(Box.createVerticalStrut(4))
                contentPanel.add(contentLabel)
            }
            
            panel.add(contentPanel, BorderLayout.CENTER)
            
            return panel
        }
        
        return this
    }
    
    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        return Dimension(size.width, size.height.coerceAtLeast(70))
    }
}
