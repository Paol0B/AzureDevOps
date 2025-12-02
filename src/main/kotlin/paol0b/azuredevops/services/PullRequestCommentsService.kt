package paol0b.azuredevops.services

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.markup.*
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.model.ThreadStatus
import java.awt.Color
import javax.swing.Icon

/**
 * Service to display and manage PR comments directly in the code
 * Similar to Visual Studio with gutter icons and tooltips
 */
@Service(Service.Level.PROJECT)
class PullRequestCommentsService(private val project: Project) {

    private val logger = Logger.getInstance(PullRequestCommentsService::class.java)
    private val commentMarkers = mutableMapOf<VirtualFile, MutableList<RangeHighlighter>>()
    
    companion object {
        fun getInstance(project: Project): PullRequestCommentsService {
            return project.getService(PullRequestCommentsService::class.java)
        }
        
        private val ACTIVE_COMMENT_ATTRIBUTES = TextAttributes().apply {
            backgroundColor = JBColor(Color(255, 250, 205), Color(80, 75, 50)) // Light/dark yellow
            effectType = EffectType.BOXED
            effectColor = JBColor(Color(255, 200, 0), Color(200, 150, 0))
        }
        
        private val RESOLVED_COMMENT_ATTRIBUTES = TextAttributes().apply {
            backgroundColor = JBColor(Color(220, 255, 220), Color(50, 80, 50)) // Light/dark green
            effectType = EffectType.BOXED
            effectColor = JBColor(Color(100, 200, 100), Color(100, 150, 100))
        }
        
        // Icons for comments (visible in the gutter)
        private val ACTIVE_COMMENT_ICON: Icon = AllIcons.Toolwindows.ToolWindowMessages
        private val RESOLVED_COMMENT_ICON: Icon = AllIcons.RunConfigurations.TestPassed
    }

    /**
     * Loads and displays PR comments in the open file
     *
     * @param editor Editor in which to display comments
     * @param file File to analyze
     * @param pullRequest PR from which to load comments
     */
    fun loadCommentsInEditor(editor: Editor, file: VirtualFile, pullRequest: PullRequest) {
        logger.info("=== LOADING COMMENTS FOR FILE ===")
        logger.info("File path: ${file.path}")
        logger.info("File name: ${file.name}")
        logger.info("PR #${pullRequest.pullRequestId}")
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val threads = apiClient.getCommentThreads(pullRequest.pullRequestId)
                
                logger.info("Found ${threads.size} total threads for PR #${pullRequest.pullRequestId}")
                
                // Log all threads for debugging
                threads.forEachIndexed { index, thread ->
                    logger.info("Thread #$index: id=${thread.id}, path=${thread.getFilePath()}, line=${thread.getRightFileStart()}, status=${thread.status}")
                }
                
                // Filter threads for this file
                // The thread path is relative to the repo (e.g., /src/main.cs)
                // The local file path contains the full path
                val fileThreads = threads.filter { thread ->
                    val threadPath = thread.getFilePath()
                    if (threadPath == null) {
                        logger.info("Thread ${thread.id} has no path, skipping")
                        return@filter false
                    }
                    
                    // Normalize paths for comparison
                    val normalizedThreadPath = threadPath.replace('/', '\\').trimStart('\\')
                    val normalizedFilePath = file.path.replace('/', '\\')
                    
                    // Check if the file path ends with the thread path
                    val matches = normalizedFilePath.endsWith(normalizedThreadPath, ignoreCase = true)
                    
                    logger.info("Comparing: thread='$normalizedThreadPath' vs file='$normalizedFilePath' -> $matches")
                    
                    matches
                }
                
                logger.info("=== FILTERED TO ${fileThreads.size} THREADS FOR THIS FILE ===")
                
                if (fileThreads.isNotEmpty()) {
                    ApplicationManager.getApplication().invokeLater {
                        displayCommentsInEditor(editor, file, fileThreads, pullRequest)
                        
                        // Update the tracker to show badges in the solution explorer
                        val tracker = PullRequestCommentsTracker.getInstance(project)
                        tracker.setCommentsForFile(file, fileThreads)
                    }
                } else {
                    logger.info("No comments found for this file")
                }
            } catch (e: Exception) {
                logger.error("Error loading comments", e)
            }
        }
    }

    /**
     * Displays comments in the editor
     */
    private fun displayCommentsInEditor(
        editor: Editor,
        file: VirtualFile,
        threads: List<CommentThread>,
        pullRequest: PullRequest
    ) {
        logger.info("Displaying ${threads.size} comments in editor for file: ${file.path}")
        
        // Remove previous markers
        clearCommentsFromFile(file)
        
        val markupModel = editor.markupModel
        val document = editor.document
        val markers = mutableListOf<RangeHighlighter>()
        
        for (thread in threads) {
            val lineNumber = thread.getRightFileStart()
            if (lineNumber == null) {
                logger.warn("Thread has no line number: ${thread.id}")
                continue
            }
            
            logger.info("Adding marker at line $lineNumber for thread ${thread.id}")
            
            val lineIndex = (lineNumber - 1).coerceIn(0, document.lineCount - 1)
            
            val startOffset = document.getLineStartOffset(lineIndex)
            val endOffset = document.getLineEndOffset(lineIndex)
            
            // Attributes for line highlighting
            val attributes = if (thread.isResolved()) RESOLVED_COMMENT_ATTRIBUTES else ACTIVE_COMMENT_ATTRIBUTES
            
            val highlighter = markupModel.addRangeHighlighter(
                startOffset,
                endOffset,
                HighlighterLayer.SELECTION - 1,
                attributes,
                HighlighterTargetArea.LINES_IN_RANGE
            )
            
            // Add icon in the gutter (left margin) like Visual Studio
            val icon = if (thread.isResolved()) RESOLVED_COMMENT_ICON else ACTIVE_COMMENT_ICON
            highlighter.gutterIconRenderer = CommentGutterIconRenderer(thread, pullRequest, icon, this)
            
            // Tooltip on mouse hover
            val tooltipText = buildTooltipText(thread)
            highlighter.errorStripeTooltip = tooltipText
            
            markers.add(highlighter)
        }
        
        commentMarkers[file] = markers
    }
    
    /**
     * Builds the tooltip text for the comment
     */
    private fun buildTooltipText(thread: CommentThread): String {
        val firstComment = thread.comments?.firstOrNull()
        val author = firstComment?.author?.displayName ?: "Unknown"
        val content = firstComment?.content?.take(200) ?: "No content"
        val status = if (thread.isResolved()) "✓ Resolved" else "⚠ Active"

        return """
            <html>
            <b>PR Comment - $status</b><br>
            <b>Author:</b> $author<br>
            <hr>
            ${content.replace("\n", "<br>")}
            ${if (content.length >= 200) "..." else ""}
            </html>
        """.trimIndent()
    }

    /**
     * Removes all displayed comments from a file
     */
    fun clearCommentsFromFile(file: VirtualFile) {
        commentMarkers[file]?.forEach { it.dispose() }
        commentMarkers.remove(file)
        
        // Also update the tracker
        val tracker = PullRequestCommentsTracker.getInstance(project)
        tracker.clearCommentsForFile(file)
    }

    /**
     * Removes all comments from all files
     */
    fun clearAllComments() {
        commentMarkers.values.flatten().forEach { it.dispose() }
        commentMarkers.clear()
        
        // Also update the tracker
        val tracker = PullRequestCommentsTracker.getInstance(project)
        tracker.clearAllComments()
    }

    /**
     * Replies to a comment
     */
    fun replyToComment(pullRequest: PullRequest, threadId: Int, content: String, onSuccess: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                apiClient.addCommentToThread(pullRequest.pullRequestId, threadId, content)
                
                ApplicationManager.getApplication().invokeLater {
                    onSuccess()
                }
            } catch (e: AzureDevOpsApiException) {
                // Handle error
            }
        }
    }

    /**
     * Resolves a comment thread
     */
    fun resolveThread(pullRequest: PullRequest, threadId: Int, onSuccess: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                apiClient.updateThreadStatus(pullRequest.pullRequestId, threadId, ThreadStatus.Fixed)
                
                ApplicationManager.getApplication().invokeLater {
                    onSuccess()
                }
            } catch (e: AzureDevOpsApiException) {
                // Handle error
            }
        }
    }

    /**
     * Re-activates a comment thread
     */
    fun unresolveThread(pullRequest: PullRequest, threadId: Int, onSuccess: () -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                apiClient.updateThreadStatus(pullRequest.pullRequestId, threadId, ThreadStatus.Active)
                
                ApplicationManager.getApplication().invokeLater {
                    onSuccess()
                }
            } catch (e: AzureDevOpsApiException) {
                // Handle error
            }
        }
    }
}
