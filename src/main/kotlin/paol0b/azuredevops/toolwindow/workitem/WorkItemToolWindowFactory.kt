package paol0b.azuredevops.toolwindow.workitem

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import paol0b.azuredevops.model.WorkItem
import java.util.concurrent.ConcurrentHashMap

/**
 * Factory to create the Work Items ToolWindow.
 *
 * The first (non-closable) content tab is the work item list.
 * Additional closable tabs are opened when the user double-clicks a work item.
 * Mirrors [paol0b.azuredevops.toolwindow.pipeline.PipelineToolWindowFactory].
 */
class WorkItemToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        const val TOOL_WINDOW_ID = "Azure DevOps Work Items"

        /** Active detail tab panels keyed by work item id */
        private val detailTabs = ConcurrentHashMap<Int, WorkItemDetailTabPanel>()

        /**
         * Open (or focus) a detail tab for the given work item inside the tool window.
         */
        fun openWorkItemDetailTab(project: Project, workItem: WorkItem) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            val contentManager = toolWindow.contentManager

            // Check if tab already exists
            val existing = detailTabs[workItem.id]
            if (existing != null) {
                for (content in contentManager.contents) {
                    if (content.component == existing) {
                        contentManager.setSelectedContent(content, true)
                        toolWindow.activate(null)
                        return
                    }
                }
                // Content was removed but map entry lingered — clean up
                detailTabs.remove(workItem.id)
            }

            // Create new detail tab
            val panel = WorkItemDetailTabPanel(project, workItem)
            detailTabs[workItem.id] = panel

            val typeName = workItem.getWorkItemType()
            val tabTitle = "$typeName #${workItem.id}"
            val content = contentManager.factory.createContent(panel, tabTitle, false).apply {
                isCloseable = true
                description = "${workItem.getTitle()}"
            }
            contentManager.addContent(content)
            contentManager.setSelectedContent(content, true)

            toolWindow.activate(null)
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val workItemToolWindow = WorkItemToolWindow(project)
        val content = toolWindow.contentManager.factory.createContent(
            workItemToolWindow.getContent(), "Work Items", false
        ).apply {
            isCloseable = false
        }
        toolWindow.contentManager.addContent(content)

        // Load work items when the list tab becomes visible
        toolWindow.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.content == content &&
                    event.operation == ContentManagerEvent.ContentOperation.add
                ) {
                    workItemToolWindow.loadWorkItemsIfNeeded()
                }
            }
        })

        // Dispose resources when content is removed
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                if (event.content == content) {
                    workItemToolWindow.dispose()
                    return
                }

                // Clean up detail tab entries
                val removedComponent = event.content.component
                if (removedComponent is WorkItemDetailTabPanel) {
                    removedComponent.dispose()
                    detailTabs.entries.removeIf { it.value == removedComponent }
                }
            }
        })
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
