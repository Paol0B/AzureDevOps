package paol0b.azuredevops.toolwindow.review

import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.tools.util.base.DiffViewerBase
import com.intellij.diff.tools.util.base.DiffViewerListener
import com.intellij.diff.tools.util.side.TwosideTextDiffViewer
import com.intellij.diff.util.Side
import com.intellij.diff.util.DiffUserDataKeys
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.SelectionListener
import com.intellij.openapi.editor.event.SelectionEvent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import paol0b.azuredevops.model.FileCommentRange
import com.intellij.openapi.editor.event.EditorMouseEvent
import com.intellij.openapi.editor.event.EditorMouseMotionListener
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.editor.markup.HighlighterLayer
import com.intellij.openapi.editor.markup.HighlighterTargetArea
import com.intellij.openapi.editor.markup.RangeHighlighter
import com.intellij.openapi.editor.markup.TextAttributes
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.util.Disposer
import com.intellij.ui.JBColor
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.PullRequestChange
import paol0b.azuredevops.model.diffSideTitles
import paol0b.azuredevops.model.displayChangeLabel
import paol0b.azuredevops.model.effectivePath
import paol0b.azuredevops.model.hasChangeType
import paol0b.azuredevops.model.previousPath
import paol0b.azuredevops.model.primaryChangeType
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.PullRequestDiffContents
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Point
import java.awt.FlowLayout
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import javax.swing.JButton
import javax.swing.JPanel

/**
 * GitHub-style diff viewer panel with inline comment support.
 *
 * Interaction model (matches the GitHub JetBrains plugin):
 *  - Hover over any line gutter → a "+" icon appears
 *  - Click "+"  → inline comment editor appears below the line
 *  - Existing comments  → persistent 💬 gutter icon; click to expand/collapse the thread
 *
 * Uses the JetBrains Diff API for professional side-by-side diff rendering.
 */
class DiffViewerPanel(
    private val project: Project,
    private val pullRequestId: Int,
    private val externalProjectName: String? = null,
    private val externalRepositoryId: String? = null
) : JPanel(BorderLayout()), Disposable {

    private val logger = Logger.getInstance(DiffViewerPanel::class.java)
    private val apiClient = AzureDevOpsApiClient.getInstance(project)

    private var disposed = false
    private val diffRequests = LatestRequest<PullRequestChange>()
    private val commentRequests = LatestRequest<LatestRequest.Ticket<PullRequestChange>>()
    private var displayedRequest: LatestRequest.Ticket<PullRequestChange>? = null
    private val activePopups = mutableSetOf<JBPopup>()
    private var currentDiffPanel: DiffRequestPanel? = null
    private var currentChange: PullRequestChange? = null
    private var cachedPullRequest: paol0b.azuredevops.model.PullRequest? = null

    private data class CommentEditor(
        val viewer: DiffViewerBase,
        val location: (Int) -> DiffCommentLocation?,
        val displayLine: (Boolean, Int) -> Int?,
        val fileLine: (Int, Boolean) -> Int?
    )
    private val commentEditors = mutableMapOf<Editor, CommentEditor>()
    private var lastCommentEditor: Editor? = null
    private var selectionPopup: JBPopup? = null
    private var commentViewerReady = false
    private val addCommentButton = JButton("Add inline comment").apply {
        isEnabled = false
        toolTipText = "Comment on the line at the cursor; or hover over a line and click +"
        addActionListener { addCommentAtCaret() }
    }

    // Comment threads cache
    private var cachedThreads: List<CommentThread> = emptyList()
    @Volatile private var currentUserId: String? = null

    // Highlighters & inlays for cleanup
    private val activeHighlighters = mutableListOf<RangeHighlighter>()
    private val activeInlays = mutableListOf<com.intellij.openapi.editor.Inlay<*>>()

    private val hoverHighlighters = mutableMapOf<Editor, RangeHighlighter>()

    private val placeholderLabel = JBLabel("Select a file to view diff").apply {
        horizontalAlignment = JBLabel.CENTER
        border = JBUI.Borders.empty(20)
    }

    init {
        minimumSize = Dimension(400, 0)
        preferredSize = Dimension(600, 0)
        add(placeholderLabel, BorderLayout.CENTER)

        logger.info("DiffViewerPanel created: pullRequestId=$pullRequestId, externalProject=$externalProjectName, externalRepo=$externalRepositoryId")

    }

    private fun bindCommentEditors(viewer: FrameDiffTool.DiffViewer) {
        val request = displayedRequest ?: return
        if (!diffRequests.isCurrent(request) || viewer !is DiffViewerBase) return
        clearCommentEditors()
        when (viewer) {
            is UnifiedDiffViewer -> registerCommentEditor(viewer.editor, CommentEditor(
                viewer,
                { line -> DiffCommentLocation.fromUnifiedLines(
                    viewer.transferLineFromOnesideStrict(Side.LEFT, line),
                    viewer.transferLineFromOnesideStrict(Side.RIGHT, line)
                ) },
                { left, line -> viewer.transferLineToOnesideStrict(if (left) Side.LEFT else Side.RIGHT, line)
                    .takeIf { it >= 0 } },
                { line, left -> viewer.transferLineFromOnesideStrict(if (left) Side.LEFT else Side.RIGHT, line).takeIf { it >= 0 } }
            ))
            is TwosideTextDiffViewer -> {
                for (side in listOf(Side.LEFT, Side.RIGHT)) {
                    registerCommentEditor(viewer.getEditor(side), CommentEditor(
                        viewer,
                        { line -> DiffCommentLocation(side == Side.LEFT, line + 1) },
                        { left, line -> line.takeIf { left == (side == Side.LEFT) } },
                        { line, left -> line.takeIf { left == (side == Side.LEFT) } }
                    ))
                }
            }
        }
        viewer.addListener(object : DiffViewerListener() {
            override fun onBeforeRediff() {
                if (commentEditors.values.none { it.viewer === viewer }) return
                commentEditors.keys.forEach { AddedFileDiffAppearance.apply(it, currentChange) }
                commentViewerReady = false
                hoverHighlighters.values.forEach { if (it.isValid) it.dispose() }
                hoverHighlighters.clear()
                clearInlays()
                activePopups.toList().forEach { it.cancel() }
                addCommentButton.isEnabled = false
            }
            override fun onAfterRediff() {
                if (!diffRequests.isCurrent(request) || commentEditors.values.none { it.viewer === viewer }) return
                commentViewerReady = true
                clearInlays()
                commentEditors.keys.toList().forEach { addInlineCommentsToEditor(it) }
                addCommentButton.isEnabled = commentEditors.isNotEmpty()
            }
            override fun onDispose() {
                if (commentEditors.values.any { it.viewer === viewer }) clearCommentEditors()
            }
        })
    }

    private fun registerCommentEditor(editor: Editor, binding: CommentEditor) {
        AddedFileDiffAppearance.apply(editor, currentChange)
        commentEditors[editor] = binding
        editor.putUserData(PrSelectedTextCommentAction.CAN_COMMENT) { selectedCommentRange(editor) != null }
        editor.putUserData(PrSelectedTextCommentAction.COMMENT) { showSelectedCommentEditor(editor) }
        editor.contentComponent.addFocusListener(object : FocusAdapter() {
            override fun focusGained(e: FocusEvent) { if (isCurrentEditor(editor)) lastCommentEditor = editor }
        })
        editor.selectionModel.addSelectionListener(object : SelectionListener {
            override fun selectionChanged(e: SelectionEvent) {
                if (!isCurrentEditor(editor)) return
                selectionPopup?.cancel()
                selectionPopup = null
                lastCommentEditor = editor
                val range = selectedCommentRange(editor) ?: return
                ApplicationManager.getApplication().invokeLater {
                    if (isCurrentEditor(editor) && editor.contentComponent.isShowing && selectedCommentRange(editor) == range) {
                        showSelectionCommentControl(editor, range)
                    }
                }
            }
        })
        editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
            override fun mouseMoved(e: EditorMouseEvent) {
                if (!isCurrentEditor(editor)) return
                val line = editor.xyToLogicalPosition(e.mouseEvent.point).line
                if (line !in 0 until editor.document.lineCount || binding.location(line) == null) {
                    removeHoverHighlighter(editor)
                    return
                }
                showHoverAddIcon(editor, line)
            }
        })
    }

    private fun addCommentAtCaret(preferredEditor: Editor? = null) {
        val editor = preferredEditor?.takeIf { isCurrentEditor(it) }
            ?: lastCommentEditor?.takeIf { isCurrentEditor(it) }
            ?: commentEditors.keys.lastOrNull { isCurrentEditor(it) } ?: return
        if (editor.selectionModel.hasSelection()) showSelectedCommentEditor(editor)
        else showInlineCommentEditor(editor, editor.caretModel.logicalPosition.line)
    }

    private fun selectedCommentRange(editor: Editor): FileCommentRange? {
        if (!isCurrentEditor(editor) || !editor.selectionModel.hasSelection() || editor.caretModel.caretCount > 1) return null
        val binding = commentEditors[editor] ?: return null
        return DiffCommentSelection.resolve(editor.document.immutableCharSequence.toString(),
            editor.selectionModel.selectionStart, editor.selectionModel.selectionEnd, binding.fileLine)
    }

    private fun showSelectedCommentEditor(editor: Editor) {
        val range = selectedCommentRange(editor) ?: return
        val row = editor.document.getLineNumber(editor.selectionModel.selectionStart)
        selectionPopup?.cancel()
        selectionPopup = null
        showInlineCommentEditor(editor, row, range)
    }

    private fun showSelectionCommentControl(editor: Editor, range: FileCommentRange) {
        selectionPopup?.cancel()
        val button = JButton("Comment on selected text", AllIcons.General.Balloon).apply {
            isFocusable = false
            addActionListener {
                if (selectedCommentRange(editor) == range) showSelectedCommentEditor(editor)
            }
        }
        val popup = JBPopupFactory.getInstance().createComponentPopupBuilder(button, null)
            .setRequestFocus(false).setCancelOnClickOutside(true).setCancelOnOtherWindowOpen(false).createPopup()
        selectionPopup = popup
        trackPopup(popup)
        val end = editor.offsetToXY(editor.selectionModel.selectionEnd)
        popup.show(RelativePoint(editor.contentComponent, Point(end.x, end.y + editor.lineHeight)))
    }

    private fun clearCommentEditors() {
        clearInlays()
        hoverHighlighters.values.forEach { if (it.isValid) it.dispose() }
        hoverHighlighters.clear()
        commentEditors.keys.forEach {
            it.putUserData(PrSelectedTextCommentAction.CAN_COMMENT, null)
            it.putUserData(PrSelectedTextCommentAction.COMMENT, null)
        }
        commentEditors.clear()
        lastCommentEditor = null
        commentViewerReady = false
        addCommentButton.isEnabled = false
        activePopups.toList().forEach { it.cancel() }
    }

    // ==================================================================
    //  Hover "+" gutter icon
    // ==================================================================

    /**
     * Show or move the "+" add-comment gutter icon on the hovered line.
     */
    private fun showHoverAddIcon(editor: Editor, line: Int) {
        val existing = hoverHighlighters[editor]

        // Already showing on this line
        if (existing != null && existing.isValid) {
            val existingLine = editor.document.getLineNumber(existing.startOffset)
            if (existingLine == line) return
        }

        // Remove old
        removeHoverHighlighter(editor)

        if (line >= editor.document.lineCount) return
        val offset = editor.document.getLineStartOffset(line)

        val highlighter = editor.markupModel.addRangeHighlighter(
            offset, offset,
            HighlighterLayer.LAST + 100,
            null,
            HighlighterTargetArea.LINES_IN_RANGE
        )

        highlighter.gutterIconRenderer = AddCommentGutterIconRenderer(line) { clickedLine ->
            removeHoverHighlighter(editor)
            showInlineCommentEditor(editor, clickedLine)
        }

        hoverHighlighters[editor] = highlighter
    }

    private fun removeHoverHighlighter(editor: Editor) {
        hoverHighlighters.remove(editor)?.let { if (it.isValid) it.dispose() }
    }

    // ==================================================================
    //  Inline comment editor (appears on "+" click)
    // ==================================================================

    /**
     * Show the GitHub-style "Add Review Comment" editor below the specified line.
     */
    private fun showInlineCommentEditor(editor: Editor, line0based: Int, selectedRange: FileCommentRange? = null) {
        if (!isCurrentEditor(editor)) return
        val request = displayedRequest ?: return
        val change = request.value
        val filePath = change.effectivePath().takeIf { it.isNotBlank() } ?: return

        val location = commentEditors[editor]?.location?.invoke(line0based) ?: return

        // We need a reference to the popup so callbacks can dismiss it.
        // Use a holder so the lambda can capture it before the popup is built.
        var popupRef: com.intellij.openapi.ui.popup.JBPopup? = null

        val component = InlineCommentEditorComponent(
            project = project,
            apiClient = apiClient,
            pullRequestId = pullRequestId,
            filePath = filePath,
            range = selectedRange ?: FileCommentRange(location.isLeftSide, location.lineNumber),
            projectName = externalProjectName,
            repositoryId = externalRepositoryId,
            changeTrackingId = change.changeTrackingId,
            onCommentAdded = {
                popupRef?.cancel()
                if (diffRequests.isCurrent(request)) refreshInlineComments()
            },
            onCancel = { popupRef?.cancel() }
        )
        component.preferredSize = Dimension(480, component.preferredSize.height.coerceAtLeast(150))

        val popup = component.createPopup()
        popupRef = popup
        trackPopup(popup)

        // Position below the target line
        val lineY = editor.logicalPositionToXY(LogicalPosition(line0based + 1, 0))
        popup.show(RelativePoint(editor.contentComponent, Point(40, lineY.y)))
    }

    // ==================================================================
    //  Persistent comment gutter icons (existing threads)
    // ==================================================================

    private fun addInlineCommentsToEditor(editor: Editor) {
        if (!isCurrentEditor(editor)) return
        val binding = commentEditors[editor] ?: return
        val filePath = currentChange?.effectivePath()?.takeIf { it.isNotBlank() } ?: return

        val relevantThreads = cachedThreads.filter { thread ->
            if (thread.comments.orEmpty().none { it.isDeleted != true && it.commentType != "system" }) return@filter false
            val ctx = thread.threadContext
            val isLeftSide = ctx?.leftFileStart != null && ctx.rightFileStart == null
            val line = if (isLeftSide) ctx.leftFileStart.line else ctx?.rightFileStart?.line
            line != null && binding.displayLine(isLeftSide, line - 1) != null
        }

        logger.info("Adding ${relevantThreads.size} inline comments for $filePath")

        relevantThreads.forEach { thread ->
            addGutterIconForThread(editor, thread)
        }
    }

    /**
     * Add a persistent comment bubble gutter icon + line highlight for an existing thread.
     */
    private fun addGutterIconForThread(editor: Editor, thread: CommentThread) {
        val ctx = thread.threadContext ?: return
        val startLine = ctx.rightFileStart?.line ?: ctx.leftFileStart?.line ?: return
        val endLine = ctx.rightFileEnd?.line ?: ctx.leftFileEnd?.line ?: startLine

        val isLeftSide = ctx.leftFileStart != null && ctx.rightFileStart == null
        val binding = commentEditors[editor] ?: return
        val startLine0 = binding.displayLine(isLeftSide, startLine - 1) ?: return
        if (startLine0 < 0 || startLine0 >= editor.document.lineCount) {
            logger.warn("Line $startLine out of bounds for thread ${thread.id}")
            return
        }

        val endLine0 = (binding.displayLine(isLeftSide, endLine - 1) ?: startLine0).coerceIn(startLine0, editor.document.lineCount - 1)
        val startOffset = editor.document.getLineStartOffset(startLine0)
        val endOffset = editor.document.getLineEndOffset(endLine0)

        // Subtle line highlight
        val highlightColor = if (thread.isActive()) {
            JBColor(Color(255, 248, 200, 50), Color(80, 70, 30, 50))
        } else {
            JBColor(Color(200, 255, 200, 50), Color(30, 60, 30, 50))
        }

        val highlighter = editor.markupModel.addRangeHighlighter(
            startOffset, endOffset,
            HighlighterLayer.SELECTION - 1,
            TextAttributes().apply { backgroundColor = highlightColor },
            HighlighterTargetArea.LINES_IN_RANGE
        )

        // Comment bubble gutter icon
        val icon = if (thread.isActive()) AllIcons.General.Balloon else AllIcons.General.InspectionsOK
        val visibleComments = thread.comments.orEmpty().filter { it.isDeleted != true && it.commentType != "system" }
        val commentCount = visibleComments.size
        val authorName = visibleComments.firstOrNull()?.author?.displayName ?: "Unknown"

        highlighter.gutterIconRenderer = object : GutterIconRenderer() {
            override fun getIcon() = icon
            override fun getTooltipText() = "$authorName ($commentCount ${if (commentCount == 1) "comment" else "comments"}) — click to view"
            override fun isNavigateAction() = true
            override fun getAlignment() = Alignment.LEFT

            override fun getClickAction(): AnAction {
                return object : AnAction() {
                    override fun actionPerformed(e: AnActionEvent) {
                        showCommentThreadPopup(editor, thread, startLine0)
                    }
                }
            }

            override fun equals(other: Any?): Boolean {
                if (other !is GutterIconRenderer) return false
                return this.hashCode() == other.hashCode()
            }

            override fun hashCode() = 31 * (thread.id ?: 0) + "comment_gutter".hashCode()
        }

        activeHighlighters.add(highlighter)
        logger.info("Added comment gutter icon for thread ${thread.id} at lines $startLine-$endLine")
    }

    /**
     * Show a comment thread in a lightweight popup positioned below the line.
     * Styled borderless to feel embedded, matching GitHub plugin behavior.
     */
    private fun showCommentThreadPopup(editor: Editor, thread: CommentThread, lineIndex: Int) {
        if (!isCurrentEditor(editor)) return
        val request = displayedRequest ?: return
        var popupRef: JBPopup? = null
        val refreshThread: () -> Unit = {
            popupRef?.cancel()
            if (diffRequests.isCurrent(request)) refreshInlineComments()
        }
        val commentComponent = InlineCommentComponent(
            project = project,
            thread = thread,
            apiClient = apiClient,
            pullRequestId = pullRequestId,
            projectName = externalProjectName,
            repositoryId = externalRepositoryId,
            currentUserId = currentUserId,
            onStatusChanged = refreshThread,
            onReplyAdded = refreshThread,
            onCommentDeleted = refreshThread
        )

        commentComponent.preferredSize = Dimension(
            480,
            commentComponent.preferredSize.height.coerceAtLeast(120)
        )

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(commentComponent, commentComponent)
            .setMovable(true)
            .setResizable(true)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(false)
            .createPopup()

        popupRef = popup
        trackPopup(popup)
        val lineY = editor.logicalPositionToXY(LogicalPosition(lineIndex + 1, 0))
        popup.show(RelativePoint(editor.contentComponent, Point(40, lineY.y)))
    }

    // ==================================================================
    //  Load / display diff
    // ==================================================================

    fun loadDiff(change: PullRequestChange) {
        if (disposed || project.isDisposed) return
        clearDiff()
        val filePath = change.effectivePath().takeIf { it.isNotBlank() } ?: run {
            showError("Invalid file path")
            return
        }
        val request = diffRequests.start(change) ?: return
        val knownPullRequest = cachedPullRequest
        currentChange = change
        showLoading(filePath)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val pr = knownPullRequest ?: apiClient.getPullRequest(pullRequestId, externalProjectName, externalRepositoryId)
                val (oldContent, newContent) = fetchFileContents(change, pr)
                if (!diffRequests.isCurrent(request)) return@executeOnPooledThread
                val threads = fetchCommentThreads(filePath)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    diffRequests.applyIfCurrent(request) { current ->
                        cachedPullRequest = pr
                        cachedThreads = threads
                        currentChange = current
                        displayedRequest = request
                        displayDiff(filePath, oldContent, newContent, current.primaryChangeType())
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to load diff for file: $filePath", e)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed) return@invokeLater
                    diffRequests.applyIfCurrent(request) { showError("Failed to load diff: ${e.message}") }
                }
            }
        }
    }

    private fun fetchCommentThreads(filePath: String): List<CommentThread> {
        return try {
            if (currentUserId == null) {
                currentUserId = apiClient.getCurrentUserIdCached()
            }
            val allThreads = apiClient.getCommentThreads(pullRequestId, externalProjectName, externalRepositoryId)
            allThreads.filter { it.getFilePath() == filePath && it.isDeleted != true }
        } catch (e: Exception) {
            logger.warn("Failed to fetch comment threads for $filePath: ${e.message}")
            emptyList()
        }
    }

    private fun fetchFileContents(
        change: PullRequestChange,
        pr: paol0b.azuredevops.model.PullRequest
    ): Pair<String, String> = PullRequestDiffContents.load(
        change, change.effectivePath(), pr.lastMergeSourceCommit?.commitId, pr.lastMergeTargetCommit?.commitId
    ) { commit, path -> apiClient.getFileContent(commit, path, externalProjectName, externalRepositoryId) }

    private fun displayDiff(filePath: String, oldContent: String, newContent: String, changeType: String) {
        removeAll()

        val fileName = filePath.substringAfterLast('/')
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)

        val pr = cachedPullRequest
        val localFile = PrDiffNavigation.localFile(project, pr, filePath)
        val content1 = PrDiffNavigation.createContent(project, oldContent, fileType, localFile)
        val content2 = PrDiffNavigation.createContent(project, newContent, fileType, localFile)
        val targetBranch = pr?.targetRefName?.substringAfterLast('/') ?: "Base"
        val sourceBranch = pr?.sourceRefName?.substringAfterLast('/') ?: "Changes"
        val (leftTitle, rightTitle) = currentChange?.diffSideTitles(targetBranch, sourceBranch)
            ?: ("Base ($targetBranch)" to "Changes ($sourceBranch)")
        val diffTitleSuffix = currentChange?.displayChangeLabel()?.let { " [$it]" }.orEmpty()

        val diffRequest = SimpleDiffRequest(
            "PR #$pullRequestId: $fileName$diffTitleSuffix",
            content1, content2, leftTitle, rightTitle
        )

        val requestTicket = displayedRequest ?: return
        diffRequest.putUserData(PrDiffCommentExtension.BIND_COMMENTS) { viewer ->
            if (diffRequests.isCurrent(requestTicket)) bindCommentEditors(viewer)
        }
        diffRequest.putUserData(DiffUserDataKeys.CONTEXT_ACTIONS, listOf(object : AnAction("Add inline comment", "Comment on this code line", AllIcons.General.Add) {
            override fun actionPerformed(e: AnActionEvent) { addCommentAtCaret(e.getData(CommonDataKeys.EDITOR)) }
        }, object : AnAction("Comment on selected text", "Comment on the selected code range", AllIcons.General.Balloon) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT
            override fun update(e: AnActionEvent) {
                val editor = e.getData(CommonDataKeys.EDITOR) ?: lastCommentEditor
                e.presentation.isEnabledAndVisible = editor != null && selectedCommentRange(editor) != null
            }
            override fun actionPerformed(e: AnActionEvent) {
                val editor = e.getData(CommonDataKeys.EDITOR) ?: lastCommentEditor ?: return
                showSelectedCommentEditor(editor)
            }
        }))

        val diffManager = DiffManager.getInstance()
        currentDiffPanel = diffManager.createRequestPanel(project, this, null).apply {
            setRequest(diffRequest)
        }

        add(JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            add(addCommentButton)
            add(JBLabel("Select text to comment, or hover over the gutter and click +"))
        }, BorderLayout.NORTH)
        add(currentDiffPanel!!.component, BorderLayout.CENTER)
        revalidate()
        repaint()

        logger.info("Displayed diff for: $filePath (type: $changeType)")
    }

    // ==================================================================
    //  Refresh / clear
    // ==================================================================

    fun refreshInlineComments() {
        val diffRequest = displayedRequest?.takeIf { diffRequests.isCurrent(it) } ?: return
        val filePath = diffRequest.value.effectivePath().takeIf { it.isNotBlank() } ?: return
        val request = commentRequests.start(diffRequest) ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val threads = fetchCommentThreads(filePath)
                ApplicationManager.getApplication().invokeLater {
                    if (project.isDisposed || !diffRequests.isCurrent(diffRequest)) return@invokeLater
                    commentRequests.applyIfCurrent(request) {
                        cachedThreads = threads
                        clearInlays()
                        commentEditors.keys.toList().forEach { addInlineCommentsToEditor(it) }
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to refresh comments", e)
            }
        }
    }

    private fun isCurrentEditor(editor: Editor): Boolean = !disposed && commentViewerReady && !editor.isDisposed &&
        displayedRequest?.let { diffRequests.isCurrent(it) } == true &&
        commentEditors[editor]?.viewer?.isDisposed == false

    private fun trackPopup(popup: JBPopup) {
        activePopups.add(popup)
        popup.addListener(object : JBPopupListener {
            override fun onClosed(event: LightweightWindowEvent) { activePopups.remove(popup) }
        })
    }

    fun clearDiff() {
        diffRequests.invalidate()
        commentRequests.invalidate()
        displayedRequest = null
        activePopups.toList().forEach { it.cancel() }
        activePopups.clear()
        clearCommentEditors()
        cachedThreads = emptyList()

        currentDiffPanel?.let { Disposer.dispose(it) }
        currentDiffPanel = null
        currentChange = null

        removeAll()
        add(placeholderLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    fun getCurrentChange(): PullRequestChange? = currentChange

    override fun dispose() {
        disposed = true
        diffRequests.dispose()
        commentRequests.dispose()
        clearDiff()
    }

    private fun clearInlays() {
        activeInlays.forEach { if (it.isValid) Disposer.dispose(it) }
        activeInlays.clear()
        activeHighlighters.forEach { if (it.isValid) it.dispose() }
        activeHighlighters.clear()
    }

    // ==================================================================
    //  UI helpers
    // ==================================================================

    private fun showLoading(fileName: String) {
        removeAll()
        add(JBLabel("Loading diff for $fileName…").apply {
            horizontalAlignment = JBLabel.CENTER
            border = JBUI.Borders.empty(20)
        }, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    private fun showError(message: String) {
        removeAll()
        add(JBLabel("❌ $message").apply {
            horizontalAlignment = JBLabel.CENTER
            border = JBUI.Borders.empty(20)
        }, BorderLayout.CENTER)
        revalidate()
        repaint()
    }
}
