package paol0b.azuredevops.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import paol0b.azuredevops.services.AzureDevOpsConfigService

/**
 * Factory per creare il ToolWindow delle Pull Request
 */
class PullRequestToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val pullRequestToolWindow = PullRequestToolWindow(project)
        val content = toolWindow.contentManager.factory.createContent(pullRequestToolWindow.getContent(), "", false)
        toolWindow.contentManager.addContent(content)

        // Ensure we stop polling when the content is removed
        toolWindow.contentManager.addContentManagerListener(object : com.intellij.ui.content.ContentManagerAdapter() {
            override fun contentRemoved(event: com.intellij.ui.content.ContentManagerEvent) {
                if (event.content == content) {
                    pullRequestToolWindow.dispose()
                }
            }
        })
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
