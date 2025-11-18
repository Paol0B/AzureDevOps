package paol0b.azuredevops.services

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import paol0b.azuredevops.model.PullRequest
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Servizio che effettua polling automatico per aggiornare i commenti PR
 * Refresh automatico ogni 30 secondi
 */
@Service(Service.Level.PROJECT)
class CommentsPollingService(private val project: Project) {

    private val logger = Logger.getInstance(CommentsPollingService::class.java)
    private var scheduler: ScheduledExecutorService? = null
    private var currentPullRequest: PullRequest? = null
    private var isPollingActive = false

    companion object {
    private const val POLLING_INTERVAL_SECONDS = 5L

        fun getInstance(project: Project): CommentsPollingService {
            return project.getService(CommentsPollingService::class.java)
        }
    }

    /**
     * Avvia il polling per una PR specifica
     */
    fun startPolling(pullRequest: PullRequest) {
        logger.info("Starting polling for PR #${pullRequest.pullRequestId}")
        
        currentPullRequest = pullRequest
        
        if (isPollingActive) {
            logger.info("Polling already active, stopping previous...")
            stopPolling()
        }

        isPollingActive = true
        
        scheduler = ScheduledThreadPoolExecutor(1).apply {
            scheduleAtFixedRate(
                { refreshComments() },
                POLLING_INTERVAL_SECONDS,
                POLLING_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            )
        }
        
        logger.info("Polling started with ${POLLING_INTERVAL_SECONDS}s interval")
    }

    /**
     * Ferma il polling
     */
    fun stopPolling() {
        logger.info("Stopping comments polling")
        
        isPollingActive = false
        currentPullRequest = null
        
        scheduler?.shutdown()
        scheduler = null
    }

    /**
     * Refresh manuale dei commenti
     */
    fun refreshNow() {
        if (currentPullRequest != null) {
            logger.info("Manual refresh requested")
            refreshComments()
        }
    }

    /**
     * Esegue il refresh dei commenti
     */
    private fun refreshComments() {
        val pr = currentPullRequest ?: return
        
        logger.info("Refreshing comments for PR #${pr.pullRequestId}")
        
        try {
            val apiClient = AzureDevOpsApiClient.getInstance(project)
            val threads = apiClient.getCommentThreads(pr.pullRequestId)
            
            logger.info("Fetched ${threads.size} threads")
            
            // Aggiorna i commenti nei file aperti
            ApplicationManager.getApplication().invokeLater {
                val fileEditorManager = FileEditorManager.getInstance(project)
                val openFiles = fileEditorManager.openFiles
                
                val commentsService = PullRequestCommentsService.getInstance(project)
                
                openFiles.forEach { file ->
                    val editor = fileEditorManager.selectedTextEditor
                    if (editor != null && file == fileEditorManager.selectedFiles.firstOrNull()) {
                        // Ricarica commenti nel file corrente
                        commentsService.loadCommentsInEditor(editor, file, pr)
                    }
                }
                
                // Aggiorna il tracker globale per i decoratori
                val tracker = PullRequestCommentsTracker.getInstance(project)
                threads.forEach { thread ->
                    val filePath = thread.getFilePath()
                    if (filePath != null) {
                        val projectBasePath = project.basePath ?: return@forEach
                        val fullPath = "$projectBasePath/${filePath.trimStart('/')}"
                        val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
                            .findFileByPath(fullPath)
                        
                        if (virtualFile != null) {
                            val fileThreads = threads.filter { it.getFilePath() == filePath }
                            tracker.setCommentsForFile(virtualFile, fileThreads)
                        }
                    }
                }
                
                // Refresh della vista progetto per aggiornare i badge
                ProjectView.getInstance(project)?.refresh()
                
                logger.info("Comments refreshed successfully")
            }
            
        } catch (e: Exception) {
            logger.warn("Failed to refresh comments", e)
        }
    }

    /**
     * Verifica se il polling è attivo
     */
    fun isPolling(): Boolean = isPollingActive

    /**
     * Ottiene la PR corrente in polling
     */
    fun getCurrentPullRequest(): PullRequest? = currentPullRequest
}
