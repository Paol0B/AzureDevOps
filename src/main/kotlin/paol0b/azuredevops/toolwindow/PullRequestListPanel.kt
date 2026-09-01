package paol0b.azuredevops.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
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
import paol0b.azuredevops.model.stableOrganizationKey
import paol0b.azuredevops.services.AvatarService
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.AzureDevOpsConfigService
import paol0b.azuredevops.services.AzureDevOpsSettingsService
import paol0b.azuredevops.services.PullRequestQueryCriteria
import paol0b.azuredevops.toolwindow.filters.PullRequestFilterPanel
import paol0b.azuredevops.toolwindow.filters.PullRequestSearchValue
import paol0b.azuredevops.util.NotificationUtil
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.util.concurrent.atomic.AtomicLong
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

    // Each refresh increments this; streaming page/complete callbacks compare their captured
    // generation against the current one and silently no-op if the user has since triggered
    // another refresh. Without this, late pages from an aborted load would clobber the new
    // results.
    private val refreshGeneration = AtomicLong(0)

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

        // Restore the persisted project filter selection so the very first refresh already
        // scopes to the projects the user last cared about, instead of re-fetching everything
        // and then narrowing once the filter chip wakes up.
        val persistedProjectIds = AzureDevOpsSettingsService.getInstance(project).state
            .prFilterSelectedProjectIds
            .toSet()
        if (persistedProjectIds.isNotEmpty()) {
            currentSearchValue = currentSearchValue.copy(selectedProjectIds = persistedProjectIds)
            filterPanel.setInitialFilter(currentSearchValue)
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
        val projectsChanged = currentSearchValue.selectedProjectIds != newValue.selectedProjectIds
        val involvementChanged = currentSearchValue.involvement != newValue.involvement
        currentSearchValue = newValue

        if (projectsChanged) {
            // Persist immediately so a hard restart preserves the user's narrowed view.
            AzureDevOpsSettingsService.getInstance(project).state.prFilterSelectedProjectIds =
                newValue.selectedProjectIds.toMutableList()
        }

        if (statusChanged || orgChanged || projectsChanged || involvementChanged) {
            refreshPullRequests()
        } else {
            applyClientFilters()
        }
    }

    fun refreshPullRequests() {
        val generation = refreshGeneration.incrementAndGet()
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
                    val resolvedCurrentUserId = apiClient.getCurrentUserIdCached()

                    if (currentSearchValue.involvement != null && resolvedCurrentUserId.isNullOrBlank()) {
                        error("Could not resolve your Azure DevOps identity for the selected PR filter")
                    }

                    val onPage: (List<PullRequest>, List<PullRequest>) -> Unit = { _, accumulated ->
                        ApplicationManager.getApplication().invokeLater {
                            if (refreshGeneration.get() != generation) return@invokeLater
                            applyStreamingUpdate(
                                accumulated = accumulated,
                                resolvedCurrentUserId = resolvedCurrentUserId,
                                originalSelectedPrId = selectedPrId,
                                isFinal = false
                            )
                        }
                    }

                    val onComplete: (List<PullRequest>) -> Unit = { total ->
                        ApplicationManager.getApplication().invokeLater {
                            if (refreshGeneration.get() != generation) return@invokeLater
                            applyStreamingUpdate(
                                accumulated = total,
                                resolvedCurrentUserId = resolvedCurrentUserId,
                                originalSelectedPrId = selectedPrId,
                                isFinal = true
                            )
                        }
                    }

                    val criteriaList = currentSearchValue.serverQueryCriteria(resolvedCurrentUserId)
                    val selectedProjectIds = currentSearchValue.selectedProjectIds
                    val aggregated = mutableListOf<PullRequest>()
                    val seenKeys = HashSet<String>()

                    // "Relevant to me" is an OR query. Azure DevOps ANDs creatorId and
                    // reviewerId, so issue two small server-filtered requests and merge them.
                    // The same aggregator also handles multi-project selections.
                    val mergePage: (List<PullRequest>) -> Unit = { page ->
                        val fresh = page.filter { seenKeys.add(it.stableOrganizationKey()) }
                        if (fresh.isNotEmpty()) {
                            aggregated.addAll(fresh)
                            onPage(fresh, aggregated.toList())
                        }
                    }

                    criteriaList.forEach { criteria ->
                        if (selectedProjectIds.isNotEmpty()) {
                            selectedProjectIds.forEach { projectId ->
                                apiClient.getProjectPullRequestsStreaming(
                                    projectIdOrName = projectId,
                                    status = apiStatus,
                                    criteria = criteria,
                                    onPage = { page, _ -> mergePage(page) },
                                    onComplete = { /* merged after every scoped request */ }
                                )
                            }
                        } else if (showAllOrg) {
                            apiClient.getAllOrganizationPullRequestsStreaming(
                                status = apiStatus,
                                criteria = criteria,
                                onPage = { page, _ -> mergePage(page) },
                                onComplete = { /* merged after every scoped request */ }
                            )
                        } else {
                            apiClient.getPullRequestsStreaming(
                                status = apiStatus,
                                criteria = criteria,
                                onPage = { page, _ -> mergePage(page) },
                                onComplete = { /* merged after every scoped request */ }
                            )
                        }
                    }
                    onComplete(aggregated.toList())
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        if (refreshGeneration.get() != generation) return@invokeLater
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

    /**
     * Folds the latest streaming snapshot into the panel state on the EDT.
     *
     * Called once per arriving page (with `isFinal = false`) and once more when pagination
     * exits (with `isFinal = true`). The list is rebuilt from scratch each call rather than
     * appended-to because sort/filter outcomes can depend on the full set (e.g. an "oldest
     * first" sort puts newly-arrived older PRs at the top).
     */
    private fun applyStreamingUpdate(
        accumulated: List<PullRequest>,
        resolvedCurrentUserId: String?,
        originalSelectedPrId: Int?,
        isFinal: Boolean
    ) {
        currentUserId = resolvedCurrentUserId
        cachedPullRequests = accumulated
        lastLoadedPullRequests = accumulated
        filterPanel.updateAuthorsFromPullRequests(accumulated)
        val filtered = applyAllFilters(accumulated)
        // Prefer the user's live selection if they've clicked something while loading;
        // otherwise restore the selection captured at the start of this refresh.
        val selectionToRestore = getSelectedPullRequest()?.pullRequestId ?: originalSelectedPrId
        updateList(filtered, selectionToRestore)
        if (isFinal) {
            updateStatusLabel(filtered.size, accumulated.size)
        } else {
            statusLabel.icon = AllIcons.Process.Step_1
            statusLabel.text = "Loading Pull Requests… ${accumulated.size} so far"
        }
        isErrorState = false
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

        // Only the pending-vote part needs a local check. Assignment itself was already
        // evaluated by Azure DevOps using searchCriteria.reviewerId (which also preserves
        // assignments made through reviewer groups).
        if (sv.involvement == PullRequestSearchValue.Involvement.AWAITING_MY_REVIEW) {
            result = result.filter { it.isAwaitingReviewFrom(currentUserId) }
        }

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

        // Project filter (defensive — the server has already done the filtering for the
        // per-project fetch path, but this also covers the org-wide / repo-scoped paths
        // and keeps applyClientFilters() correct if only the project chip changed).
        val projectIds = sv.selectedProjectIds
        if (projectIds.isNotEmpty()) {
            result = result.filter { pr ->
                pr.repository?.project?.id in projectIds
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
        popup.add(JMenuItem("Copy PR Link").apply {
            addActionListener {
                val url = pullRequestWebUrl(pr)
                if (url != null) {
                    CopyPasteManager.getInstance().setContents(StringSelection(url))
                    NotificationUtil.info(project, "Pull Request link copied", url)
                } else {
                    NotificationUtil.warning(
                        project,
                        "Could not copy Pull Request link",
                        "The repository or project could not be resolved for this pull request."
                    )
                }
            }
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

    /**
     * Builds the Azure DevOps web URL for a pull request. Prefers the repository/project
     * embedded in the PR; falls back to the configured project/repository when those are
     * missing. Mirrors PullRequestToolWindow.getPullRequestWebUrl. Returns null when the
     * URL cannot be resolved.
     */
    private fun pullRequestWebUrl(pr: PullRequest): String? {
        val apiClient = AzureDevOpsApiClient.getInstance(project)

        pr.repository?.let { repo ->
            if (repo.name != null && repo.project?.name != null) {
                return apiClient.buildPullRequestWebUrl(repo.project.name, repo.name, pr.pullRequestId)
            }
        }

        val config = AzureDevOpsConfigService.getInstance(project).getConfig()
        if (!config.isValid()) return null
        return apiClient.buildPullRequestWebUrl(config.project, config.repository, pr.pullRequestId)
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
