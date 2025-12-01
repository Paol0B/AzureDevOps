package paol0b.azuredevops.listeners

import com.intellij.ide.projectView.ProjectView
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.AzureDevOpsConfigService
import paol0b.azuredevops.services.CommentsPollingService
import paol0b.azuredevops.services.GitRepositoryService
import paol0b.azuredevops.services.PullRequestCommentsService

/**
 * Listener to detect when a file is opened
 * Automatically loads PR comments if the branch has an active PR
 * Visual Studio style: comments always visible during review
 */
class FileOpenedListener(private val project: Project) : FileEditorManagerListener {

    private val logger = Logger.getInstance(FileOpenedListener::class.java)
    private val notifiedBranches = mutableSetOf<String>()
    private var currentPullRequest: PullRequest? = null

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        logger.info("File opened: ${file.path}")
        
        // Check only if Azure DevOps is configured
        val configService = AzureDevOpsConfigService.getInstance(project)
        if (!configService.isAzureDevOpsRepository()) {
            logger.info("Not an Azure DevOps repository, skipping")
            return
        }

        // Check if there is a current branch
        val gitService = GitRepositoryService.getInstance(project)
        val currentBranch = gitService.getCurrentBranch()
        if (currentBranch == null) {
            logger.info("No current branch found")
            return
        }
        logger.info("Current branch: ${currentBranch.displayName}")
        
        // If we already have a current PR for this branch, load comments directly
        val pr = currentPullRequest
        if (pr != null && pr.sourceRefName?.endsWith(currentBranch.displayName) == true) {
            logger.info("Using cached PR #${pr.pullRequestId}")
            loadCommentsForFile(source, file, pr)
            return
        }

        logger.info("Searching for PR for branch ${currentBranch.displayName}")
        
        // Search for PR in the background
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val pullRequest = apiClient.findPullRequestForBranch(currentBranch.displayName)

                if (pullRequest != null) {
                    logger.info("Found PR #${pullRequest.pullRequestId} for branch ${currentBranch.displayName}")
                    currentPullRequest = pullRequest
                    
                    // Start automatic polling for comments
                    val pollingService = CommentsPollingService.getInstance(project)
                    if (!pollingService.isPolling()) {
                        pollingService.startPolling(pullRequest)
                    }
                    
                    ApplicationManager.getApplication().invokeLater {
                        // Load comments for the newly opened file
                        loadCommentsForFile(source, file, pullRequest)
                        
                        // Notify only the first time
                        if (!notifiedBranches.contains(currentBranch.displayName)) {
                            notifiedBranches.add(currentBranch.displayName)
                            showPullRequestNotification(pullRequest, currentBranch.displayName, source, file)
                        }
                    }
                } else {
                    logger.info("No PR found for branch ${currentBranch.displayName}")
                }
            } catch (e: Exception) {
                logger.warn("Failed to check for PR on branch ${currentBranch.displayName}", e)
            }
        }
    }
    
    /**
     * Loads comments for the file in the editor
     */
    private fun loadCommentsForFile(source: FileEditorManager, file: VirtualFile, pullRequest: PullRequest) {
        logger.info("Loading comments for file: ${file.path}, PR #${pullRequest.pullRequestId}")
        
        val editor = source.selectedTextEditor
        if (editor == null) {
            logger.warn("No selected text editor found")
            return
        }
        
        val commentsService = PullRequestCommentsService.getInstance(project)
        commentsService.loadCommentsInEditor(editor, file, pullRequest)
        
        // Refresh project view to update file decorators
        ApplicationManager.getApplication().invokeLater {
            ProjectView.getInstance(project)?.refresh()
        }
    }
    
    /**
     * Shows notification for active PR
     */
    private fun showPullRequestNotification(
        pullRequest: PullRequest, 
        branchName: String,
        source: FileEditorManager,
        file: VirtualFile
    ) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("AzureDevOps.Notifications")
            .createNotification(
                "Pull Request Active",
                "Branch '$branchName' has active PR: #${pullRequest.pullRequestId} - ${pullRequest.title}<br>" +
                        "<i>Comments are now visible in the editor gutter</i>",
                NotificationType.INFORMATION
            )
        
        // Action to manually refresh comments
        notification.addAction(object : AnAction("Refresh Comments") {
            override fun actionPerformed(e: AnActionEvent) {
                val editor = source.selectedTextEditor
                if (editor != null) {
                    val commentsService = PullRequestCommentsService.getInstance(project)
                    commentsService.loadCommentsInEditor(editor, file, pullRequest)
                }
                notification.expire()
            }
        })
        
        notification.notify(project)
    }

    override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
        // Remove comment markers when the file is closed
        val commentsService = PullRequestCommentsService.getInstance(project)
        commentsService.clearCommentsFromFile(file)
        
        // Refresh project view to update file decorators
        ApplicationManager.getApplication().invokeLater {
            ProjectView.getInstance(project)?.refresh()
        }
    }
}
