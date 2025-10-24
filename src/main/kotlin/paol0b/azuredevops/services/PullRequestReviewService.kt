package paol0b.azuredevops.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.ui.PullRequestReviewDialog

/**
 * Servizio per gestire la review delle Pull Request
 * Apre diff viewer integrato di Rider per visualizzare le modifiche
 */
@Service(Service.Level.PROJECT)
class PullRequestReviewService(private val project: Project) {

    private val logger = Logger.getInstance(PullRequestReviewService::class.java)

    companion object {
        fun getInstance(project: Project): PullRequestReviewService {
            return project.getService(PullRequestReviewService::class.java)
        }
    }

    /**
     * Apre la review completa di una PR mostrando tutti i file modificati
     * 
     * @param pullRequest La PR da revieware
     */
    fun startReview(pullRequest: PullRequest) {
        logger.info("=== START REVIEW FOR PR #${pullRequest.pullRequestId} ===")
        
        val apiClient = AzureDevOpsApiClient.getInstance(project)
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                logger.info("Fetching changes from API...")
                
                val changes = apiClient.getPullRequestChanges(pullRequest.pullRequestId)
                
                logger.info("Found ${changes.size} changes in PR #${pullRequest.pullRequestId}")
                
                if (changes.isEmpty()) {
                    logger.warn("No changes found!")
                    ApplicationManager.getApplication().invokeLater {
                        showNoChangesNotification()
                    }
                    return@executeOnPooledThread
                }
                
                // Filtra solo i file (hanno un path e un changeType valido)
                val fileChanges = changes.filter { change ->
                    val changeType = change.changeType?.lowercase() ?: ""
                    val hasPath = change.item?.path?.isNotBlank() == true
                    val isFileChange = changeType in listOf("edit", "add", "rename", "delete")
                    
                    hasPath && isFileChange
                }
                
                logger.info("Filtered to ${fileChanges.size} file changes")
                
                if (fileChanges.isEmpty()) {
                    logger.warn("No file changes after filtering!")
                    return@executeOnPooledThread
                }
                
                // Apri il dialog di review
                ApplicationManager.getApplication().invokeLater {
                    val dialog = PullRequestReviewDialog(project, pullRequest, fileChanges)
                    dialog.show()
                }
                
            } catch (e: Exception) {
                logger.error("Failed to start review", e)
                ApplicationManager.getApplication().invokeLater {
                    showErrorNotification(e.message ?: "Unknown error")
                }
            }
        }
    }

    private fun showNoChangesNotification() {
        logger.info("No changes notification")
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("AzureDevOps.Notifications")
            .createNotification(
                "No Changes",
                "This Pull Request has no file changes to review.",
                com.intellij.notification.NotificationType.INFORMATION
            )
            .notify(project)
    }

    private fun showErrorNotification(message: String) {
        logger.error("Review error notification: $message")
        com.intellij.notification.NotificationGroupManager.getInstance()
            .getNotificationGroup("AzureDevOps.Notifications")
            .createNotification(
                "Review Error",
                "Failed to start review: $message",
                com.intellij.notification.NotificationType.ERROR
            )
            .notify(project)
    }
}
