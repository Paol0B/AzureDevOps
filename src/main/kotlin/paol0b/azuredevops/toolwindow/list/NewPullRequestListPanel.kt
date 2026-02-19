package paol0b.azuredevops.toolwindow.list

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.actions.CompletePullRequestAction
import paol0b.azuredevops.actions.SetAutoCompletePullRequestAction
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AvatarService
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.PullRequestBranchService
import paol0b.azuredevops.toolwindow.PullRequestToolWindowFactory
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * New GitHub-style Pull Request list panel.
 * Inspired by GHPRListPanelFactory from the JetBrains GitHub plugin.
 *
 * Uses a JBList with rich cell rendering, search bar with filters,
 * quick-filter buttons, and org/repo mode toggle.
 */
class NewPullRequestListPanel(
    private val project: Project,
    private val onSelectionChanged: (PullRequest?) -> Unit
) {
    private val logger = Logger.getInstance(NewPullRequestListPanel::class.java)
    private val avatarService = AvatarService.getInstance(project)

    // ── State ──
    private val searchStateHolder = PullRequestSearchStateHolder()
    private var allPullRequests: List<PullRequest> = emptyList()  // Full unfiltered list from API
    private var filteredPullRequests: List<PullRequest> = emptyList()
    private var isLoading = false
    private var lastSelectedPrId: Int? = null
    private var cachedPullRequests: List<PullRequest> = emptyList()
    private var isErrorState = false

    // ── UI Components ──
    private val listModel = DefaultListModel<PullRequest>()
    private val prList = JBList(listModel).apply {
        cellRenderer = PullRequestListRenderer(avatarService) { searchStateHolder.state.orgMode }
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        fixedCellHeight = JBUI.scale(88)
        background = UIUtil.getListBackground()
        emptyText.text = "No pull requests found"
        emptyText.appendSecondaryText("Loading pull requests...", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
    }

    private val searchPanel = PullRequestSearchPanel(searchStateHolder) {
        refreshPullRequests()
    }

    private val statusBar = JPanel(BorderLayout()).apply {
        border = JBUI.Borders.empty(JBUI.scale(4), JBUI.scale(10))
        isOpaque = true
        background = UIUtil.getPanelBackground()
    }
    private val statusLabel = JBLabel("Ready").apply {
        foreground = JBColor.GRAY
        font = UIUtil.getLabelFont().deriveFont(UIUtil.getFontSize(UIUtil.FontSize.SMALL))
    }
    private val loadingIndicator = JProgressBar().apply {
        isIndeterminate = true
        isVisible = false
        preferredSize = Dimension(Int.MAX_VALUE, JBUI.scale(2))
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(2))
    }

    private val mainPanel: JPanel

    init {
        // ── Setup search state listener ──
        searchStateHolder.addListener(object : PullRequestSearchListener {
            override fun onApiFilterChanged(state: PullRequestSearchState) {
                refreshPullRequests()
            }

            override fun onClientFilterChanged(state: PullRequestSearchState) {
                applyFiltersAndUpdateList()
            }
        })

        // ── List selection listener ──
        prList.addListSelectionListener {
            if (!it.valueIsAdjusting) {
                val selected = prList.selectedValue
                lastSelectedPrId = selected?.pullRequestId
                onSelectionChanged(selected)
            }
        }

        // ── Double-click opens PR review tab ──
        prList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val index = prList.locationToIndex(e.point)
                    if (index >= 0) {
                        val pr = listModel.getElementAt(index)
                        PullRequestToolWindowFactory.openPrReviewTab(project, pr)
                    }
                }
            }

            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
        })

        // ── Status bar ──
        statusBar.add(statusLabel, BorderLayout.CENTER)

        // ── Main panel assembly ──
        mainPanel = JPanel(BorderLayout()).apply {
            // Top: search/filter panel
            add(searchPanel, BorderLayout.NORTH)

            // Center: loading indicator + list
            val listContainer = JPanel(BorderLayout()).apply {
                add(loadingIndicator, BorderLayout.NORTH)
                add(JBScrollPane(prList).apply {
                    border = JBUI.Borders.empty()
                    verticalScrollBar.unitIncrement = 16
                }, BorderLayout.CENTER)
            }
            add(listContainer, BorderLayout.CENTER)

            // Bottom: status bar
            add(statusBar, BorderLayout.SOUTH)
        }
    }

    fun getComponent(): JPanel = mainPanel

    fun getSelectedPullRequest(): PullRequest? = prList.selectedValue

    fun getShowAllOrganizationPrs(): Boolean = searchStateHolder.state.orgMode

    fun setShowAllOrganizationPrs(enabled: Boolean) {
        searchStateHolder.updateOrgMode(enabled)
        searchPanel.setState(searchStateHolder.state)
    }

    fun setFilterStatus(status: String) {
        val filter = StatusFilter.fromApiValue(status)
        searchStateHolder.updateStatusFilter(filter)
        searchPanel.setState(searchStateHolder.state)
    }

    fun getFilterStatus(): String = searchStateHolder.state.statusFilter.apiValue

    // ── Data Loading ──

    fun refreshPullRequests() {
        if (isLoading) return

        isLoading = true
        showLoading(true)
        statusLabel.text = "Loading Pull Requests..."
        statusLabel.icon = null

        val state = searchStateHolder.state
        val selectedPrId = getSelectedPullRequest()?.pullRequestId ?: lastSelectedPrId

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Loading Pull Requests...", false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val apiClient = AzureDevOpsApiClient.getInstance(project)
                    val pullRequests = if (state.orgMode) {
                        apiClient.getAllOrganizationPullRequests(
                            status = state.statusFilter.apiValue,
                            top = 100
                        )
                    } else {
                        apiClient.getPullRequests(
                            status = state.statusFilter.apiValue
                        )
                    }

                    ApplicationManager.getApplication().invokeLater {
                        isLoading = false
                        showLoading(false)

                        if (isErrorState || hasDataChanged(pullRequests)) {
                            allPullRequests = pullRequests
                            cachedPullRequests = pullRequests
                            isErrorState = false

                            // Update filter dropdowns with available authors/reviewers
                            searchPanel.updateFilterOptions(
                                PullRequestFilterUtil.extractAuthors(pullRequests),
                                PullRequestFilterUtil.extractReviewers(pullRequests)
                            )

                            // Preload avatars
                            avatarService.preloadAvatars(
                                pullRequests.flatMap { pr ->
                                    listOfNotNull(pr.createdBy?.imageUrl) +
                                            (pr.reviewers?.mapNotNull { it.imageUrl } ?: emptyList())
                                }
                            )

                            applyFiltersAndUpdateList(selectedPrId)

                            statusLabel.text = "${pullRequests.size} Pull Request(s)"
                            statusLabel.icon = AllIcons.General.InspectionsOK
                        } else {
                            statusLabel.text = "${pullRequests.size} Pull Request(s) (up to date)"
                            statusLabel.icon = AllIcons.General.InspectionsOK
                        }
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        isLoading = false
                        showLoading(false)
                        isErrorState = true

                        val isConfigError = e.message?.contains("not configured", ignoreCase = true) == true
                        if (isConfigError) {
                            listModel.clear()
                            prList.emptyText.text = "Azure DevOps not configured"
                            prList.emptyText.appendSecondaryText(
                                "Go to Settings → Tools → Azure DevOps Accounts to configure",
                                SimpleTextAttributes.GRAYED_ATTRIBUTES, null
                            )
                            statusLabel.text = "Not configured"
                            statusLabel.icon = AllIcons.General.Warning
                        } else {
                            listModel.clear()
                            prList.emptyText.text = "Error loading pull requests"
                            prList.emptyText.appendSecondaryText(
                                e.message ?: "Unknown error", SimpleTextAttributes.GRAYED_ATTRIBUTES, null
                            )
                            statusLabel.text = "Error: ${e.message}"
                            statusLabel.icon = AllIcons.General.Error
                        }
                    }
                }
            }
        })
    }

    // ── Client-side filtering ──

    private fun applyFiltersAndUpdateList(restoreSelectionPrId: Int? = null) {
        val state = searchStateHolder.state
        filteredPullRequests = PullRequestFilterUtil.applyFilters(allPullRequests, state)

        listModel.clear()
        filteredPullRequests.forEach { listModel.addElement(it) }

        // Update empty text
        if (filteredPullRequests.isEmpty() && allPullRequests.isNotEmpty()) {
            prList.emptyText.text = "No pull requests match the current filters"
            prList.emptyText.appendSecondaryText("Try adjusting your filters", SimpleTextAttributes.GRAYED_ATTRIBUTES, null)
        } else if (filteredPullRequests.isEmpty()) {
            prList.emptyText.text = "No pull requests found"
        }

        // Restore selection
        val prIdToSelect = restoreSelectionPrId ?: lastSelectedPrId
        if (prIdToSelect != null) {
            for (i in 0 until listModel.size) {
                if (listModel.getElementAt(i).pullRequestId == prIdToSelect) {
                    prList.selectedIndex = i
                    prList.ensureIndexIsVisible(i)
                    break
                }
            }
        }
    }

    private fun hasDataChanged(newPullRequests: List<PullRequest>): Boolean {
        if (cachedPullRequests.size != newPullRequests.size) return true

        val cachedMap = cachedPullRequests.associateBy { it.pullRequestId }
        for (newPr in newPullRequests) {
            val cachedPr = cachedMap[newPr.pullRequestId] ?: return true
            if (cachedPr.status != newPr.status ||
                cachedPr.title != newPr.title ||
                cachedPr.isDraft != newPr.isDraft ||
                cachedPr.reviewers?.size != newPr.reviewers?.size
            ) return true

            val cachedReviewers = cachedPr.reviewers?.associateBy { it.id } ?: emptyMap()
            for (reviewer in newPr.reviewers ?: emptyList()) {
                if (cachedReviewers[reviewer.id]?.vote != reviewer.vote) return true
            }
        }
        return false
    }

    private fun showLoading(loading: Boolean) {
        loadingIndicator.isVisible = loading
    }

    // ── Context Menu ──

    private fun showContextMenu(e: MouseEvent) {
        val index = prList.locationToIndex(e.point)
        if (index < 0) return
        val pr = listModel.getElementAt(index)
        if (!pr.isActive()) return

        prList.selectedIndex = index

        val popup = JBPopupMenu()

        // Open Review in Tab
        popup.add(JMenuItem("Open Review in Tab").apply {
            icon = AllIcons.Actions.Preview
            addActionListener {
                PullRequestToolWindowFactory.openPrReviewTab(project, pr)
            }
        })

        popup.addSeparator()

        // Enter This Branch
        popup.add(JMenuItem("Enter This Branch").apply {
            icon = AllIcons.Vcs.BranchNode
            addActionListener {
                PullRequestBranchService.getInstance(project).enterPullRequestBranch(pr)
            }
        })

        // Complete / Auto-Complete
        val showComplete = pr.isReadyToComplete()
        val showAutoComplete = !pr.hasAutoComplete() && !pr.isReadyToComplete()

        if (showComplete || showAutoComplete) {
            popup.addSeparator()
        }

        if (showComplete) {
            popup.add(JMenuItem("Complete PR...").apply {
                icon = AllIcons.RunConfigurations.TestPassed
                addActionListener {
                    CompletePullRequestAction(pr) { refreshPullRequests() }.performCompletePR(project)
                }
            })
        }

        if (showAutoComplete) {
            popup.add(JMenuItem("Set Auto-Complete...").apply {
                icon = AllIcons.Actions.Execute
                addActionListener {
                    SetAutoCompletePullRequestAction(pr) { refreshPullRequests() }.performSetAutoComplete(project)
                }
            })
        }

        popup.show(prList, e.x, e.y)
    }
}
