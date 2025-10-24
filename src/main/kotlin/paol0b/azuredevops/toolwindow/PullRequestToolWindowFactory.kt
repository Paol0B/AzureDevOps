package paol0b.azuredevops.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.content.ContentFactory
import paol0b.azuredevops.services.AzureDevOpsConfigService

/**
 * Factory per creare il ToolWindow delle Pull Request
 */
class PullRequestToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val pullRequestToolWindow = PullRequestToolWindow(project)
        val contentFactory = ContentFactory.getInstance()
        val content = contentFactory.createContent(pullRequestToolWindow.getContent(), "", false)
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean {
        // Mostra il tool window solo se è un repository Azure DevOps
        val configService = AzureDevOpsConfigService.getInstance(project)
        return configService.isAzureDevOpsRepository()
    }
}
