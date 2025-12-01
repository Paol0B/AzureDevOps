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
 * Service that performs automatic polling to update PR comments
 * Automatic refresh every 30 seconds
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
     * Starts polling for a specific PR
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
     * Stops polling
     */
    fun stopPolling() {
        logger.info("Stopping comments polling")

        isPollingActive = false
        currentPullRequest = null

        scheduler?.shutdown()
        scheduler = null
    }

    /**
     * Manual refresh of comments
     */
    fun refreshNow() {
        if (currentPullRequest != null) {
            logger.info("Manual refresh requested")
            refreshComments()
        }
    }

    /**
     * Performs the refresh of comments
     */
    private fun refreshComments() {
        val pr = currentPullRequest ?: return

        logger.info("Refreshing comments for PR #${pr.pullRequestId}")

        try {
            val apiClient = AzureDevOpsApiClient.getInstance(project)
            val threads = apiClient.getCommentThreads(pr.pullRequestId)

            logger.info("Fetched ${threads.size} threads")

            // Update comments in open files
            ApplicationManager.getApplication().invokeLater {
                val fileEditorManager = FileEditorManager.getInstance(project)
                val openFiles = fileEditorManager.openFiles

                val commentsService = PullRequestCommentsService.getInstance(project)

                openFiles.forEach { file ->
                    val editor = fileEditorManager.selectedTextEditor
                    if (editor != null && file == fileEditorManager.selectedFiles.firstOrNull()) {
                        // Reload comments in the current file
                        commentsService.loadCommentsInEditor(editor, file, pr)
                    }
                }

                // Update the global tracker for decorators
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

                // Refresh the project view to update badges
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
