package paol0b.azuredevops.toolwindow.review.timeline

import paol0b.azuredevops.model.*

/**
 * Types of entries in the PR timeline.
 */
enum class TimelineEntryType {
    PR_CREATED,
    COMMENT_THREAD,
    VOTE_EVENT,
    SYSTEM_EVENT
}

/**
 * A single entry in the timeline. Each entry corresponds to either a PR-creation event,
 * a comment-thread (with nested replies), a vote, or a system event.
 */
data class TimelineEntry(
    val type: TimelineEntryType,
    val author: String,
    val authorImageUrl: String?,
    val timestamp: String?,
    val content: String,
    val filePath: String? = null,
    val threadId: Int? = null,
    val threadStatus: ThreadStatus? = null,
    /** Non-null only for COMMENT_THREAD entries: the nested replies (2nd+ comments). */
    val replies: List<TimelineReply> = emptyList(),
    /** Number of non-system comments in this thread (including the root). */
    val commentCount: Int = 0,
    /** The reviewer vote value if this is a VOTE_EVENT (10, 5, 0, -5, -10). */
    val voteValue: Int? = null
)

/**
 * A reply inside a comment thread (2nd, 3rd, … comment in the thread).
 */
data class TimelineReply(
    val commentId: Int?,
    val author: String,
    val authorImageUrl: String?,
    val timestamp: String?,
    val content: String
)

/**
 * Aggregated vote status for the Reviewer-Badges header.
 */
data class ReviewerVoteSummary(
    val reviewer: Reviewer,
    val vote: ReviewerVote
)

// ──────────────────────────────────────────────────────────────
//  Converter: raw API models → Timeline view models
// ──────────────────────────────────────────────────────────────

object TimelineConverter {

    /**
     * Build a list of [TimelineEntry] from the PR itself and its comment threads.
     * Results are sorted chronologically (oldest first).
     */
    fun buildEntries(pr: PullRequest, threads: List<CommentThread>): List<TimelineEntry> {
        val entries = mutableListOf<TimelineEntry>()

        // 1) PR creation event
        entries += TimelineEntry(
            type = TimelineEntryType.PR_CREATED,
            author = pr.createdBy?.displayName ?: "Unknown",
            authorImageUrl = pr.createdBy?.imageUrl,
            timestamp = pr.creationDate,
            content = "created this pull request"
        )

        // 2) Walk every thread
        for (thread in threads) {
            if (thread.isDeleted == true) continue
            val comments = thread.comments?.filter { it.isDeleted != true } ?: continue
            if (comments.isEmpty()) continue

            val root = comments.first()
            val isSystem = root.commentType.equals("system", ignoreCase = true)

            if (isSystem) {
                // System / vote events kept as flat entries
                val isVote = root.content?.contains("voted", ignoreCase = true) == true
                val voteValue = if (isVote) TimelineUtils.extractVoteValueFromContent(root.content ?: "") else null
                entries += TimelineEntry(
                    type = if (isVote) TimelineEntryType.VOTE_EVENT else TimelineEntryType.SYSTEM_EVENT,
                    author = root.author?.displayName ?: "System",
                    authorImageUrl = root.author?.imageUrl,
                    timestamp = root.publishedDate ?: root.lastUpdatedDate,
                    content = root.content ?: "",
                    voteValue = voteValue
                )
            } else {
                // Human comment thread → card with nested replies
                val nonSystemComments = comments.filter {
                    it.commentType?.equals("system", ignoreCase = true) != true
                }
                val rootComment = nonSystemComments.firstOrNull() ?: continue
                val replies = nonSystemComments.drop(1).map { c ->
                    TimelineReply(
                        commentId = c.id,
                        author = c.author?.displayName ?: "Unknown",
                        authorImageUrl = c.author?.imageUrl,
                        timestamp = c.publishedDate ?: c.lastUpdatedDate,
                        content = c.content ?: ""
                    )
                }

                entries += TimelineEntry(
                    type = TimelineEntryType.COMMENT_THREAD,
                    author = rootComment.author?.displayName ?: "Unknown",
                    authorImageUrl = rootComment.author?.imageUrl,
                    timestamp = rootComment.publishedDate ?: rootComment.lastUpdatedDate,
                    content = rootComment.content ?: "",
                    filePath = thread.getFilePath(),
                    threadId = thread.id,
                    threadStatus = thread.status,
                    replies = replies,
                    commentCount = nonSystemComments.size
                )
            }
        }

        // Sort chronologically (oldest first → newest at bottom)
        entries.sortBy { it.timestamp ?: "" }
        return entries
    }

    /**
     * Build reviewer vote summaries from the PR reviewers list.
     */
    fun buildVoteSummaries(reviewers: List<Reviewer>?): List<ReviewerVoteSummary> {
        return (reviewers ?: emptyList()).map { r ->
            ReviewerVoteSummary(reviewer = r, vote = r.getVoteStatus())
        }
    }

    /**
     * Calculate a stable hash for change detection.
     * Only triggers re-render when something actually changed.
     */
    fun calculateHash(threads: List<CommentThread>, reviewers: List<Reviewer>?): Int {
        var hash = threads.size
        for (thread in threads) {
            hash = 31 * hash + (thread.id ?: 0)
            hash = 31 * hash + (thread.status?.hashCode() ?: 0)
            hash = 31 * hash + (thread.comments?.size ?: 0)
            hash = 31 * hash + (thread.comments?.firstOrNull()?.content?.hashCode() ?: 0)
            hash = 31 * hash + (thread.comments?.lastOrNull()?.content?.hashCode() ?: 0)
            hash = 31 * hash + (thread.comments?.lastOrNull()?.publishedDate?.hashCode() ?: 0)
        }
        // Include reviewer votes so badge panel updates
        for (r in reviewers ?: emptyList()) {
            hash = 31 * hash + (r.id?.hashCode() ?: 0)
            hash = 31 * hash + (r.vote ?: 0)
        }
        return hash
    }
}
