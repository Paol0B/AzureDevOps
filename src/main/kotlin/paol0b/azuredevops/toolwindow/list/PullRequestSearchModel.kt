package paol0b.azuredevops.toolwindow.list

import paol0b.azuredevops.model.PullRequest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Filter state for the PR list - inspired by GitHub's GHPRListSearchValue.
 */
data class PullRequestSearchState(
    val textSearch: String = "",
    val statusFilter: StatusFilter = StatusFilter.ACTIVE,
    val authorFilter: String? = null,
    val reviewerFilter: String? = null,
    val sortOrder: SortOrder = SortOrder.NEWEST,
    val orgMode: Boolean = true
) {
    fun isEmpty(): Boolean =
        textSearch.isBlank() && statusFilter == StatusFilter.ACTIVE &&
                authorFilter == null && reviewerFilter == null &&
                sortOrder == SortOrder.NEWEST
}

enum class StatusFilter(val apiValue: String, val displayName: String) {
    ACTIVE("active", "Active"),
    COMPLETED("completed", "Completed"),
    ABANDONED("abandoned", "Abandoned"),
    ALL("all", "All");

    companion object {
        fun fromApiValue(value: String): StatusFilter =
            entries.firstOrNull { it.apiValue.equals(value, ignoreCase = true) } ?: ACTIVE
    }
}

enum class SortOrder(val displayName: String) {
    NEWEST("Newest first"),
    OLDEST("Oldest first");
}

enum class QuickFilter(val displayName: String) {
    OPEN("Active"),
    MY_PRS("My Pull Requests"),
    ASSIGNED_TO_ME("Assigned to Me"),
    NEEDS_MY_REVIEW("Needs My Review");
}

/**
 * Listener interface for search state changes.
 */
interface PullRequestSearchListener {
    /** Called when a filter changes that requires a new API call (e.g., status filter) */
    fun onApiFilterChanged(state: PullRequestSearchState)
    /** Called when a filter changes that only requires client-side re-filtering */
    fun onClientFilterChanged(state: PullRequestSearchState)
}

/**
 * Observable search state holder.
 */
class PullRequestSearchStateHolder {
    private val listeners = mutableListOf<PullRequestSearchListener>()
    var state: PullRequestSearchState = PullRequestSearchState()
        private set

    fun addListener(listener: PullRequestSearchListener) {
        listeners.add(listener)
    }

    fun updateTextSearch(text: String) {
        val old = state
        state = state.copy(textSearch = text)
        if (old != state) notifyClientChange()
    }

    fun updateStatusFilter(filter: StatusFilter) {
        val old = state
        state = state.copy(statusFilter = filter)
        if (old.statusFilter != filter) notifyApiChange()
    }

    fun updateAuthorFilter(author: String?) {
        val old = state
        state = state.copy(authorFilter = author)
        if (old != state) notifyClientChange()
    }

    fun updateReviewerFilter(reviewer: String?) {
        val old = state
        state = state.copy(reviewerFilter = reviewer)
        if (old != state) notifyClientChange()
    }

    fun updateSortOrder(sort: SortOrder) {
        val old = state
        state = state.copy(sortOrder = sort)
        if (old != state) notifyClientChange()
    }

    fun updateOrgMode(enabled: Boolean) {
        val old = state
        state = state.copy(orgMode = enabled)
        if (old.orgMode != enabled) notifyApiChange()
    }

    fun applyQuickFilter(quickFilter: QuickFilter) {
        state = when (quickFilter) {
            QuickFilter.OPEN -> PullRequestSearchState(statusFilter = StatusFilter.ACTIVE, orgMode = state.orgMode)
            QuickFilter.MY_PRS -> PullRequestSearchState(statusFilter = StatusFilter.ACTIVE, orgMode = state.orgMode)
            QuickFilter.ASSIGNED_TO_ME -> PullRequestSearchState(statusFilter = StatusFilter.ACTIVE, orgMode = state.orgMode)
            QuickFilter.NEEDS_MY_REVIEW -> PullRequestSearchState(statusFilter = StatusFilter.ACTIVE, orgMode = state.orgMode)
        }
        notifyApiChange()
    }

    fun resetFilters() {
        state = PullRequestSearchState(orgMode = state.orgMode)
        notifyApiChange()
    }

    private fun notifyApiChange() = listeners.forEach { it.onApiFilterChanged(state) }
    private fun notifyClientChange() = listeners.forEach { it.onClientFilterChanged(state) }
}

/**
 * Utility: apply client-side filters and sorting.
 */
object PullRequestFilterUtil {

    fun applyFilters(
        pullRequests: List<PullRequest>,
        state: PullRequestSearchState,
        currentUserId: String? = null
    ): List<PullRequest> {
        var result = pullRequests

        // Text search: match on title, PR ID, branch names, author name, repo name
        if (state.textSearch.isNotBlank()) {
            val query = state.textSearch.lowercase()
            result = result.filter { pr ->
                pr.title.lowercase().contains(query) ||
                        "#${pr.pullRequestId}".contains(query) ||
                        pr.getSourceBranchName().lowercase().contains(query) ||
                        pr.getTargetBranchName().lowercase().contains(query) ||
                        (pr.createdBy?.displayName?.lowercase()?.contains(query) == true) ||
                        (pr.repository?.name?.lowercase()?.contains(query) == true)
            }
        }

        // Author filter
        if (state.authorFilter != null) {
            result = result.filter { pr ->
                pr.createdBy?.displayName?.equals(state.authorFilter, ignoreCase = true) == true ||
                        pr.createdBy?.uniqueName?.equals(state.authorFilter, ignoreCase = true) == true
            }
        }

        // Reviewer filter
        if (state.reviewerFilter != null) {
            result = result.filter { pr ->
                pr.reviewers?.any {
                    it.displayName?.equals(state.reviewerFilter, ignoreCase = true) == true ||
                            it.uniqueName?.equals(state.reviewerFilter, ignoreCase = true) == true
                } == true
            }
        }

        // Sort
        result = when (state.sortOrder) {
            SortOrder.NEWEST -> result.sortedByDescending { it.pullRequestId }
            SortOrder.OLDEST -> result.sortedBy { it.pullRequestId }
        }

        return result
    }

    /**
     * Extract unique authors from a list of PRs.
     */
    fun extractAuthors(pullRequests: List<PullRequest>): List<String> {
        return pullRequests
            .mapNotNull { it.createdBy?.displayName }
            .distinct()
            .sorted()
    }

    /**
     * Extract unique reviewers from a list of PRs.
     */
    fun extractReviewers(pullRequests: List<PullRequest>): List<String> {
        return pullRequests
            .flatMap { it.reviewers ?: emptyList() }
            .mapNotNull { it.displayName }
            .distinct()
            .sorted()
    }

    /**
     * Format a date string to relative time (e.g., "2 hours ago", "3 days ago").
     */
    fun formatRelativeTime(dateString: String?): String {
        if (dateString.isNullOrBlank()) return ""
        return try {
            val instant = Instant.parse(dateString)
            val now = Instant.now()
            val seconds = now.epochSecond - instant.epochSecond

            when {
                seconds < 60 -> "just now"
                seconds < 3600 -> "${seconds / 60}m ago"
                seconds < 86400 -> "${seconds / 3600}h ago"
                seconds < 604800 -> "${seconds / 86400}d ago"
                seconds < 2592000 -> "${seconds / 604800}w ago"
                else -> {
                    val date = instant.atZone(ZoneId.systemDefault()).toLocalDate()
                    date.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
                }
            }
        } catch (e: Exception) {
            // Fallback: try to parse Azure DevOps date format
            try {
                val parts = dateString.split("T")
                if (parts.size >= 2) parts[0] else dateString
            } catch (_: Exception) {
                dateString
            }
        }
    }
}
