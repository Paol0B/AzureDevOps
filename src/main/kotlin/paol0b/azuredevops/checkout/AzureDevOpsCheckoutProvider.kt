package paol0b.azuredevops.checkout

import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckoutProvider
import com.intellij.openapi.vcs.VcsNotifier
import git4idea.GitVcs
import git4idea.checkout.GitCheckoutProvider
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import java.io.File

/**
 * Checkout provider for Azure DevOps repositories.
 * Appears in the "Clone Repository" dialog alongside GitHub, GitLab, etc.
 */
class AzureDevOpsCheckoutProvider : CheckoutProvider {

    override fun getVcsName(): String = "Azure DevOps"

    override fun doCheckout(project: Project, listener: CheckoutProvider.Listener?) {
        val dialog = AzureDevOpsCloneDialog(project)
        
        if (dialog.showAndGet()) {
            val selectedRepo = dialog.getSelectedRepository() ?: return
            val targetDirectory = dialog.getTargetDirectory()
            val cloneUrl = selectedRepo.remoteUrl
            
            // Perform the clone operation using Git
            ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Cloning ${selectedRepo.name}...", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.text = "Cloning repository from Azure DevOps..."
                        indicator.text2 = cloneUrl
                        
                        val checkoutDir = File(targetDirectory, selectedRepo.name)
                        checkoutDir.mkdirs()
                        
                        // Execute git clone
                        val handler = GitLineHandler(project, checkoutDir.parentFile, GitCommand.CLONE)
                        handler.setUrl(cloneUrl)
                        handler.addParameters("--progress")
                        handler.addParameters(cloneUrl)
                        handler.addParameters(selectedRepo.name)
                        
                        val result = Git.getInstance().runCommand(handler)
                        
                        if (result.success()) {
                            listener?.directoryCheckedOut(checkoutDir, GitVcs.getKey())
                            listener?.checkoutCompleted()
                            
                            VcsNotifier.getInstance(project).notifySuccess(
                                null,
                                "Azure DevOps Clone",
                                "Repository '${selectedRepo.name}' cloned successfully to ${checkoutDir.absolutePath}"
                            )
                        } else {
                            VcsNotifier.getInstance(project).notifyError(
                                null,
                                "Azure DevOps Clone Error",
                                "Failed to clone repository: ${result.errorOutputAsJoinedString}"
                            )
                        }
                    } catch (e: Exception) {
                        VcsNotifier.getInstance(project).notifyError(
                            null,
                            "Azure DevOps Clone Error",
                            "Failed to clone repository: ${e.message}"
                        )
                    }
                }
            })
        }
    }
}
