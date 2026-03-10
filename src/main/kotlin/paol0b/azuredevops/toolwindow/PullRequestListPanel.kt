package paol0b.azuredevops.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.actions.AbandonPullRequestAction
import paol0b.azuredevops.actions.CompletePullRequestAction
import paol0b.azuredevops.actions.ConvertToDraftPullRequestAction
import paol0b.azuredevops.actions.SetAutoCompletePullRequestAction
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AvatarService
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.toolwindow.filters.PullRequestFilterPanel
import paol0b.azuredevops.toolwindow.filters.PullRequestSearchValue
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Panel that shows the list of Pull Requests using a JBList with
 * GitHub-style two-line cell rendering and a filter bar.
 */
class PullRequestListPanel(
    private val project: Project,
    private val onSelectionChanged: (PullRequest?) -> Unit
) {

    private val panel: JPanel
    private val listModel: DefaultListModel<PullRequest>
    private val prList: JBList<PullRequest>
    private val statusLabel: JLabel
    private val filterPanel: PullRequestFilterPanel

    private var lastSelectedPrId: Int? = null
    private var cachedPullRequests: List<PullRequest> = emptyList()
    private var lastLoadedPullRequests: List<PullRequest> = emptyList()
    private var isErrorState: Boolean = false
    private var currentUserId: String? = null

    // Derived from filter panel state
    private var currentSearchValue = PullRequestSearchValue.DEFAULT

    init {
        listModel = DefaultListModel()

        val avatarService = AvatarService.getInstance(project)
        val cellRenderer = PullRequestListCellRenderer(avatarService) { currentSearchValue.showAllOrg }

        prList = JBList(listModel).apply {
            this.cellRenderer = cellRenderer
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            border = JBUI.Borders.empty()
        }

        prList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val pr = prList.selectedValue
                lastSelectedPrId = pr?.pullRequestId
                onSelectionChanged(pr)
            }
        }

        prList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val pr = prList.selectedValue ?: return
                    PullRequestToolWindowFactory.openPrReviewTab(project, pr)
                }
            }
        })

        statusLabel = JLabel("Ready").apply {
            border = JBUI.Borders.empty(8, 12)
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.GRAY
        }

        // GitHub-style filter panel
        filterPanel = PullRequestFilterPanel(project) { newFilter ->
            onFilterChanged(newFilter)
        }

        val scrollPane = JBScrollPane(prList).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 16
        }

        panel = JPanel(BorderLayout()).apply {
            add(filterPanel.getComponent(), BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
            minimumSize = Dimension(250, 0)
        }
    }

    fun getComponent(): JPanel = panel

    /**
     * Called when the filter panel reports a new filter value.
     */
    private fun onFilterChanged(newValue: PullRequestSearchValue) {
        val statusChanged = currentSearchValue.state != newValue.state
        val orgChanged = currentSearchValue.showAllOrg != newValue.showAllOrg
        currentSearchValue = newValue

        if (statusChanged || orgChanged) {
            refreshPullRequests()
        } else {
            applyClientFilters()
        }
    }

    fun refreshPullRequests() {
        statusLabel.text = "Loading Pull Requests..."
        statusLabel.icon = AllIcons.Process.Step_1

        val selectedPrId = getSelectedPullRequest()?.pullRequestId ?: lastSelectedPrId
        val apiStatus = currentSearchValue.state?.apiValue ?: "active"
        val showAllOrg = currentSearchValue.showAllOrg

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Loading Pull Requests...", false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val apiClient = AzureDevOpsApiClient.getInstance(project)
                    val pullRequests = if (showAllOrg) {
                        apiClient.getAllOrganizationPullRequests(status = apiStatus, top = 100)
                    } else {
                        apiClient.getPullRequests(status = apiStatus)
                    }
                    val resolvedCurrentUserId = apiClient.getCurrentUserIdCached()

                    ApplicationManager.getApplication().invokeLater {
                        currentUserId = resolvedCurrentUserId
                        cachedPullRequests = pullRequests
                        lastLoadedPullRequests = pullRequests
                        filterPanel.updateAuthorsFromPullRequests(pullRequests)
                        val filtered = applyAllFilters(pullRequests)
                        updateList(filtered, selectedPrId)
                        updateStatusLabel(filtered.size, pullRequests.size)
                        isErrorState = false
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        isErrorState = true
                        listModel.clear()
                        val isConfigError = e.message?.contains("not configured", ignoreCase = true) == true
                        if (isConfigError) {
                            statusLabel.text = "Azure DevOps not configured"
                            statusLabel.icon = AllIcons.General.Warning
                        } else {
                            statusLabel.text = "Error: ${e.message}"
                            statusLabel.icon = AllIcons.General.Error
                        }
                    }
                }
            }
        })
    }

    fun getSelectedPullRequest(): PullRequest? = prList.selectedValue

    // ---- Client-side filtering ----

    private fun applyClientFilters() {
        if (isErrorState) return
        val selectedPrId = getSelectedPullRequest()?.pullRequestId ?: lastSelectedPrId
        val filtered = applyAllFilters(lastLoadedPullRequests)
        updateList(filtered, selectedPrId)
        updateStatusLabel(filtered.size, lastLoadedPullRequests.size)
    }

    private fun applyAllFilters(pullRequests: List<PullRequest>): List<PullRequest> {
        var result = pullRequests
        val sv = currentSearchValue

        // Text search
        val query = sv.searchQuery
        if (!query.isNullOrBlank()) {
            val normalizedQuery = query.lowercase()
            result = result.filter { pr ->
                pr.title.lowercase().contains(normalizedQuery) ||
                (pr.createdBy?.displayName?.lowercase()?.contains(normalizedQuery) == true) ||
                (pr.createdBy?.uniqueName?.lowercase()?.contains(normalizedQuery) == true)
            }
        }

        // Author filter
        val author = sv.author
        if (author != null) {
            result = if (author.id == "@me") {
                result.filter { pr -> pr.isCreatedByUser(currentUserId) }
            } else {
                result.filter { pr -> pr.createdBy?.id == author.id || pr.createdBy?.displayName == author.displayName }
            }
        }

        // Project filter
        val projFilter = sv.projectFilter
        if (projFilter != null) {
            result = result.filter { pr ->
                pr.repository?.project?.id == projFilter.id || pr.repository?.project?.name == projFilter.name
            }
        }

        // Repository filter
        val repoFilter = sv.repositoryFilter
        if (repoFilter != null) {
            result = result.filter { pr ->
                pr.repository?.id == repoFilter.id || pr.repository?.name == repoFilter.name
            }
        }

        // Review filter
        val review = sv.review
        if (review != null) {
            result = result.filter { pr -> matchesReviewFilter(pr, review) }
        }

        // Sort
        result = when (sv.sort) {
            PullRequestSearchValue.Sort.OLDEST -> result.sortedBy { it.pullRequestId }
            PullRequestSearchValue.Sort.RECENTLY_UPDATED -> result
            else -> result.sortedByDescending { it.pullRequestId }
        }

        return result
    }

    private fun matchesReviewFilter(pr: PullRequest, review: PullRequestSearchValue.ReviewState): Boolean {
        val reviewers = pr.reviewers ?: emptyList()
        return when (review) {
            PullRequestSearchValue.ReviewState.NO_REVIEW -> {
                reviewers.isEmpty() || reviewers.all { it.vote == 0 || it.vote == null }
            }
            PullRequestSearchValue.ReviewState.APPROVED -> {
                reviewers.any { it.vote == 10 || it.vote == 5 }
            }
            PullRequestSearchValue.ReviewState.CHANGES_REQUESTED -> {
                reviewers.any { it.vote == -5 || it.vote == -10 }
            }
            PullRequestSearchValue.ReviewState.REVIEWED_BY_YOU -> {
                reviewers.any { it.id == currentUserId && (it.vote != null && it.vote != 0) }
            }
        }
    }

    // ---- List management ----

    private fun updateList(pullRequests: List<PullRequest>, previouslySelectedPrId: Int? = null) {
        listModel.clear()
        pullRequests.forEach { listModel.addElement(it) }

        if (previouslySelectedPrId != null) {
            for (i in 0 until listModel.size) {
                if (listModel.getElementAt(i).pullRequestId == previouslySelectedPrId) {
                    prList.selectedIndex = i
                    prList.ensureIndexIsVisible(i)
                    break
                }
            }
        }
    }

    private fun showContextMenu(e: MouseEvent) {
        val index = prList.locationToIndex(e.point)
        if (index < 0) return
        val cellBounds = prList.getCellBounds(index, index) ?: return
        if (!cellBounds.contains(e.point)) return
        val pr = listModel.getElementAt(index)
        prList.selectedIndex = index

        val popup = JBPopupMenu()

        popup.add(JMenuItem("Open Review in Tab").apply {
            addActionListener { PullRequestToolWindowFactory.openPrReviewTab(project, pr) }
        })
        popup.addSeparator()
        popup.add(JMenuItem("Enter This Branch").apply {
            addActionListener {
                paol0b.azuredevops.services.PullRequestBranchService.getInstance(project).enterPullRequestBranch(pr)
            }
        })

        if (pr.isActive()) {
            val isMyPr = pr.isCreatedByUser(currentUserId)
            val showAbandonPr = isMyPr
            val showCompletePR = pr.isReadyToComplete()
            val showAutoComplete = !pr.hasAutoComplete() && !pr.isReadyToComplete()
            val showConvertToDraft = isMyPr && pr.isDraft != true
            val showPublishPr = isMyPr && pr.isDraft == true
            if (showAbandonPr || showCompletePR || showAutoComplete || showConvertToDraft || showPublishPr) popup.addSeparator()

            if (showConvertToDraft) {
                popup.add(JMenuItem("Convert to Draft").apply {
                    addActionListener {
                        ConvertToDraftPullRequestAction(pr, currentUserId, convertToDraft = true) { refreshPullRequests() }.perform(project)
                    }
                })
            }
            if (showPublishPr) {
                popup.add(JMenuItem("Publish PR").apply {
                    addActionListener {
                        ConvertToDraftPullRequestAction(pr, currentUserId, convertToDraft = false) { refreshPullRequests() }.perform(project)
                    }
                })
            }
            if (showAbandonPr) {
                popup.add(JMenuItem("Abandon PR...").apply {
                    addActionListener {
                        AbandonPullRequestAction(pr, currentUserId) { refreshPullRequests() }.performAbandonPR(project)
                    }
                })
            }
            if (showCompletePR) {
                popup.add(JMenuItem("Complete PR...").apply {
                    addActionListener {
                        CompletePullRequestAction(pr) { refreshPullRequests() }.performCompletePR(project)
                    }
                })
            }
            if (showAutoComplete) {
                popup.add(JMenuItem("Set Auto-Complete...").apply {
                    addActionListener {
                        SetAutoCompletePullRequestAction(pr) { refreshPullRequests() }.performSetAutoComplete(project)
                    }
                })
            }
        }

        popup.show(prList, e.x, e.y)
    }

    private fun updateStatusLabel(filteredCount: Int, totalCount: Int) {
        statusLabel.icon = AllIcons.General.InspectionsOK
        statusLabel.text = if (filteredCount < totalCount) {
            "Showing $filteredCount of $totalCount Pull Request(s)"
        } else {
            "Loaded $totalCount Pull Request(s)"
        }
    }
}
