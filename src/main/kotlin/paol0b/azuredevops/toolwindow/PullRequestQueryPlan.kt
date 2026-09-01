package paol0b.azuredevops.toolwindow

import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.PullRequestQueryCriteria
import paol0b.azuredevops.toolwindow.filters.PullRequestSearchValue

/** Convert a UI preset into one or more server-side Azure DevOps queries. */
internal fun PullRequestSearchValue.serverQueryCriteria(
    currentUserId: String?
): List<PullRequestQueryCriteria> {
    val userId = currentUserId?.takeIf { it.isNotBlank() }

    return when (involvement) {
        PullRequestSearchValue.Involvement.RELEVANT_TO_ME -> {
            requireNotNull(userId) { "Current Azure DevOps user is required" }
            listOf(
                PullRequestQueryCriteria(creatorId = userId),
                PullRequestQueryCriteria(reviewerId = userId)
            )
        }
        PullRequestSearchValue.Involvement.CREATED_BY_ME -> {
            requireNotNull(userId) { "Current Azure DevOps user is required" }
            listOf(PullRequestQueryCriteria(creatorId = userId))
        }
        PullRequestSearchValue.Involvement.ASSIGNED_TO_ME,
        PullRequestSearchValue.Involvement.AWAITING_MY_REVIEW -> {
            requireNotNull(userId) { "Current Azure DevOps user is required" }
            listOf(PullRequestQueryCriteria(reviewerId = userId))
        }
        null -> when {
            // Preserve efficient behavior for any persisted 4.1 quick-filter state.
            author?.id == "@me" && userId != null ->
                listOf(PullRequestQueryCriteria(creatorId = userId))
            review == PullRequestSearchValue.ReviewState.REVIEWED_BY_YOU && userId != null ->
                listOf(PullRequestQueryCriteria(reviewerId = userId))
            else -> listOf(PullRequestQueryCriteria.NONE)
        }
    }
}

/**
 * Azure DevOps may satisfy reviewerId through a reviewer group, in which case the current
 * user's identity is not necessarily present in the returned reviewer array. Treat that as
 * pending; when a direct identity is present, only a missing/zero vote is pending.
 */
internal fun PullRequest.isAwaitingReviewFrom(currentUserId: String?): Boolean {
    val directReviewer = reviewers.orEmpty().firstOrNull { it.id == currentUserId }
    return directReviewer == null || directReviewer.vote == null || directReviewer.vote == 0
}
