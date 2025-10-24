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
 * Listener per rilevare quando viene aperto un file
 * Carica automaticamente i commenti PR se il branch ha una PR attiva
 * Stile Visual Studio: commenti sempre visibili durante la review
 */
class FileOpenedListener(private val project: Project) : FileEditorManagerListener {

    private val logger = Logger.getInstance(FileOpenedListener::class.java)
    private val notifiedBranches = mutableSetOf<String>()
    private var currentPullRequest: PullRequest? = null

    override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
        logger.info("File opened: ${file.path}")
        
        // Verifica solo se Azure DevOps è configurato
        val configService = AzureDevOpsConfigService.getInstance(project)
        if (!configService.isAzureDevOpsRepository()) {
            logger.info("Not an Azure DevOps repository, skipping")
            return
        }

        // Verifica se c'è un branch corrente
        val gitService = GitRepositoryService.getInstance(project)
        val currentBranch = gitService.getCurrentBranch()
        if (currentBranch == null) {
            logger.info("No current branch found")
            return
        }
        logger.info("Current branch: ${currentBranch.displayName}")
        
        // Se abbiamo già una PR corrente per questo branch, carica i commenti direttamente
        val pr = currentPullRequest
        if (pr != null && pr.sourceRefName?.endsWith(currentBranch.displayName) == true) {
            logger.info("Using cached PR #${pr.pullRequestId}")
            loadCommentsForFile(source, file, pr)
            return
        }

        logger.info("Searching for PR for branch ${currentBranch.displayName}")
        
        // Cerca PR in background
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val pullRequest = apiClient.findPullRequestForBranch(currentBranch.displayName)

                if (pullRequest != null) {
                    logger.info("Found PR #${pullRequest.pullRequestId} for branch ${currentBranch.displayName}")
                    currentPullRequest = pullRequest
                    
                    // Avvia polling automatico per i commenti
                    val pollingService = CommentsPollingService.getInstance(project)
                    if (!pollingService.isPolling()) {
                        pollingService.startPolling(pullRequest)
                    }
                    
                    ApplicationManager.getApplication().invokeLater {
                        // Carica i commenti per il file appena aperto
                        loadCommentsForFile(source, file, pullRequest)
                        
                        // Notifica solo la prima volta
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
     * Carica i commenti per il file nell'editor
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
     * Mostra la notifica di PR attiva
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
        
        // Azione per ricaricare i commenti manualmente
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
        // Rimuovi i marker dei commenti quando il file viene chiuso
        val commentsService = PullRequestCommentsService.getInstance(project)
        commentsService.clearCommentsFromFile(file)
        
        // Refresh project view to update file decorators
        ApplicationManager.getApplication().invokeLater {
            ProjectView.getInstance(project)?.refresh()
        }
    }
}
