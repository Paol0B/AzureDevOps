package paol0b.azuredevops.toolwindow.filters

/**
 * Immutable value representing the current state of all PR list filters.
 * Modeled after GHPRListSearchValue from the JetBrains GitHub plugin.
 */
data class PullRequestSearchValue(
    val searchQuery: String? = null,
    val state: State? = null,
    val author: AuthorFilter? = null,
    val review: ReviewState? = null,
    val sort: Sort? = null,
    val showAllOrg: Boolean = false
) {
    val filterCount: Int
        get() {
            var count = 0
            if (searchQuery != null) count++
            if (state != null) count++
            if (author != null) count++
            if (review != null) count++
            if (sort != null) count++
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

    companion object {
        val DEFAULT = PullRequestSearchValue(state = State.OPEN, showAllOrg = true)
        val EMPTY = PullRequestSearchValue()
    }
}

/**
 * Quick filter presets, modeled after GHPRListQuickFilter.
 */
enum class PullRequestQuickFilter(val displayName: String) {
    OPEN("Open"),
    YOUR_PULL_REQUESTS("Your pull requests"),
    ASSIGNED_TO_YOU("Assigned to you"),
    REVIEW_REQUESTS("Review requests");

    override fun toString(): String = displayName
}
