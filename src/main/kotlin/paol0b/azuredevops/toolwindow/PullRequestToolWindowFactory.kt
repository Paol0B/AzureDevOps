package paol0b.azuredevops.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.toolwindow.create.CreatePullRequestPanel
import paol0b.azuredevops.toolwindow.metrics.PrMetricsDashboardPanel
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
        private const val METRICS_TAB_TITLE = "Metrics & Trends"
        private const val CREATE_PR_TAB_TITLE = "New Pull Request"

        /** Active review tab panels keyed by pullRequestId */
        private val reviewTabs = ConcurrentHashMap<Int, PrReviewTabPanel>()

        /** Singleton metrics dashboard per project (keyed by project hash) */
        private val metricsTabs = ConcurrentHashMap<Int, PrMetricsDashboardPanel>()

        /** Singleton create PR panel per project (keyed by project hash) */
        private val createPrTabs = ConcurrentHashMap<Int, CreatePullRequestPanel>()

        /**
         * Open (or focus) the Create Pull Request tab.
         */
        fun openCreatePrTab(project: Project) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            val contentManager = toolWindow.contentManager

            val projectKey = System.identityHashCode(project)

            // Check if tab already exists
            val existing = createPrTabs[projectKey]
            if (existing != null) {
                for (content in contentManager.contents) {
                    if (content.component == existing) {
                        contentManager.setSelectedContent(content, true)
                        toolWindow.activate(null)
                        return
                    }
                }
                createPrTabs.remove(projectKey)
            }

            // Create new Create PR tab
            val panel = CreatePullRequestPanel(project)
            createPrTabs[projectKey] = panel

            val content = contentManager.factory.createContent(panel, CREATE_PR_TAB_TITLE, false).apply {
                isCloseable = true
                description = "Create a new Pull Request"
            }

            // When PR is created, close this tab and refresh
            panel.onPullRequestCreated = {
                contentManager.removeContent(content, true)
                createPrTabs.remove(projectKey)
            }

            contentManager.addContent(content)
            contentManager.setSelectedContent(content, true)
            toolWindow.activate(null)
        }

        /**
         * Open (or focus) the Metrics & Trends dashboard tab.
         */
        fun openMetricsDashboard(project: Project) {
            val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            val contentManager = toolWindow.contentManager

            val projectKey = System.identityHashCode(project)

            // Check if tab already exists
            val existing = metricsTabs[projectKey]
            if (existing != null) {
                for (content in contentManager.contents) {
                    if (content.component == existing.getComponent()) {
                        contentManager.setSelectedContent(content, true)
                        toolWindow.activate(null)
                        return
                    }
                }
                metricsTabs.remove(projectKey)
            }

            // Create new metrics tab
            val panel = PrMetricsDashboardPanel(project)
            metricsTabs[projectKey] = panel

            val content = contentManager.factory.createContent(panel.getComponent(), METRICS_TAB_TITLE, false).apply {
                isCloseable = true
                description = "PR metrics and trends dashboard"
            }
            contentManager.addContent(content)
            contentManager.setSelectedContent(content, true)
            toolWindow.activate(null)

            // Auto-load metrics on first open
            panel.loadMetrics()
        }

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

            val tabTitle = "#${pullRequest.pullRequestId}"
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

        // Place actions in the native tool window title bar (like JetBrains GitHub plugin)
        toolWindow.setTitleActions(listOf(
            object : AnAction("New Pull Request", "Create a new Pull Request", AllIcons.General.Add) {
                override fun actionPerformed(e: AnActionEvent) {
                    openCreatePrTab(project)
                }
            },
            object : AnAction("Refresh", "Refresh Pull Requests", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    pullRequestToolWindow.refreshPullRequests()
                    // Also refresh the metrics tab if it's open
                    val projectKey = System.identityHashCode(project)
                    metricsTabs[projectKey]?.loadMetrics()
                }
            },
            object : AnAction("Open in Browser", "Open selected PR in browser", AllIcons.Ide.External_link_arrow) {
                override fun actionPerformed(e: AnActionEvent) {
                    pullRequestToolWindow.openSelectedPrInBrowser()
                }
                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = pullRequestToolWindow.getSelectedPullRequest() != null
                }
                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            },
            object : AnAction("Metrics & Trends", "Open PR metrics dashboard", AllIcons.Actions.GroupByModule) {
                override fun actionPerformed(e: AnActionEvent) {
                    openMetricsDashboard(project)
                }
            }
        ))

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

                // Clean up metrics tab entries
                metricsTabs.entries.removeIf { it.value.getComponent() == removedComponent }

                // Clean up create PR tab entries
                if (removedComponent is CreatePullRequestPanel) {
                    removedComponent.dispose()
                    createPrTabs.entries.removeIf { it.value == removedComponent }
                }
            }
        })
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}
