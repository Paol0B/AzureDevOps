package paol0b.azuredevops.toolwindow.filters

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBThinOverlappingScrollBar
import com.intellij.ui.scale.JBUIScale
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.checkout.AzureDevOpsCloneApiClient
import paol0b.azuredevops.checkout.ProjectFilterPopup
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AvatarService
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.*
import javax.swing.*

/**
 * Complete filter panel for the PR list, modeled after the JetBrains GitHub plugin's
 * ReviewListSearchPanelFactory. Provides:
 *
 * - A search text field at the top
 * - A horizontally scrollable filter bar with: Quick Filter | State | Author | Review | Sort
 * - When the tool window is too narrow, filters become horizontally scrollable
 */
class PullRequestFilterPanel(
    private val project: Project,
    private val onFilterChanged: (PullRequestSearchValue) -> Unit
) {

    private val panel: JPanel
    private var currentValue = PullRequestSearchValue.DEFAULT

    // Search field
    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search pull requests"
    }

    // Filter chips
    private val stateChip: FilterChipComponent
    private val projectChip: FilterChipComponent
    private val repositoryChip: FilterChipComponent
    private val authorChip: FilterChipComponent
    private val reviewChip: FilterChipComponent
    private val sortChip: FilterChipComponent

    // Quick filter button
    private val filterBadgeIcon = FilterBadgeIcon(AllIcons.General.Filter)
    private val quickFilterButton: JButton

    // Cached lists. Authors and repositories are still derived from loaded PR data (the
    // filter values are only meaningful for things that actually appear in PRs). Projects,
    // however, are pre-fetched directly from the org's projects endpoint so the user can
    // pick any project — even one with zero PRs in the current state — to narrow the load.
    //
    // We keep two sources for projects: the authoritative API list, and a fallback derived
    // from loaded PRs. If the API call fails (commonly: PAT missing the `vso.project` /
    // "Project and Team (read)" scope) we still want the multi-select popup to be usable
    // — populated from whatever projects the user's PRs already revealed.
    private var cachedAuthors: List<PullRequestSearchValue.AuthorFilter>? = null
    private var orgProjectsFromApi: List<AzureDevOpsApiClient.OrgProject> = emptyList()
    // Keyed by project id, kept as an accumulating union across every refresh — once we've
    // seen a project from a PR, it stays in the fallback even if a later (narrower) refresh
    // doesn't include it. Without this, narrowing the project filter would visibly shrink
    // the list of projects available in the chip on the next refresh.
    private val projectsFromPrData: MutableMap<String, AzureDevOpsApiClient.OrgProject> = mutableMapOf()
    private var cachedRepositories: List<PullRequestSearchValue.RepositoryFilter> = emptyList()

    private val logger = Logger.getInstance(PullRequestFilterPanel::class.java)

    private enum class LoadState { NOT_STARTED, LOADING, LOADED, FAILED }
    private var orgProjectsLoadState: LoadState = LoadState.NOT_STARTED

    /** Combined view: prefer the authoritative API list; fall back to the accumulated
     *  PR-derived list if empty. The fallback is sorted alphabetically by project name. */
    private val effectiveProjects: List<AzureDevOpsApiClient.OrgProject>
        get() = if (orgProjectsFromApi.isNotEmpty()) orgProjectsFromApi
        else projectsFromPrData.values.sortedBy { it.name.lowercase() }

    init {
        // Create quick filter button (funnel icon)
        quickFilterButton = JButton().apply {
            icon = filterBadgeIcon
            toolTipText = "Quick Filters"
            isFocusPainted = false
            isContentAreaFilled = false
            border = JBUI.Borders.empty(2, 4)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(JBUIScale.scale(28), JBUIScale.scale(24))

            addActionListener {
                showQuickFilterPopup(this)
            }
        }

        // State filter
        stateChip = FilterChipComponent("State",
            onShowPopup = { chip -> showStatePopup(chip) },
            onClear = { updateFilter(currentValue.copy(state = null)) }
        )
        // Set default state
        stateChip.setValue(PullRequestSearchValue.State.OPEN.displayName)

        // Project filter (multi-select). Clearing the chip wipes both project ids and the
        // dependent repository filter, since "all projects" no longer constrains repos.
        projectChip = FilterChipComponent("Project",
            onShowPopup = { chip -> showProjectPopup(chip) },
            onClear = {
                updateFilter(currentValue.copy(selectedProjectIds = emptySet(), repositoryFilter = null))
                repositoryChip.clearValue()
            }
        )

        // Repository filter
        repositoryChip = FilterChipComponent("Repository",
            onShowPopup = { chip -> showRepositoryPopup(chip) },
            onClear = { updateFilter(currentValue.copy(repositoryFilter = null)) }
        )

        // Author filter
        authorChip = FilterChipComponent("Author",
            onShowPopup = { chip -> showAuthorPopup(chip) },
            onClear = { updateFilter(currentValue.copy(author = null)) }
        )

        // Review filter
        reviewChip = FilterChipComponent("Review",
            onShowPopup = { chip -> showReviewPopup(chip) },
            onClear = { updateFilter(currentValue.copy(review = null)) }
        )

        // Sort filter
        sortChip = FilterChipComponent("Sort",
            onShowPopup = { chip -> showSortPopup(chip) },
            onClear = { updateFilter(currentValue.copy(sort = null)) }
        )

        // Search field listener
        searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                val query = searchField.text.trim()
                updateFilter(currentValue.copy(searchQuery = query.ifBlank { null }))
            }
        })

        // Build the horizontally scrollable filter bar
        val filtersContent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(stateChip)
            add(Box.createHorizontalStrut(JBUIScale.scale(4)))
            add(projectChip)
            add(Box.createHorizontalStrut(JBUIScale.scale(4)))
            add(repositoryChip)
            add(Box.createHorizontalStrut(JBUIScale.scale(4)))
            add(authorChip)
            add(Box.createHorizontalStrut(JBUIScale.scale(4)))
            add(reviewChip)
            add(Box.createHorizontalStrut(JBUIScale.scale(4)))
            add(sortChip)
        }

        val filtersScrollPane = JBScrollPane(filtersContent).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBar = JBThinOverlappingScrollBar(Adjustable.HORIZONTAL)
        }

        val filterBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(quickFilterButton, BorderLayout.WEST)
            add(filtersScrollPane, BorderLayout.CENTER)
        }

        panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 10, 4, 10)
            isOpaque = false
            add(searchField, BorderLayout.CENTER)
            add(filterBar, BorderLayout.SOUTH)
        }

        // Kick off the projects fetch on construction so the filter popup is ready to use
        // by the time the user actually clicks it. Failures are non-fatal — the popup will
        // just show a "no projects" message until the next reload.
        fetchOrgProjectsAsync()
    }

    fun getComponent(): JPanel = panel

    fun getCurrentFilter(): PullRequestSearchValue = currentValue

    /**
     * Replaces the panel's filter value without firing [onFilterChanged]. Used by the list
     * panel at startup to restore the persisted project selection before the first refresh
     * runs, so that refresh already knows which projects to scope to.
     */
    fun setInitialFilter(value: PullRequestSearchValue) {
        currentValue = value
        syncChipsFromValue()
        updateBadge()
    }

    private fun fetchOrgProjectsAsync() {
        // Don't pile up concurrent fetches.
        if (orgProjectsLoadState == LoadState.LOADING) return
        orgProjectsLoadState = LoadState.LOADING

        ApplicationManager.getApplication().executeOnPooledThread {
            var result: List<AzureDevOpsApiClient.OrgProject> = emptyList()
            var failure: Throwable? = null
            try {
                result = AzureDevOpsApiClient.getInstance(project).getProjects()
            } catch (e: Throwable) {
                failure = e
                logger.warn(
                    "Failed to fetch projects for PR filter; falling back to PR-derived list. " +
                        "If you expected the full project list, ensure your PAT has the " +
                        "'Project and Team (read)' (vso.project) scope.",
                    e
                )
            }
            ApplicationManager.getApplication().invokeLater({
                orgProjectsFromApi = result
                orgProjectsLoadState = if (failure != null) LoadState.FAILED else LoadState.LOADED
                // The chip label may have been a count placeholder from a persisted selection
                // before names were known — refresh it once we have data.
                syncProjectChipFromValue()
            }, ModalityState.any())
        }
    }

    /**
     * Update the cached list of authors from the loaded PR data.
     * Called by the list panel whenever new PR data is available.
     */
    fun updateAuthorsFromPullRequests(pullRequests: List<PullRequest>) {
        val authors = pullRequests
            .mapNotNull { pr ->
                pr.createdBy?.let { user ->
                    PullRequestSearchValue.AuthorFilter(
                        id = user.id,
                        displayName = user.displayName ?: "Unknown",
                        uniqueName = user.uniqueName,
                        imageUrl = user.imageUrl
                    )
                }
            }
            .distinctBy { it.id ?: it.displayName }
            .sortedBy { it.displayName.lowercase() }
        cachedAuthors = authors

        // Still derive a PR-data project list as a fallback. The authoritative source for
        // the filter popup is the API list (see fetchOrgProjectsAsync) — that one covers
        // projects with zero PRs — but if the API call failed (e.g. PAT lacks the project
        // scope) we want to keep the popup functional with whatever projects the loaded PRs
        // have ever revealed. We MERGE into the existing map rather than replacing so that
        // a narrower refresh (e.g. after the user filters down to 3 projects) doesn't
        // visibly shrink the list of projects available in the chip.
        pullRequests.forEach { pr ->
            val proj = pr.repository?.project
            val id = proj?.id
            val name = proj?.name
            if (id != null && name != null) {
                projectsFromPrData.putIfAbsent(id, AzureDevOpsApiClient.OrgProject(id, name, null))
            }
        }

        // If the org projects fetch previously failed, give it another shot now that PR
        // loading is clearly working — credentials may have been refreshed, or the failure
        // may have been transient.
        if (orgProjectsLoadState == LoadState.FAILED) {
            fetchOrgProjectsAsync()
        }

        cachedRepositories = pullRequests
            .mapNotNull { pr ->
                pr.repository?.let { repo ->
                    PullRequestSearchValue.RepositoryFilter(
                        id = repo.id,
                        name = repo.name ?: "Unknown",
                        projectName = repo.project?.name
                    )
                }
            }
            .distinctBy { it.id ?: it.name }
            .sortedBy { it.name.lowercase() }
    }

    // ---- Popup handlers ----

    private fun showQuickFilterPopup(button: JComponent) {
        val diffCount = diffFromDefaultCount()
        val activeQuickFilter = getActiveQuickFilter()

        val items = mutableListOf<QuickFilterMenuItem>(
            QuickFilterMenuItem.Filter(PullRequestQuickFilter.OPEN),
            QuickFilterMenuItem.Filter(PullRequestQuickFilter.YOUR_PULL_REQUESTS),
            QuickFilterMenuItem.Filter(PullRequestQuickFilter.ASSIGNED_TO_YOU),
            QuickFilterMenuItem.Filter(PullRequestQuickFilter.REVIEW_REQUESTS)
        )
        if (diffCount > 0) items += QuickFilterMenuItem.ClearFilters(diffCount)

        val step = object : BaseListPopupStep<QuickFilterMenuItem>("Quick Filters", items) {
            override fun getTextFor(value: QuickFilterMenuItem): String = when (value) {
                is QuickFilterMenuItem.Filter -> {
                    val checked = value.filter == activeQuickFilter
                    if (checked) "\u2713 ${value.filter.displayName}" else "  ${value.filter.displayName}"
                }
                is QuickFilterMenuItem.ClearFilters -> "Clear ${value.count} Filters"
            }

            override fun getSeparatorAbove(value: QuickFilterMenuItem): ListSeparator? =
                if (value is QuickFilterMenuItem.ClearFilters) ListSeparator() else null

            override fun onChosen(selectedValue: QuickFilterMenuItem, finalChoice: Boolean): PopupStep<*>? {
                when (selectedValue) {
                    is QuickFilterMenuItem.Filter -> when (selectedValue.filter) {
                        PullRequestQuickFilter.OPEN -> applyQuickFilter(
                            PullRequestSearchValue(state = PullRequestSearchValue.State.OPEN, showAllOrg = true)
                        )
                        PullRequestQuickFilter.YOUR_PULL_REQUESTS -> applyQuickFilter(
                            PullRequestSearchValue(
                                state = PullRequestSearchValue.State.OPEN,
                                author = PullRequestSearchValue.AuthorFilter(
                                    id = "@me", displayName = "Your pull requests",
                                    uniqueName = null, imageUrl = null
                                ),
                                showAllOrg = true
                            )
                        )
                        PullRequestQuickFilter.ASSIGNED_TO_YOU -> applyQuickFilter(
                            PullRequestSearchValue(
                                state = PullRequestSearchValue.State.OPEN,
                                review = PullRequestSearchValue.ReviewState.REVIEWED_BY_YOU,
                                showAllOrg = true
                            )
                        )
                        PullRequestQuickFilter.REVIEW_REQUESTS -> applyQuickFilter(
                            PullRequestSearchValue(
                                state = PullRequestSearchValue.State.OPEN,
                                review = PullRequestSearchValue.ReviewState.NO_REVIEW,
                                showAllOrg = true
                            )
                        )
                    }
                    is QuickFilterMenuItem.ClearFilters -> applyQuickFilter(PullRequestSearchValue.DEFAULT)
                }
                return PopupStep.FINAL_CHOICE
            }
        }

        val popup = JBPopupFactory.getInstance().createListPopup(step)
        val point = RelativePoint(button, Point(0, button.height + JBUIScale.scale(2)))
        popup.show(point)
    }

    private fun applyQuickFilter(value: PullRequestSearchValue) {
        currentValue = value
        syncChipsFromValue()
        onFilterChanged(currentValue)
        updateBadge()
    }

    private fun showStatePopup(chip: FilterChipComponent) {
        FilterPopupUtil.showSimplePopup(
            component = chip,
            items = PullRequestSearchValue.State.entries.toList(),
            presenter = { it.displayName },
            onSelected = { state ->
                chip.setValue(state.displayName)
                updateFilter(currentValue.copy(state = state))
            }
        )
    }

    private fun showAuthorPopup(chip: FilterChipComponent) {
        val authors = cachedAuthors
        if (authors.isNullOrEmpty()) {
            // No authors available yet — show a message
            FilterPopupUtil.showSimplePopup(
                component = chip,
                items = listOf("No authors available"),
                presenter = { it },
                onSelected = {}
            )
            return
        }

        val avatarService = AvatarService.getInstance(project)
        FilterPopupUtil.showUserPopup(
            component = chip,
            users = authors,
            avatarProvider = { user ->
                avatarService.getAvatar(user.imageUrl, 20) {
                    // Repaint when avatar loads
                    chip.repaint()
                }
            },
            onSelected = { author ->
                // Show avatar in the chip
                val icon = avatarService.getAvatar(author.imageUrl, 16) {
                    chip.repaint()
                }
                chip.setValue(author.displayName, icon)
                updateFilter(currentValue.copy(author = author))
            }
        )
    }

    private fun showReviewPopup(chip: FilterChipComponent) {
        FilterPopupUtil.showSimplePopup(
            component = chip,
            items = PullRequestSearchValue.ReviewState.entries.toList(),
            presenter = { it.displayName },
            onSelected = { review ->
                chip.setValue(review.displayName)
                updateFilter(currentValue.copy(review = review))
            }
        )
    }

    private fun showSortPopup(chip: FilterChipComponent) {
        FilterPopupUtil.showSimplePopup(
            component = chip,
            items = PullRequestSearchValue.Sort.entries.toList(),
            presenter = { it.displayName },
            onSelected = { sort ->
                chip.setValue(sort.displayName)
                updateFilter(currentValue.copy(sort = sort))
            }
        )
    }

    private fun showProjectPopup(chip: FilterChipComponent) {
        val available = effectiveProjects

        // If we have nothing yet, surface a state-appropriate message and (re)kick off a
        // fetch — that way a user who opens the popup before the eager init fetch had a
        // chance to complete, or whose first attempt failed, doesn't get stuck staring at
        // a static "Loading…" forever.
        if (available.isEmpty()) {
            when (orgProjectsLoadState) {
                LoadState.NOT_STARTED, LoadState.FAILED -> fetchOrgProjectsAsync()
                LoadState.LOADING, LoadState.LOADED -> Unit
            }
            val message = when (orgProjectsLoadState) {
                LoadState.LOADING -> "Loading projects… open this menu again in a moment"
                LoadState.FAILED -> "Failed to load projects (check idea.log) — retrying…"
                LoadState.NOT_STARTED -> "Loading projects…"
                LoadState.LOADED -> "No projects available"
            }
            FilterPopupUtil.showSimplePopup(
                component = chip,
                items = listOf(message),
                presenter = { it },
                onSelected = {}
            )
            return
        }

        // ProjectFilterPopup (used by the clone dialog) wants its own Project type — convert
        // each OrgProject to that shape so we can reuse the multi-select popup verbatim.
        val popupProjects = available.map {
            AzureDevOpsCloneApiClient.Project(id = it.id, name = it.name, description = it.description)
        }
        ProjectFilterPopup.show(
            anchor = chip,
            projects = popupProjects,
            initiallySelected = currentValue.selectedProjectIds
        ) { newSelection ->
            // Project selection invalidates any repo selection (the chosen repo may no
            // longer be in any selected project).
            val newRepo = currentValue.repositoryFilter?.takeIf { repo ->
                newSelection.isEmpty() || available
                    .filter { it.id in newSelection }
                    .any { it.name == repo.projectName }
            }
            if (newRepo == null) repositoryChip.clearValue()
            updateFilter(currentValue.copy(selectedProjectIds = newSelection, repositoryFilter = newRepo))
            syncProjectChipFromValue()
        }
    }

    private fun showRepositoryPopup(chip: FilterChipComponent) {
        // Constrain the repo list to the projects the user has explicitly selected (if any).
        val selectedProjectNames = if (currentValue.selectedProjectIds.isNotEmpty()) {
            effectiveProjects.filter { it.id in currentValue.selectedProjectIds }.map { it.name }.toSet()
        } else emptySet()
        val repos = if (selectedProjectNames.isEmpty()) cachedRepositories
        else cachedRepositories.filter { it.projectName in selectedProjectNames }

        if (repos.isEmpty()) {
            FilterPopupUtil.showSimplePopup(
                component = chip,
                items = listOf("No repositories available"),
                presenter = { it },
                onSelected = {}
            )
            return
        }
        FilterPopupUtil.showSearchablePopup(
            component = chip,
            items = repos,
            presenter = { repo ->
                if (selectedProjectNames.isEmpty() && repo.projectName != null) {
                    "${repo.name}  (${repo.projectName})"
                } else {
                    repo.name
                }
            },
            icon = AllIcons.Vcs.Branch,
            onSelected = { repo ->
                chip.setValue(repo.name)
                updateFilter(currentValue.copy(repositoryFilter = repo))
            }
        )
    }

    /** Sets the project chip's label to match the current [PullRequestSearchValue.selectedProjectIds]. */
    private fun syncProjectChipFromValue() {
        val ids = currentValue.selectedProjectIds
        when {
            ids.isEmpty() -> projectChip.clearValue()
            ids.size == 1 -> {
                val name = effectiveProjects.firstOrNull { it.id in ids }?.name ?: "1 project"
                projectChip.setValue(name)
            }
            else -> projectChip.setValue("${ids.size} projects")
        }
    }

    private fun updateFilter(newValue: PullRequestSearchValue) {
        currentValue = newValue
        onFilterChanged(currentValue)
        updateBadge()
    }

    /**
     * Synchronize chips visual state from the current filter value.
     * Used when a quick filter is applied.
     */
    private fun syncChipsFromValue() {
        val v = currentValue

        if (v.state != null) {
            stateChip.setValue(v.state.displayName)
        } else {
            stateChip.clearValue()
        }

        if (v.author != null) {
            val avatarService = AvatarService.getInstance(project)
            val icon = avatarService.getAvatar(v.author.imageUrl, 16) { authorChip.repaint() }
            authorChip.setValue(v.author.displayName, icon)
        } else {
            authorChip.clearValue()
        }

        if (v.review != null) {
            reviewChip.setValue(v.review.displayName)
        } else {
            reviewChip.clearValue()
        }

        if (v.sort != null) {
            sortChip.setValue(v.sort.displayName)
        } else {
            sortChip.clearValue()
        }

        syncProjectChipFromValue()

        if (v.repositoryFilter != null) {
            repositoryChip.setValue(v.repositoryFilter.name)
        } else {
            repositoryChip.clearValue()
        }

        if (v.searchQuery != null) {
            searchField.text = v.searchQuery
        } else {
            searchField.text = ""
        }
    }

    /** Count how many active filters differ from the DEFAULT state. */
    private fun diffFromDefaultCount(): Int {
        val def = PullRequestSearchValue.DEFAULT
        var count = 0
        if (currentValue.state != def.state) count++
        if (currentValue.author != null) count++
        if (currentValue.review != null) count++
        if (currentValue.sort != null) count++
        if (currentValue.selectedProjectIds.isNotEmpty()) count++
        if (currentValue.repositoryFilter != null) count++
        if (currentValue.searchQuery != null) count++
        return count
    }

    /** Returns which quick filter preset matches the current filter value, or null. */
    private fun getActiveQuickFilter(): PullRequestQuickFilter? {
        val v = currentValue
        return when {
            v.state == PullRequestSearchValue.State.OPEN &&
                v.author?.id == "@me" && v.review == null ->
                PullRequestQuickFilter.YOUR_PULL_REQUESTS
            v.state == PullRequestSearchValue.State.OPEN &&
                v.review == PullRequestSearchValue.ReviewState.REVIEWED_BY_YOU && v.author == null ->
                PullRequestQuickFilter.ASSIGNED_TO_YOU
            v.state == PullRequestSearchValue.State.OPEN &&
                v.review == PullRequestSearchValue.ReviewState.NO_REVIEW && v.author == null ->
                PullRequestQuickFilter.REVIEW_REQUESTS
            v.state == PullRequestSearchValue.State.OPEN &&
                v.author == null && v.review == null ->
                PullRequestQuickFilter.OPEN
            else -> null
        }
    }

    private fun updateBadge() {
        filterBadgeIcon.showBadge = diffFromDefaultCount() > 0
        quickFilterButton.repaint()
    }
}

private sealed class QuickFilterMenuItem {
    data class Filter(val filter: PullRequestQuickFilter) : QuickFilterMenuItem()
    data class ClearFilters(val count: Int) : QuickFilterMenuItem()
}
