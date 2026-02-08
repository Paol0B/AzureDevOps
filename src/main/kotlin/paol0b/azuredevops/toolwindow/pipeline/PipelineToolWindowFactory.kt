package paol0b.azuredevops.toolwindow.pipeline

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import paol0b.azuredevops.model.PipelineBuild
import java.util.concurrent.ConcurrentHashMap

/**
 * Factory to create the Pipeline ToolWindow.
 *
 * The first (non-closable) content tab is the pipeline/build list.
 * Additional closable tabs are opened when the user double-clicks a build.
 * Mirrors [paol0b.azuredevops.toolwindow.PullRequestToolWindowFactory].
 */
class PipelineToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        const val TOOL_WINDOW_ID = "Azure DevOps Pipelines"

        /** Active detail tab panels keyed by build id */
        private val detailTabs = ConcurrentHashMap<Int, PipelineDetailTabPanel>()

        /**
         * Open (or focus) a detail tab for the given build inside the tool window.
         */
        fun openPipelineDetailTab(project: Project, build: PipelineBuild) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            val contentManager = toolWindow.contentManager

            // Check if tab already exists
            val existing = detailTabs[build.id]
            if (existing != null) {
                for (content in contentManager.contents) {
                    if (content.component == existing) {
                        contentManager.setSelectedContent(content, true)
                        toolWindow.activate(null)
                        return
                    }
                }
                // Content was removed but map entry lingered — clean up
                detailTabs.remove(build.id)
            }

            // Create new detail tab
            val panel = PipelineDetailTabPanel(project, build)
            detailTabs[build.id] = panel

            val defName = build.definition?.name ?: "Pipeline"
            val tabTitle = "$defName #${build.buildNumber}"
            val content = contentManager.factory.createContent(panel, tabTitle, false).apply {
                isCloseable = true
                description = "Build ${build.buildNumber}"
            }
            contentManager.addContent(content)
            contentManager.setSelectedContent(content, true)

            toolWindow.activate(null)
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val pipelineToolWindow = PipelineToolWindow(project)
        val content = toolWindow.contentManager.factory.createContent(
            pipelineToolWindow.getContent(), "Pipelines", false
        ).apply {
            isCloseable = false
        }
        toolWindow.contentManager.addContent(content)

        // Load pipelines when the list tab becomes visible
        toolWindow.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.content == content &&
                    event.operation == ContentManagerEvent.ContentOperation.add
                ) {
                    pipelineToolWindow.loadPipelinesIfNeeded()
                }
            }
        })

        // Dispose resources when content is removed
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                if (event.content == content) {
                    pipelineToolWindow.dispose()
                    return
                }

                // Clean up detail tab entries
                val removedComponent = event.content.component
                if (removedComponent is PipelineDetailTabPanel) {
                    removedComponent.dispose()
                    detailTabs.entries.removeIf { it.value == removedComponent }
                }
            }
        })
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
