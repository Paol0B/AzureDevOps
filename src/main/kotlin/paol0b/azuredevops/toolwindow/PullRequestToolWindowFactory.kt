package paol0b.azuredevops.toolwindow

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.toolwindow.review.PrReviewTabPanel
import java.util.concurrent.ConcurrentHashMap

/**
 * Factory to create the Pull Request ToolWindow.
 *
 * The first (non-closable) content tab is the PR list.
 * Additional closable tabs are opened when the user double-clicks a PR.
 */
class PullRequestToolWindowFactory : ToolWindowFactory, DumbAware {

    companion object {
        private const val TOOL_WINDOW_ID = "Azure DevOps PRs"

        /** Active review tab panels keyed by pullRequestId */
        private val reviewTabs = ConcurrentHashMap<Int, PrReviewTabPanel>()

        /**
         * Open (or focus) a review tab for the given PR inside the tool window.
         */
        fun openPrReviewTab(project: Project, pullRequest: PullRequest) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            val contentManager = toolWindow.contentManager

            // Check if tab already exists
            val existing = reviewTabs[pullRequest.pullRequestId]
            if (existing != null) {
                // Find the Content that wraps it and select it
                for (content in contentManager.contents) {
                    if (content.component == existing) {
                        contentManager.setSelectedContent(content, true)
                        toolWindow.activate(null)
                        return
                    }
                }
                // Content was removed but map entry lingered — clean up
                reviewTabs.remove(pullRequest.pullRequestId)
            }

            // Create new review tab
            val panel = PrReviewTabPanel(project, pullRequest)
            reviewTabs[pullRequest.pullRequestId] = panel

            val tabTitle = "PR #${pullRequest.pullRequestId}"
            val content = contentManager.factory.createContent(panel, tabTitle, false).apply {
                isCloseable = true
                description = pullRequest.title
            }
            contentManager.addContent(content)
            contentManager.setSelectedContent(content, true)

            toolWindow.activate(null)
        }
    }

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val pullRequestToolWindow = PullRequestToolWindow(project)
        val content = toolWindow.contentManager.factory.createContent(
            pullRequestToolWindow.getContent(), "Pull Requests", false
        ).apply {
            isCloseable = false          // main list tab is never closable
        }
        toolWindow.contentManager.addContent(content)

        // Load PRs when the list tab becomes visible
        toolWindow.addContentManagerListener(object : ContentManagerListener {
            override fun selectionChanged(event: ContentManagerEvent) {
                if (event.content == content &&
                    event.operation == ContentManagerEvent.ContentOperation.add
                ) {
                    pullRequestToolWindow.loadPullRequestsIfNeeded()
                }
            }
        })

        // Dispose resources when content is removed
        toolWindow.contentManager.addContentManagerListener(object : ContentManagerListener {
            override fun contentRemoved(event: ContentManagerEvent) {
                if (event.content == content) {
                    pullRequestToolWindow.dispose()
                    return
                }

                // Clean up review tab entries
                val removedComponent = event.content.component
                if (removedComponent is PrReviewTabPanel) {
                    removedComponent.dispose()
                    reviewTabs.entries.removeIf { it.value == removedComponent }
                }
            }
        })
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
