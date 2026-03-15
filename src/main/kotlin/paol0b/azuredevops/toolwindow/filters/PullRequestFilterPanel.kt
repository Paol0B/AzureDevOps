package paol0b.azuredevops.toolwindow.filters

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
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
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AvatarService
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

    // Cached lists (populated from loaded PR data)
    private var cachedAuthors: List<PullRequestSearchValue.AuthorFilter>? = null
    private var cachedProjects: List<PullRequestSearchValue.ProjectFilter> = emptyList()
    private var cachedRepositories: List<PullRequestSearchValue.RepositoryFilter> = emptyList()

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

        // Project filter
        projectChip = FilterChipComponent("Project",
            onShowPopup = { chip -> showProjectPopup(chip) },
            onClear = { updateFilter(currentValue.copy(projectFilter = null, repositoryFilter = null)); repositoryChip.clearValue() }
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
    }

    fun getComponent(): JPanel = panel

    fun getCurrentFilter(): PullRequestSearchValue = currentValue

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

        // Extract distinct projects from PR data
        cachedProjects = pullRequests
            .mapNotNull { pr ->
                pr.repository?.project?.let { proj ->
                    PullRequestSearchValue.ProjectFilter(
                        id = proj.id,
                        name = proj.name ?: "Unknown"
                    )
                }
            }
            .distinctBy { it.id ?: it.name }
            .sortedBy { it.name.lowercase() }

        // Extract distinct repositories from PR data
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
        if (cachedProjects.isEmpty()) {
            FilterPopupUtil.showSimplePopup(
                component = chip,
                items = listOf("No projects available"),
                presenter = { it },
                onSelected = {}
            )
            return
        }
        FilterPopupUtil.showSearchablePopup(
            component = chip,
            items = cachedProjects,
            presenter = { it.name },
            icon = AllIcons.Nodes.Project,
            onSelected = { proj ->
                chip.setValue(proj.name)
                // When project changes, clear repository filter since repos may differ
                repositoryChip.clearValue()
                updateFilter(currentValue.copy(projectFilter = proj, repositoryFilter = null))
            }
        )
    }

    private fun showRepositoryPopup(chip: FilterChipComponent) {
        // If a project filter is active, only show repos from that project
        val repos = currentValue.projectFilter?.let { proj ->
            cachedRepositories.filter { it.projectName == proj.name }
        } ?: cachedRepositories

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
                if (currentValue.projectFilter == null && repo.projectName != null) {
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

        if (v.projectFilter != null) {
            projectChip.setValue(v.projectFilter.name)
        } else {
            projectChip.clearValue()
        }

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
        if (currentValue.projectFilter != null) count++
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
