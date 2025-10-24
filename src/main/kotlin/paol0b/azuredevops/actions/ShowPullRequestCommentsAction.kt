package paol0b.azuredevops.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.ui.Messages
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.GitRepositoryService
import paol0b.azuredevops.services.PullRequestCommentsService

/**
 * Action per caricare e visualizzare i commenti della PR nel file corrente
 */
class ShowPullRequestCommentsAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return

        // Verifica che ci sia un branch corrente
        val gitService = GitRepositoryService.getInstance(project)
        val currentBranch = gitService.getCurrentBranch() ?: run {
            Messages.showWarningDialog(
                project,
                "Nessun branch Git attivo.",
                "Impossibile Visualizzare Commenti"
            )
            return
        }

        // Cerca la PR associata al branch
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val pullRequest = apiClient.findPullRequestForBranch(currentBranch.displayName)

                if (pullRequest == null) {
                    ApplicationManager.getApplication().invokeLater {
                        Messages.showMessageDialog(
                            project,
                            "Il branch '${currentBranch.displayName}' non ha una Pull Request attiva.\n\n" +
                                    "Crea una Pull Request per questo branch per visualizzare i commenti.",
                            "Nessuna Pull Request",
                            Messages.getInformationIcon()
                        )
                    }
                    return@executeOnPooledThread
                }

                // Carica i commenti
                val commentsService = PullRequestCommentsService.getInstance(project)
                ApplicationManager.getApplication().invokeLater {
                    commentsService.loadCommentsInEditor(editor, file, pullRequest)
                    
                    Messages.showMessageDialog(
                        project,
                        "Commenti caricati per PR #${pullRequest.pullRequestId}:\n${pullRequest.title}\n\n" +
                                "I commenti sono evidenziati nel codice.\n" +
                                "Clicca sull'icona nella gutter per visualizzare e rispondere.",
                        "Commenti Caricati",
                        Messages.getInformationIcon()
                    )
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    Messages.showErrorDialog(
                        project,
                        "Errore durante il caricamento dei commenti:\n${e.message}",
                        "Errore"
                    )
                }
            }
        }
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = e.getData(CommonDataKeys.VIRTUAL_FILE)
        
        e.presentation.isEnabledAndVisible = project != null && editor != null && file != null
    }
}
