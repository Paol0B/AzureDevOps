package paol0b.azuredevops.toolwindow.filters

/**
 * Immutable value representing the current state of all PR list filters.
 * Modeled after GHPRListSearchValue from the JetBrains GitHub plugin.
 */
data class PullRequestSearchValue(
    val searchQuery: String? = null,
    val state: State? = null,
    /**
     * Server-side "who is this PR relevant to?" scope.  Azure DevOps exposes creatorId and
     * reviewerId search criteria, so these presets must not be emulated by downloading every
     * PR in the organization and filtering locally.
     */
    val involvement: Involvement? = null,
    val author: AuthorFilter? = null,
    val review: ReviewState? = null,
    val sort: Sort? = null,
    /** Multi-select set of project ids the user has chosen. Empty = no project filter (the
     *  list panel falls back to its org-wide or repo-scoped fetch behavior). */
    val selectedProjectIds: Set<String> = emptySet(),
    val repositoryFilter: RepositoryFilter? = null,
    val showAllOrg: Boolean = false
) {
    val filterCount: Int
        get() {
            var count = 0
            if (searchQuery != null) count++
            if (state != null) count++
            if (involvement != null) count++
            if (author != null) count++
            if (review != null) count++
            if (sort != null) count++
            if (selectedProjectIds.isNotEmpty()) count++
            if (repositoryFilter != null) count++
            return count
        }

    /** Maps to Azure DevOps PR status API values. */
    enum class State(val apiValue: String, val displayName: String) {
        OPEN("active", "Open"),
        COMPLETED("completed", "Completed"),
        ABANDONED("abandoned", "Abandoned"),
        ALL("all", "All");

        override fun toString(): String = displayName
    }

    /** User-centric scopes used by the quick-filter presets. */
    enum class Involvement(val displayName: String) {
        RELEVANT_TO_ME("Relevant to me"),
        CREATED_BY_ME("Created by me"),
        ASSIGNED_TO_ME("Assigned to me"),
        AWAITING_MY_REVIEW("Needs my review");

        override fun toString(): String = displayName
    }

    /** Author information for the author filter. */
    data class AuthorFilter(
        val id: String?,
        val displayName: String,
        val uniqueName: String?,
        val imageUrl: String?
    ) {
        override fun toString(): String = displayName
    }

    /** Review state filter values, adapted for Azure DevOps PR reviewer votes. */
    enum class ReviewState(val displayName: String) {
        NO_REVIEW("No reviews"),
        APPROVED("Approved review"),
        CHANGES_REQUESTED("Changes requested"),
        REVIEWED_BY_YOU("Reviewed by you");

        override fun toString(): String = displayName
    }

    /** Sort order for PRs. */
    enum class Sort(val displayName: String) {
        NEWEST("Newest"),
        OLDEST("Oldest"),
        RECENTLY_UPDATED("Recently updated");

        override fun toString(): String = displayName
    }

    /** Project filter: allows filtering PRs by Azure DevOps project. */
    data class ProjectFilter(
        val id: String?,
        val name: String
    ) {
        override fun toString(): String = name
    }

    /** Repository filter: allows filtering PRs by repository within a project. */
    data class RepositoryFilter(
        val id: String?,
        val name: String,
        val projectName: String?
    ) {
        override fun toString(): String = name
    }

    companion object {
        // A useful and inexpensive default: two narrowly scoped API calls (creator + reviewer)
        // instead of an unbounded organization-wide PR download.
        val DEFAULT = PullRequestSearchValue(
            state = State.OPEN,
            involvement = Involvement.RELEVANT_TO_ME,
            showAllOrg = true
        )
        val EMPTY = PullRequestSearchValue()
    }
}

/**
 * Quick filter presets, modeled after GHPRListQuickFilter.
 */
enum class PullRequestQuickFilter(val displayName: String) {
    RELEVANT_TO_ME("Relevant to me"),
    OPEN("Open (all organization)"),
    YOUR_PULL_REQUESTS("Your pull requests"),
    ASSIGNED_TO_YOU("Assigned to you"),
    REVIEW_REQUESTS("Review requests");

    override fun toString(): String = displayName
}
