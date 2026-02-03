package paol0b.azuredevops

import com.intellij.icons.AllIcons
import com.intellij.openapi.util.IconLoader
import javax.swing.Icon

/**
 * Custom icons for Azure DevOps
 * Using Azure DevOps brand icon for logo/toolwindow and standard IDE icons for items
 */
object AzureDevOpsIcons {
    @JvmField
    val ToolWindow: Icon = IconLoader.getIcon("/icons/azuredevops.svg", AzureDevOpsIcons::class.java)
    
    @JvmField
    val Logo: Icon = IconLoader.getIcon("/icons/azuredevops.svg", AzureDevOpsIcons::class.java)
    
    @JvmField
    val LogoLarge: Icon = IconLoader.getIcon("/icons/azure-devops-logo.svg", AzureDevOpsIcons::class.java)
    
    // Project icon - folder with special mark (like Azure DevOps projects)
    @JvmField
    val Project: Icon = AllIcons.Nodes.Folder
    
    // Repository icon - standard git repo icon
    @JvmField
    val Repository: Icon = AllIcons.Vcs.Branch
}
