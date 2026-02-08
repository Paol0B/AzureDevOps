package paol0b.azuredevops.checkout

import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ui.cloneDialog.VcsCloneDialogExtension
import com.intellij.openapi.vcs.ui.cloneDialog.VcsCloneDialogExtensionComponent
import com.intellij.openapi.vcs.ui.cloneDialog.VcsCloneDialogExtensionStatusLine
import paol0b.azuredevops.AzureDevOpsIcons
import javax.swing.Icon

/**
 * Clone dialog extension that adds Azure DevOps to the left panel
 * of the Clone Repository dialog (alongside GitHub, GitLab, etc.)
 * 
 * This uses the proper extension point (vcsCloneDialogExtension) instead of
 * checkoutProvider, which places items in the VCS dropdown.
 */
class AzureDevOpsCloneDialogExtension : VcsCloneDialogExtension {

    override fun getName(): String = "Azure DevOps"

    override fun getIcon(): Icon = AzureDevOpsIcons.Logo

    override fun getAdditionalStatusLines(): List<VcsCloneDialogExtensionStatusLine> {
        val accountManager = AzureDevOpsAccountManager.getInstance()
        val accounts = accountManager.getAccounts()
        
        return if (accounts.isEmpty()) {
            listOf(VcsCloneDialogExtensionStatusLine.greyText("No accounts"))
        } else {
            accounts.map { account ->
                VcsCloneDialogExtensionStatusLine.greyText(account.displayName)
            }
        }
    }

    override fun createMainComponent(project: Project, modalityState: ModalityState): VcsCloneDialogExtensionComponent {
        return AzureDevOpsCloneDialogComponent(project)
    }
}
