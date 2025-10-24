package paol0b.azuredevops.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import paol0b.azuredevops.model.PullRequestResponse
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.AzureDevOpsApiException
import paol0b.azuredevops.services.AzureDevOpsConfigService
import paol0b.azuredevops.services.GitRepositoryService
import paol0b.azuredevops.ui.CreatePullRequestDialog
import java.awt.Desktop
import java.net.URI

/**
 * Action per creare una Pull Request su Azure DevOps
 */
class CreatePullRequestAction : AnAction() {

    private val logger = Logger.getInstance(CreatePullRequestAction::class.java)

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        // Verifica che il progetto abbia un repository Git
        val gitService = GitRepositoryService.getInstance(project)
        if (!gitService.hasGitRepository()) {
            Messages.showErrorDialog(
                project,
                "Nessun repository Git trovato in questo progetto.",
                "Repository Git Mancante"
            )
            return
        }

        // Verifica che sia un repository Azure DevOps o configurato manualmente
        val configService = AzureDevOpsConfigService.getInstance(project)
        if (!configService.isAzureDevOpsRepository()) {
            val result = Messages.showYesNoDialog(
                project,
                "Azure DevOps non è configurato per questo progetto.\n\n" +
                        "Il plugin può:\n" +
                        "1. Rilevare automaticamente repository clonati da Azure DevOps\n" +
                        "2. Essere configurato manualmente se il repository non è Azure DevOps\n\n" +
                        "Vuoi configurarlo ora?",
                "Configurazione Azure DevOps Richiesta",
                Messages.getQuestionIcon()
            )
            
            if (result == Messages.YES) {
                // Apri le impostazioni
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "Azure DevOps")
            }
            return
        }

        // Verifica che il PAT sia configurato
        if (!configService.isConfigured()) {
            val result = Messages.showYesNoDialog(
                project,
                "Il Personal Access Token (PAT) non è configurato.\n\n" +
                        "Repository rilevato: ${configService.getDetectedRepositoryInfo()}\n\n" +
                        "Vuoi configurare il PAT ora?",
                "Configurazione PAT Richiesta",
                Messages.getQuestionIcon()
            )
            
            if (result == Messages.YES) {
                // Apri le impostazioni
                com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                    .showSettingsDialog(project, "Azure DevOps")
            }
            return
        }

        // Mostra il dialog per creare la PR
        val dialog = CreatePullRequestDialog(project, gitService)
        if (dialog.showAndGet()) {
            val sourceBranch = dialog.getSourceBranch()
            val targetBranch = dialog.getTargetBranch()
            val title = dialog.getPrTitle()
            val description = dialog.getDescription()

            if (sourceBranch != null && targetBranch != null) {
                createPullRequest(project, sourceBranch.name, targetBranch.name, title, description)
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }

        // Mostra l'action solo se è un repository Azure DevOps
        val configService = AzureDevOpsConfigService.getInstance(project)
        e.presentation.isEnabledAndVisible = configService.isAzureDevOpsRepository()
    }

    /**
     * Crea la Pull Request in modo asincrono
     */
    private fun createPullRequest(
        project: Project,
        sourceBranch: String,
        targetBranch: String,
        title: String,
        description: String
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Creating Pull Request on Azure DevOps...",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                
                try {
                    val apiClient = AzureDevOpsApiClient.getInstance(project)
                    
                    // Verifica se esiste già una PR attiva tra questi branch
                    indicator.text = "Checking for existing Pull Requests..."
                    val existingPr = apiClient.findActivePullRequest(sourceBranch, targetBranch)
                    
                    if (existingPr != null) {
                        // Esiste già una PR attiva!
                        val sourceDisplayName = sourceBranch.removePrefix("refs/heads/")
                        val targetDisplayName = targetBranch.removePrefix("refs/heads/")
                        
                        showExistingPrError(
                            project, 
                            existingPr.pullRequestId,
                            existingPr.title,
                            sourceDisplayName,
                            targetDisplayName
                        )
                        return
                    }
                    
                    // Nessuna PR esistente, procedi con la creazione
                    indicator.text = "Sending request to Azure DevOps..."
                    val response = apiClient.createPullRequest(
                        sourceBranch = sourceBranch,
                        targetBranch = targetBranch,
                        title = title,
                        description = description
                    )

                    // Mostra notifica di successo
                    showSuccessNotification(project, response)
                    
                } catch (e: AzureDevOpsApiException) {
                    logger.error("Failed to create pull request", e)
                    showErrorNotification(project, e.message ?: "Errore sconosciuto")
                } catch (e: Exception) {
                    logger.error("Unexpected error creating pull request", e)
                    showErrorNotification(project, "Errore inaspettato: ${e.message}")
                }
            }
        })
    }

    /**
     * Mostra una notifica di successo
     */
    private fun showSuccessNotification(project: Project, response: PullRequestResponse) {
        val prUrl = getPullRequestUrl(project, response.pullRequestId)
        
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("AzureDevOps.Notifications")
            .createNotification(
                "Pull Request Created Successfully",
                "PR #${response.pullRequestId}: ${response.title}<br>" +
                        "Source: ${response.sourceRefName.removePrefix("refs/heads/")}<br>" +
                        "Target: ${response.targetRefName.removePrefix("refs/heads/")}",
                NotificationType.INFORMATION
            )

        // Aggiungi azione per aprire la PR nel browser
        if (prUrl != null) {
            notification.addAction(object : com.intellij.openapi.actionSystem.AnAction("Open in Browser") {
                override fun actionPerformed(e: AnActionEvent) {
                    openInBrowser(prUrl)
                    notification.expire()
                }
            })
        }

        notification.notify(project)
    }

    /**
     * Mostra una notifica di errore
     */
    private fun showErrorNotification(project: Project, message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("AzureDevOps.Notifications")
            .createNotification(
                "Failed to Create Pull Request",
                message,
                NotificationType.ERROR
            )
            .notify(project)
    }
    
    /**
     * Mostra un errore quando esiste già una PR tra i branch
     */
    private fun showExistingPrError(
        project: Project, 
        prId: Int, 
        prTitle: String,
        sourceBranch: String, 
        targetBranch: String
    ) {
        val prUrl = getPullRequestUrl(project, prId)
        
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup("AzureDevOps.Notifications")
            .createNotification(
                "Pull Request Already Exists",
                "Esiste già una Pull Request attiva da <b>$sourceBranch</b> verso <b>$targetBranch</b>:<br><br>" +
                        "PR #$prId: $prTitle",
                NotificationType.WARNING
            )
        
        // Aggiungi azione per aprire la PR esistente
        if (prUrl != null) {
            notification.addAction(object : com.intellij.openapi.actionSystem.AnAction("Open Existing PR") {
                override fun actionPerformed(e: AnActionEvent) {
                    openInBrowser(prUrl)
                    notification.expire()
                }
            })
        }
        
        notification.notify(project)
    }

    /**
     * Costruisce l'URL della Pull Request
     */
    private fun getPullRequestUrl(project: Project, prId: Int): String? {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()
        
        if (!config.isValid()) return null
        
        // URL formato: https://dev.azure.com/{org}/{project}/_git/{repo}/pullrequest/{prId}
        return "https://dev.azure.com/${config.organization}/${config.project}/_git/${config.repository}/pullrequest/$prId"
    }

    /**
     * Apre un URL nel browser predefinito
     */
    private fun openInBrowser(url: String) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI(url))
            }
        } catch (e: Exception) {
            logger.error("Failed to open browser", e)
        }
    }
}
