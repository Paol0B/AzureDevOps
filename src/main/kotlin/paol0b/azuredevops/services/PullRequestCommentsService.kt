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
 * Servizio per visualizzare e gestire i commenti delle PR direttamente nel codice
 * Simile a Visual Studio con icone nella gutter e tooltip
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
            backgroundColor = JBColor(Color(255, 250, 205), Color(80, 75, 50)) // Giallo chiaro/scuro
            effectType = EffectType.BOXED
            effectColor = JBColor(Color(255, 200, 0), Color(200, 150, 0))
        }
        
        private val RESOLVED_COMMENT_ATTRIBUTES = TextAttributes().apply {
            backgroundColor = JBColor(Color(220, 255, 220), Color(50, 80, 50)) // Verde chiaro/scuro
            effectType = EffectType.BOXED
            effectColor = JBColor(Color(100, 200, 100), Color(100, 150, 100))
        }
        
        // Icone per i commenti (visibili nella gutter)
        private val ACTIVE_COMMENT_ICON: Icon = AllIcons.Toolwindows.ToolWindowMessages
        private val RESOLVED_COMMENT_ICON: Icon = AllIcons.RunConfigurations.TestPassed
    }

    /**
     * Carica e visualizza i commenti di una PR nel file aperto
     * 
     * @param editor Editor in cui visualizzare i commenti
     * @param file File da analizzare
     * @param pullRequest PR da cui caricare i commenti
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
                
                // Log tutti i thread per debug
                threads.forEachIndexed { index, thread ->
                    logger.info("Thread #$index: id=${thread.id}, path=${thread.getFilePath()}, line=${thread.getRightFileStart()}, status=${thread.status}")
                }
                
                // Filtra i thread per questo file
                // Il path del thread è relativo al repo (es: /src/main.cs)
                // Il path del file locale contiene il percorso completo
                val fileThreads = threads.filter { thread ->
                    val threadPath = thread.getFilePath()
                    if (threadPath == null) {
                        logger.info("Thread ${thread.id} has no path, skipping")
                        return@filter false
                    }
                    
                    // Normalizza i path per il confronto
                    val normalizedThreadPath = threadPath.replace('/', '\\').trimStart('\\')
                    val normalizedFilePath = file.path.replace('/', '\\')
                    
                    // Verifica se il file path termina con il thread path
                    val matches = normalizedFilePath.endsWith(normalizedThreadPath, ignoreCase = true)
                    
                    logger.info("Comparing: thread='$normalizedThreadPath' vs file='$normalizedFilePath' -> $matches")
                    
                    matches
                }
                
                logger.info("=== FILTERED TO ${fileThreads.size} THREADS FOR THIS FILE ===")
                
                if (fileThreads.isNotEmpty()) {
                    ApplicationManager.getApplication().invokeLater {
                        displayCommentsInEditor(editor, file, fileThreads, pullRequest)
                        
                        // Aggiorna il tracker per mostrare badge nell'esplora soluzioni
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
     * Visualizza i commenti nell'editor
     */
    private fun displayCommentsInEditor(
        editor: Editor,
        file: VirtualFile,
        threads: List<CommentThread>,
        pullRequest: PullRequest
    ) {
        logger.info("Displaying ${threads.size} comments in editor for file: ${file.path}")
        
        // Rimuovi i marker precedenti
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
            
            // Attributi per l'evidenziazione della linea
            val attributes = if (thread.isResolved()) RESOLVED_COMMENT_ATTRIBUTES else ACTIVE_COMMENT_ATTRIBUTES
            
            val highlighter = markupModel.addRangeHighlighter(
                startOffset,
                endOffset,
                HighlighterLayer.SELECTION - 1,
                attributes,
                HighlighterTargetArea.LINES_IN_RANGE
            )
            
            // Aggiungi icona nella gutter (margine sinistro) come Visual Studio
            val icon = if (thread.isResolved()) RESOLVED_COMMENT_ICON else ACTIVE_COMMENT_ICON
            highlighter.gutterIconRenderer = CommentGutterIconRenderer(thread, pullRequest, icon, this)
            
            // Tooltip al passaggio del mouse
            val tooltipText = buildTooltipText(thread)
            highlighter.errorStripeTooltip = tooltipText
            
            markers.add(highlighter)
        }
        
        commentMarkers[file] = markers
    }
    
    /**
     * Costruisce il testo del tooltip per il commento
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
     * Rimuove tutti i commenti visualizzati da un file
     */
    fun clearCommentsFromFile(file: VirtualFile) {
        commentMarkers[file]?.forEach { it.dispose() }
        commentMarkers.remove(file)
        
        // Aggiorna anche il tracker
        val tracker = PullRequestCommentsTracker.getInstance(project)
        tracker.clearCommentsForFile(file)
    }

    /**
     * Rimuove tutti i commenti da tutti i file
     */
    fun clearAllComments() {
        commentMarkers.values.flatten().forEach { it.dispose() }
        commentMarkers.clear()
        
        // Aggiorna anche il tracker
        val tracker = PullRequestCommentsTracker.getInstance(project)
        tracker.clearAllComments()
    }

    /**
     * Risponde a un commento
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
     * Risolve un thread di commenti
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
     * Riattiva un thread di commenti
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
