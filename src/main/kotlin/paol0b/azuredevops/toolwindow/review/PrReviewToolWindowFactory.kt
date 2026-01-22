package paol0b.azuredevops.toolwindow.review

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory

/**
 * Factory for PR Review Tool Window
 * Creates the full-screen review workspace with file tree, diff viewer, and comments
 */
class PrReviewToolWindowFactory : ToolWindowFactory, DumbAware {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val reviewWindow = PrReviewToolWindow(project)
        val content = toolWindow.contentManager.factory.createContent(
            reviewWindow,
            "",
            false
        )
        toolWindow.contentManager.addContent(content)
        
        // Cleanup when content is removed
        toolWindow.contentManager.addContentManagerListener(object : com.intellij.ui.content.ContentManagerListener {
            override fun contentRemoved(event: com.intellij.ui.content.ContentManagerEvent) {
                if (event.content == content) {
                    reviewWindow.dispose()
                }
            }
        })
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
