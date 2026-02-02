package paol0b.azuredevops.toolwindow.review

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseListener
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.util.Disposer
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.PullRequestChange
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import javax.swing.JButton
import javax.swing.JPanel

/**
 * Diff viewer panel with syntax highlighting and inline comments support
 * Uses JetBrains Diff API for professional diff viewing
 */
class DiffViewerPanel(
    private val project: Project,
    private val pullRequestId: Int,
    private val externalProjectName: String? = null,
    private val externalRepositoryId: String? = null
) : JPanel(BorderLayout()), Disposable {

    private val logger = Logger.getInstance(DiffViewerPanel::class.java)
    private val apiClient = AzureDevOpsApiClient.getInstance(project)
    private val diffContentFactory = DiffContentFactory.getInstance()
    
    private var currentDiffPanel: DiffRequestPanel? = null
    private var currentChange: PullRequestChange? = null
    private var cachedPullRequest: paol0b.azuredevops.model.PullRequest? = null
    
    // Track documents to identify editors
    private var baseDocument: Document? = null
    private var changesDocument: Document? = null
    
    // Track editors for inline comments
    private var baseEditor: Editor? = null
    private var changesEditor: Editor? = null
    
    // Cache for comment threads
    private var cachedThreads: List<CommentThread> = emptyList()
    
    // Track active inlays for cleanup
    private val activeInlays = mutableListOf<com.intellij.openapi.editor.Inlay<*>>()
    
    // Track active highlighters for cleanup
    private val activeHighlighters = mutableListOf<com.intellij.openapi.editor.markup.RangeHighlighter>()
    
    private val placeholderLabel = JBLabel("Select a file to view diff").apply {
        horizontalAlignment = JBLabel.CENTER
        border = JBUI.Borders.empty(20)
    }

    init {
        minimumSize = Dimension(400, 0)
        preferredSize = Dimension(600, 0)
        add(placeholderLabel, BorderLayout.CENTER)
        
        logger.info("DiffViewerPanel created: pullRequestId=$pullRequestId, externalProject=$externalProjectName, externalRepo=$externalRepositoryId")

        // Listen for editor creation to attach interaction listeners
        EditorFactory.getInstance().addEditorFactoryListener(object : EditorFactoryListener {
            override fun editorCreated(event: EditorFactoryEvent) {
                val editor = event.editor
                // Check if this editor belongs to our current diff
                if (editor.document == baseDocument || editor.document == changesDocument) {
                    // Store editor reference
                    if (editor.document == baseDocument) {
                        baseEditor = editor
                    } else {
                        changesEditor = editor
                    }
                    
                    // Add inline comments after editors are ready
                    ApplicationManager.getApplication().invokeLater {
                        addInlineCommentsToEditor(editor)
                    }
                    
                    editor.addEditorMouseListener(object : EditorMouseListener {
                        override fun mouseClicked(e: EditorMouseEvent) {
                            if (e.mouseEvent.clickCount == 2) {
                                val selectionModel = editor.selectionModel
                                if (selectionModel.hasSelection()) {
                                    showAddCommentPopup(editor, e.mouseEvent.point)
                                }
                            }
                        }
                        
                        override fun mouseReleased(e: EditorMouseEvent) {
                            handlePopup(e)
                        }
                        
                        override fun mousePressed(e: EditorMouseEvent) {
                            handlePopup(e)
                        }
                        
                        private fun handlePopup(e: EditorMouseEvent) {
                            if (e.mouseEvent.isPopupTrigger) {
                                // Consuming the event prevents the default context menu
                                e.consume()
                                showContextMenu(editor, e)
                            }
                        }
                    })
                }
            }
        }, this)
    }
    
    /**
     * Show custom context menu for adding comments
     */
    private fun showContextMenu(editor: Editor, e: EditorMouseEvent) {
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("Add Comment", "Add a comment to this line", AllIcons.General.Add) {
                override fun actionPerformed(event: AnActionEvent) {
                    // Update selection if needed (e.g. if right click didn't select)
                    // Usually Editor handles this, but we can ensure caret is at point
                    val logicalPosition = editor.xyToLogicalPosition(e.mouseEvent.point)
                    editor.caretModel.moveToLogicalPosition(logicalPosition)
                    
                    // Select the line if no selection
                    if (!editor.selectionModel.hasSelection()) {
                        editor.selectionModel.selectLineAtCaret()
                    }
                    
                    showAddCommentPopup(editor, e.mouseEvent.point)
                }
            })
        }
        
        val popupMenu = ActionManager.getInstance().createActionPopupMenu("DiffCommentPopup", actionGroup)
        popupMenu.component.show(e.mouseEvent.component, e.mouseEvent.x, e.mouseEvent.y)
    }

    private fun showAddCommentPopup(editor: Editor, point: Point) {
        val selectionStart = editor.selectionModel.selectionStart
        val selectionEnd = editor.selectionModel.selectionEnd
        val isBase = editor.document == baseDocument
        
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(10)
        
        val textArea = JBTextArea(5, 30)
        textArea.lineWrap = true
        textArea.wrapStyleWord = true
        panel.add(JBScrollPane(textArea), BorderLayout.CENTER)
        
        val buttonsPanel = JPanel(FlowLayout(FlowLayout.RIGHT))
        val saveButton = JButton("Add Comment")
        val cancelButton = JButton("Cancel")
        
        buttonsPanel.add(cancelButton)
        buttonsPanel.add(saveButton)
        panel.add(buttonsPanel, BorderLayout.SOUTH)
        
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(panel, textArea)
            .setTitle("Add Comment")
            .setMovable(true)
            .setRequestFocus(true)
            .createPopup()
            
        cancelButton.addActionListener { popup.cancel() }
        
        saveButton.addActionListener {
            val commentText = textArea.text.trim()
            if (commentText.isNotEmpty()) {
                // Validate we have a proper file path - this is required for file-specific comments
                val currentPath = currentChange?.item?.path
                if (currentPath.isNullOrBlank()) {
                    JBPopupFactory.getInstance()
                        .createHtmlTextBalloonBuilder("Cannot add comment: No file is currently selected", com.intellij.openapi.ui.MessageType.ERROR, null)
                        .createBalloon()
                        .show(RelativePoint(saveButton.parent, Point(0, 0)), com.intellij.openapi.ui.popup.Balloon.Position.above)
                    return@addActionListener
                }
                
                // Calculate line numbers from selection offsets
                // Convert 0-based document line numbers to 1-based API line numbers
                val startLineNumber = editor.document.getLineNumber(selectionStart) + 1
                val endLineNumber = editor.document.getLineNumber(selectionEnd) + 1
                
                // Ensure valid line range (end >= start)
                val startLine = minOf(startLineNumber, endLineNumber)
                val endLine = maxOf(startLineNumber, endLineNumber)
                
                // Disable button to prevent double submission
                saveButton.isEnabled = false
                saveButton.text = "Adding..."
                
                // Create comment asynchronously
                ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        apiClient.createThread(
                            pullRequestId = pullRequestId, 
                            filePath = currentPath, 
                            content = commentText, 
                            startLine = startLine,
                            endLine = endLine,
                            isLeft = isBase,
                            projectName = externalProjectName,
                            repositoryId = externalRepositoryId,
                            changeTrackingId = currentChange?.changeTrackingId
                        )
                        
                        logger.info("Comment added to $currentPath lines $startLine-$endLine (isLeft: $isBase)")
                        
                        // Notify success and close popup, then refresh comments
                        ApplicationManager.getApplication().invokeLater {
                             popup.cancel()
                             // Refresh inline comments to show the new one
                             refreshInlineComments()
                        }
                    } catch (ex: Exception) {
                        logger.error("Failed to add comment", ex)
                        ApplicationManager.getApplication().invokeLater {
                            saveButton.isEnabled = true
                            saveButton.text = "Add Comment"
                            JBPopupFactory.getInstance()
                                .createHtmlTextBalloonBuilder("Failed to add comment: ${ex.message}", com.intellij.openapi.ui.MessageType.ERROR, null)
                                .createBalloon()
                                .show(RelativePoint(saveButton.parent, Point(0, 0)), com.intellij.openapi.ui.popup.Balloon.Position.above)
                        }
                    }
                }
            }
        }
        
        // Show at the specified point relative to the editor component
        val component = editor.contentComponent
        popup.show(RelativePoint(component, point))
    }

    /**
     * Load and display diff for a file change
     */
    fun loadDiff(change: PullRequestChange) {
        val filePath = change.item?.path ?: run {
            showError("Invalid file path")
            return
        }
        
        // Clear previous diff panel FIRST (this resets currentChange)
        clearDiff()
        
        // Set currentChange AFTER clearDiff() to preserve it
        currentChange = change
        
        // Show loading state
        showLoading(filePath)
        
        // Load diff content in background
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val (oldContent, newContent) = fetchFileContents(change)
                
                // Also fetch comment threads for this file
                val threads = fetchCommentThreads(filePath)
                cachedThreads = threads
                
                // Update UI on EDT
                ApplicationManager.getApplication().invokeLater {
                    displayDiff(filePath, oldContent, newContent, change.changeType ?: "edit")
                }
            } catch (e: Exception) {
                logger.error("Failed to load diff for file: $filePath", e)
                ApplicationManager.getApplication().invokeLater {
                    showError("Failed to load diff: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Fetch comment threads for a specific file
     */
    private fun fetchCommentThreads(filePath: String): List<CommentThread> {
        return try {
            val allThreads = apiClient.getCommentThreads(pullRequestId, externalProjectName, externalRepositoryId)
            // Filter threads for this specific file
            allThreads.filter { thread ->
                thread.getFilePath() == filePath && thread.isDeleted != true
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch comment threads for $filePath: ${e.message}")
            emptyList()
        }
    }

    /**
     * Fetch file contents (old and new versions)
     */
    private fun fetchFileContents(change: PullRequestChange): Pair<String, String> {
        val filePath = change.item?.path ?: ""
        val changeType = change.changeType?.lowercase() ?: "edit"
        
        logger.info("fetchFileContents: filePath=$filePath, externalProject=$externalProjectName, externalRepo=$externalRepositoryId")
        
        // Get PR to access commit IDs (cache it)
        if (cachedPullRequest == null) {
            logger.info("Fetching PR #$pullRequestId with project=$externalProjectName, repo=$externalRepositoryId")
            cachedPullRequest = apiClient.getPullRequest(pullRequestId, externalProjectName, externalRepositoryId)
        }
        val pr = cachedPullRequest!!
        
        val sourceCommit = pr.lastMergeSourceCommit?.commitId
        val targetCommit = pr.lastMergeTargetCommit?.commitId
        
        return when (changeType) {
            "add" -> {
                // New file - no old content
                val newContent = if (sourceCommit != null) {
                    try {
                        apiClient.getFileContent(sourceCommit, filePath, externalProjectName, externalRepositoryId)
                    } catch (e: Exception) {
                        logger.info("Failed to get new content: ${e.message}")
                        ""
                    }
                } else ""
                "" to newContent
            }
            "delete" -> {
                // Deleted file - no new content
                val oldContent = if (targetCommit != null) {
                    try {
                        apiClient.getFileContent(targetCommit, filePath, externalProjectName, externalRepositoryId)
                    } catch (e: Exception) {
                        logger.info("Failed to get old content: ${e.message}")
                        ""
                    }
                } else ""
                oldContent to ""
            }
            "edit", "rename" -> {
                // Modified file - fetch both versions
                val oldPath = change.originalPath ?: filePath
                
                val oldContent = if (targetCommit != null) {
                    try {
                        apiClient.getFileContent(targetCommit, oldPath, externalProjectName, externalRepositoryId)
                    } catch (e: Exception) {
                        logger.info("File is new (doesn't exist in base): ${e.message}")
                        ""
                    }
                } else ""
                
                val newContent = if (sourceCommit != null) {
                    try {
                        apiClient.getFileContent(sourceCommit, filePath, externalProjectName, externalRepositoryId)
                    } catch (e: Exception) {
                        logger.info("Failed to get new content: ${e.message}")
                        ""
                    }
                } else ""
                
                oldContent to newContent
            }
            else -> {
                logger.warn("Unknown change type: $changeType")
                "" to ""
            }
        }
    }

    /**
     * Display the diff using JetBrains Diff API
     */
    private fun displayDiff(filePath: String, oldContent: String, newContent: String, changeType: String) {
        removeAll()
        
        // Determine file type for syntax highlighting
        val fileName = filePath.substringAfterLast('/')
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        
        // Create diff contents
        val content1 = diffContentFactory.create(project, oldContent, fileType)
        val content2 = diffContentFactory.create(project, newContent, fileType)
        
        // Store documents for editor matching
        baseDocument = (content1 as? DocumentContent)?.document
        changesDocument = (content2 as? DocumentContent)?.document
        
        // Get branch names for titles
        val pr = cachedPullRequest
        val targetBranch = pr?.targetRefName?.substringAfterLast('/') ?: "Base"
        val sourceBranch = pr?.sourceRefName?.substringAfterLast('/') ?: "Changes"
        
        // Create diff request with appropriate titles
        val diffRequest = SimpleDiffRequest(
            "PR #$pullRequestId: $fileName",
            content1,
            content2,
            "Base ($targetBranch)",
            "Changes ($sourceBranch)"
        )
        
        // Create diff panel
        val diffManager = DiffManager.getInstance()
        currentDiffPanel = diffManager.createRequestPanel(project, this, null).apply {
            setRequest(diffRequest)
        }
        
        add(currentDiffPanel!!.component, BorderLayout.CENTER)
        revalidate()
        repaint()
        
        logger.info("Displayed diff for: $filePath (type: $changeType)")
    }

    /**
     * Show loading state
     */
    private fun showLoading(fileName: String) {
        removeAll()
        val loadingLabel = JBLabel("Loading diff for $fileName...").apply {
            horizontalAlignment = JBLabel.CENTER
            border = JBUI.Borders.empty(20)
        }
        add(loadingLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    /**
     * Show error message
     */
    private fun showError(message: String) {
        removeAll()
        val errorLabel = JBLabel("❌ $message").apply {
            horizontalAlignment = JBLabel.CENTER
            border = JBUI.Borders.empty(20)
        }
        add(errorLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    /**
     * Clear the current diff
     */
    fun clearDiff() {
        clearInlays()
        baseEditor = null
        changesEditor = null
        cachedThreads = emptyList()
        
        currentDiffPanel?.let { panel ->
            Disposer.dispose(panel)
            currentDiffPanel = null
        }
        currentChange = null
        
        removeAll()
        add(placeholderLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    /**
     * Get the current file change being displayed
     */
    fun getCurrentChange(): PullRequestChange? = currentChange

    /**
     * Cleanup resources (implementation of Disposable interface)
     */
    override fun dispose() {
        clearInlays()
        clearDiff()
    }
    
    /**
     * Add inline comments to a specific editor
     */
    private fun addInlineCommentsToEditor(editor: Editor) {
        val isBase = editor.document == baseDocument
        val filePath = currentChange?.item?.path ?: return
        
        // Filter threads for this side of the diff
        val relevantThreads = cachedThreads.filter { thread ->
            val ctx = thread.pullRequestThreadContext ?: thread.threadContext
            val isLeftSide = ctx?.leftFileStart != null && ctx.rightFileStart == null
            isLeftSide == isBase
        }
        
        logger.info("Adding ${relevantThreads.size} inline comments to ${if (isBase) "base" else "changes"} editor for $filePath")
        
        relevantThreads.forEach { thread ->
            addGutterIconForThread(editor, thread)
        }
    }
    
    /**
     * Add a gutter icon and highlighter for a comment thread
     */
    private fun addGutterIconForThread(editor: Editor, thread: CommentThread) {
        val ctx = thread.pullRequestThreadContext ?: thread.threadContext ?: return
        val startLine = ctx.rightFileStart?.line ?: ctx.leftFileStart?.line ?: return
        val endLine = ctx.rightFileEnd?.line ?: ctx.leftFileEnd?.line ?: startLine
        
        // Convert to 0-based line numbers
        val startLine0 = (startLine - 1).coerceIn(0, editor.document.lineCount - 1)
        val endLine0 = (endLine - 1).coerceIn(0, editor.document.lineCount - 1)
        
        if (startLine0 >= editor.document.lineCount) {
            logger.warn("Line $startLine out of bounds for thread ${thread.id}")
            return
        }
        
        val startOffset = editor.document.getLineStartOffset(startLine0)
        val endOffset = editor.document.getLineEndOffset(endLine0)
        
        val markupModel = editor.markupModel
        
        // Create line highlighter with background color
        val highlightColor = if (thread.isActive()) {
            com.intellij.ui.JBColor(java.awt.Color(255, 248, 200, 60), java.awt.Color(80, 70, 30, 60))
        } else {
            com.intellij.ui.JBColor(java.awt.Color(200, 255, 200, 60), java.awt.Color(30, 60, 30, 60))
        }
        
        val textAttributes = com.intellij.openapi.editor.markup.TextAttributes().apply {
            backgroundColor = highlightColor
        }
        
        val highlighter = markupModel.addRangeHighlighter(
            startOffset,
            endOffset,
            com.intellij.openapi.editor.markup.HighlighterLayer.SELECTION - 1,
            textAttributes,
            com.intellij.openapi.editor.markup.HighlighterTargetArea.LINES_IN_RANGE
        )
        
        // Add gutter icon
        val icon = if (thread.isActive()) AllIcons.General.Balloon else AllIcons.General.InspectionsOK
        val commentCount = thread.comments?.size ?: 0
        val authorName = thread.comments?.firstOrNull()?.author?.displayName ?: "Unknown"
        val tooltip = "$authorName ($commentCount ${if (commentCount == 1) "comment" else "comments"})"
        
        highlighter.gutterIconRenderer = object : com.intellij.openapi.editor.markup.GutterIconRenderer() {
            override fun getIcon() = icon
            override fun getTooltipText() = tooltip
            override fun isNavigateAction() = true
            override fun getAlignment() = Alignment.LEFT
            
            override fun getClickAction(): AnAction {
                return object : AnAction() {
                    override fun actionPerformed(e: AnActionEvent) {
                        showCommentPopup(editor, thread, startLine0)
                    }
                }
            }
            
            override fun equals(other: Any?): Boolean {
                if (other !is com.intellij.openapi.editor.markup.GutterIconRenderer) return false
                return thread.id == (other as? com.intellij.openapi.editor.markup.GutterIconRenderer)?.hashCode()
            }
            
            override fun hashCode() = thread.id ?: 0
        }
        
        // Track for cleanup
        activeHighlighters.add(highlighter)
        
        logger.info("Added gutter icon for thread ${thread.id} at lines $startLine-$endLine")
    }
    
    /**
     * Show interactive comment popup for a thread
     */
    private fun showCommentPopup(editor: Editor, thread: CommentThread, lineIndex: Int) {
        val commentComponent = InlineCommentComponent(
            thread = thread,
            apiClient = apiClient,
            pullRequestId = pullRequestId,
            projectName = externalProjectName,
            repositoryId = externalRepositoryId,
            onStatusChanged = { refreshInlineComments() },
            onReplyAdded = { refreshInlineComments() }
        )
        
        // Set preferred size
        commentComponent.preferredSize = Dimension(450, commentComponent.preferredSize.height.coerceAtLeast(120))
        
        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(commentComponent, commentComponent)
            .setTitle("Comment Thread #${thread.id}")
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(false)
            .createPopup()
        
        // Calculate position - show below the line
        val lineY = editor.logicalPositionToXY(com.intellij.openapi.editor.LogicalPosition(lineIndex + 1, 0))
        val editorLocation = editor.contentComponent.locationOnScreen
        val point = Point(editorLocation.x + 50, editorLocation.y + lineY.y)
        
        popup.showInScreenCoordinates(editor.contentComponent, point)
    }
    
    /**
     * Refresh inline comments (reload from API and update display)
     */
    fun refreshInlineComments() {
        val filePath = currentChange?.item?.path ?: return
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val threads = fetchCommentThreads(filePath)
                cachedThreads = threads
                
                ApplicationManager.getApplication().invokeLater {
                    clearInlays()
                    baseEditor?.let { addInlineCommentsToEditor(it) }
                    changesEditor?.let { addInlineCommentsToEditor(it) }
                }
            } catch (e: Exception) {
                logger.error("Failed to refresh comments", e)
            }
        }
    }
    
    /**
     * Clear all active inlays and highlighters
     */
    private fun clearInlays() {
        activeInlays.forEach { inlay ->
            if (inlay.isValid) {
                Disposer.dispose(inlay)
            }
        }
        activeInlays.clear()
        
        // Clear highlighters - need to remove from markup model
        activeHighlighters.forEach { highlighter ->
            if (highlighter.isValid) {
                highlighter.dispose()
            }
        }
        activeHighlighters.clear()
    }
}
