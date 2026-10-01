package paol0b.azuredevops.toolwindow.review

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.contents.DocumentContent
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.event.EditorFactoryEvent
import com.intellij.openapi.editor.event.EditorFactoryListener
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
    private val diffContentFactory = DiffContentFactory.getInstance()

    private var disposed = false
    private val diffRequests = LatestRequest<PullRequestChange>()
    private val commentRequests = LatestRequest<LatestRequest.Ticket<PullRequestChange>>()
    private var displayedRequest: LatestRequest.Ticket<PullRequestChange>? = null
    private val activePopups = mutableSetOf<JBPopup>()
    private var currentDiffPanel: DiffRequestPanel? = null
    private var currentChange: PullRequestChange? = null
    private var cachedPullRequest: paol0b.azuredevops.model.PullRequest? = null

    // Track documents to identify editors
    private var baseDocument: Document? = null
    private var changesDocument: Document? = null

    // Track editors
    private var baseEditor: Editor? = null
    private var changesEditor: Editor? = null

    // Comment threads cache
    private var cachedThreads: List<CommentThread> = emptyList()
    @Volatile private var currentUserId: String? = null

    // Highlighters & inlays for cleanup
    private val activeHighlighters = mutableListOf<RangeHighlighter>()
    private val activeInlays = mutableListOf<com.intellij.openapi.editor.Inlay<*>>()

    // Hover "+" gutter state — one per editor
    private var hoverHighlighterBase: RangeHighlighter? = null
    private var hoverHighlighterChanges: RangeHighlighter? = null

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
                if (!isCurrentEditor(editor)) return
                val request = displayedRequest ?: return

                if (editor.document == baseDocument) baseEditor = editor
                else changesEditor = editor

                // Show existing comments
                ApplicationManager.getApplication().invokeLater {
                    if (diffRequests.isCurrent(request) && isCurrentEditor(editor)) addInlineCommentsToEditor(editor)
                }

                // ── Hover "+" gutter icon (GitHub-style) ──
                editor.addEditorMouseMotionListener(object : EditorMouseMotionListener {
                    override fun mouseMoved(e: EditorMouseEvent) {
                        if (!isCurrentEditor(editor)) return
                        val logicalPos = editor.xyToLogicalPosition(e.mouseEvent.point)
                        val line = logicalPos.line
                        if (line < 0 || line >= editor.document.lineCount) {
                            removeHoverHighlighter(editor)
                            return
                        }
                        showHoverAddIcon(editor, line)
                    }
                })
            }
        }, this)
    }

    // ==================================================================
    //  Hover "+" gutter icon
    // ==================================================================

    /**
     * Show or move the "+" add-comment gutter icon on the hovered line.
     */
    private fun showHoverAddIcon(editor: Editor, line: Int) {
        val isBase = editor.document == baseDocument
        val existing = if (isBase) hoverHighlighterBase else hoverHighlighterChanges

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

        if (isBase) hoverHighlighterBase = highlighter
        else hoverHighlighterChanges = highlighter
    }

    private fun removeHoverHighlighter(editor: Editor) {
        val isBase = editor.document == baseDocument
        val hl = if (isBase) hoverHighlighterBase else hoverHighlighterChanges
        if (hl != null && hl.isValid) hl.dispose()
        if (isBase) hoverHighlighterBase = null
        else hoverHighlighterChanges = null
    }

    // ==================================================================
    //  Inline comment editor (appears on "+" click)
    // ==================================================================

    /**
     * Show the GitHub-style "Add Review Comment" editor below the specified line.
     */
    private fun showInlineCommentEditor(editor: Editor, line0based: Int) {
        if (!isCurrentEditor(editor)) return
        val request = displayedRequest ?: return
        val change = request.value
        val filePath = change.effectivePath().takeIf { it.isNotBlank() } ?: return

        val isBase = editor.document == baseDocument
        val lineNumber = line0based + 1 // API uses 1-based

        // We need a reference to the popup so callbacks can dismiss it.
        // Use a holder so the lambda can capture it before the popup is built.
        var popupRef: com.intellij.openapi.ui.popup.JBPopup? = null

        val component = InlineCommentEditorComponent(
            project = project,
            apiClient = apiClient,
            pullRequestId = pullRequestId,
            filePath = filePath,
            lineNumber = lineNumber,
            isLeftSide = isBase,
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

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(component, component)
            .setMovable(false)
            .setResizable(false)
            .setRequestFocus(true)
            .setCancelOnClickOutside(true)
            .setCancelOnOtherWindowOpen(false)
            .setCancelKeyEnabled(true)
            .createPopup()
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
        val isBase = editor.document == baseDocument
        val filePath = currentChange?.effectivePath()?.takeIf { it.isNotBlank() } ?: return

        val relevantThreads = cachedThreads.filter { thread ->
            if (thread.comments.orEmpty().none { it.isDeleted != true && it.commentType != "system" }) return@filter false
            val ctx = thread.threadContext
            val isLeftSide = ctx?.leftFileStart != null && ctx.rightFileStart == null
            isLeftSide == isBase
        }

        logger.info("Adding ${relevantThreads.size} inline comments to ${if (isBase) "base" else "changes"} editor for $filePath")

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

        val startLine0 = startLine - 1
        if (startLine0 < 0 || startLine0 >= editor.document.lineCount) {
            logger.warn("Line $startLine out of bounds for thread ${thread.id}")
            return
        }

        val endLine0 = (endLine - 1).coerceIn(startLine0, editor.document.lineCount - 1)
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

        val content1 = diffContentFactory.create(project, oldContent, fileType)
        val content2 = diffContentFactory.create(project, newContent, fileType)

        baseDocument = (content1 as? DocumentContent)?.document
        changesDocument = (content2 as? DocumentContent)?.document

        val pr = cachedPullRequest
        val targetBranch = pr?.targetRefName?.substringAfterLast('/') ?: "Base"
        val sourceBranch = pr?.sourceRefName?.substringAfterLast('/') ?: "Changes"
        val (leftTitle, rightTitle) = currentChange?.diffSideTitles(targetBranch, sourceBranch)
            ?: ("Base ($targetBranch)" to "Changes ($sourceBranch)")
        val diffTitleSuffix = currentChange?.displayChangeLabel()?.let { " [$it]" }.orEmpty()

        val diffRequest = SimpleDiffRequest(
            "PR #$pullRequestId: $fileName$diffTitleSuffix",
            content1, content2, leftTitle, rightTitle
        )

        val diffManager = DiffManager.getInstance()
        currentDiffPanel = diffManager.createRequestPanel(project, this, null).apply {
            setRequest(diffRequest)
        }

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
                        baseEditor?.let { addInlineCommentsToEditor(it) }
                        changesEditor?.let { addInlineCommentsToEditor(it) }
                    }
                }
            } catch (e: Exception) {
                logger.error("Failed to refresh comments", e)
            }
        }
    }

    private fun isCurrentEditor(editor: Editor): Boolean = !disposed && !editor.isDisposed &&
        displayedRequest?.let { diffRequests.isCurrent(it) } == true &&
        (editor.document == baseDocument || editor.document == changesDocument)

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
        clearInlays()
        hoverHighlighterBase?.let { if (it.isValid) it.dispose() }
        hoverHighlighterChanges?.let { if (it.isValid) it.dispose() }
        hoverHighlighterBase = null
        hoverHighlighterChanges = null
        baseEditor = null
        changesEditor = null
        baseDocument = null
        changesDocument = null
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
