package paol0b.azuredevops.services

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.PullRequest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Service that performs automatic polling to update PR comments.
 * Uses self-rescheduling to prevent overlapping requests and support configurable intervals.
 * Notifies registered change listeners when comments change (hash-based detection).
 */
@Service(Service.Level.PROJECT)
class CommentsPollingService(private val project: Project) {

    private val logger = Logger.getInstance(CommentsPollingService::class.java)
    private var scheduler: ScheduledExecutorService? = null
    private var currentPullRequest: PullRequest? = null
    private var isPollingActive = false
    private var lastCommentsHash: Int = 0
    private val changeListeners = CopyOnWriteArrayList<() -> Unit>()

    companion object {
        fun getInstance(project: Project): CommentsPollingService {
            return project.getService(CommentsPollingService::class.java)
        }
    }

    fun addChangeListener(listener: () -> Unit) {
        changeListeners.add(listener)
    }

    fun removeChangeListener(listener: () -> Unit) {
        changeListeners.remove(listener)
    }

    /**
     * Starts polling for a specific PR.
     * Uses self-rescheduling: the next poll is scheduled only after the current one completes.
     */
    fun startPolling(pullRequest: PullRequest) {
        logger.info("Starting polling for PR #${pullRequest.pullRequestId}")

        if (isPollingActive) {
            logger.info("Polling already active, stopping previous...")
            stopPolling()
        }

        currentPullRequest = pullRequest
        isPollingActive = true
        lastCommentsHash = 0

        val interval = AzureDevOpsSettingsService.getInstance(project).state.commentsIntervalSeconds
        scheduler = ScheduledThreadPoolExecutor(1)
        scheduleNext()

        logger.info("Polling started with ${interval}s interval")
    }

    private fun scheduleNext() {
        if (!isPollingActive) return
        val interval = AzureDevOpsSettingsService.getInstance(project).state.commentsIntervalSeconds
        scheduler?.schedule({
            try {
                refreshComments()
            } catch (e: Exception) {
                logger.warn("Error during comments refresh", e)
            } finally {
                scheduleNext()
            }
        }, interval, TimeUnit.SECONDS)
    }

    /**
     * Stops polling
     */
    fun stopPolling() {
        logger.info("Stopping comments polling")

        isPollingActive = false
        currentPullRequest = null
        lastCommentsHash = 0

        scheduler?.shutdown()
        scheduler = null
    }

    /**
     * Reschedule polling with the current settings.
     * Called when polling interval settings change.
     */
    fun reschedule() {
        if (!isPollingActive) return
        val pr = currentPullRequest ?: return
        logger.info("Rescheduling comments polling with updated interval")
        stopPolling()
        startPolling(pr)
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

        // Check if comments visibility is enabled globally
        val visibilityService = CommentsVisibilityService.getInstance(project)
        if (!visibilityService.isCommentsVisible()) {
            logger.info("Comments visibility disabled - skipping refresh")
            return
        }

        logger.info("Refreshing comments for PR #${pr.pullRequestId}")

        try {
            val apiClient = AzureDevOpsApiClient.getInstance(project)
            val threads = apiClient.getCommentThreads(pr.pullRequestId)

            logger.info("Fetched ${threads.size} threads")

            // Hash-based change detection
            val newHash = calculateCommentsHash(threads)
            val hasChanged = newHash != lastCommentsHash
            if (hasChanged) {
                logger.info("Comments changed (hash: $lastCommentsHash -> $newHash)")
                lastCommentsHash = newHash
            } else {
                logger.info("No changes in comments, skipping UI update")
                return
            }

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

                // Notify change listeners
                changeListeners.forEach { it() }

                logger.info("Comments refreshed successfully")
            }

        } catch (e: Exception) {
            logger.warn("Failed to refresh comments", e)
        }
    }

    private fun calculateCommentsHash(threads: List<CommentThread>): Int {
        return threads.hashCode() + threads.sumOf { thread ->
            var hash = thread.id ?: 0
            hash = 31 * hash + (thread.status?.hashCode() ?: 0)
            hash = 31 * hash + (thread.comments?.size ?: 0)
            hash = 31 * hash + (thread.comments?.firstOrNull()?.content?.hashCode() ?: 0)
            hash = 31 * hash + (thread.comments?.lastOrNull()?.content?.hashCode() ?: 0)
            hash
        }
    }

    fun isPolling(): Boolean = isPollingActive

    fun getCurrentPullRequest(): PullRequest? = currentPullRequest
}
