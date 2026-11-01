package paol0b.azuredevops.services

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.toolwindow.filters.PullRequestSearchValue
import java.util.concurrent.CancellationException

class RelevantPullRequestSearchTest {
    private fun pr(id: Int, repo: String = "repo"): PullRequest = Gson().fromJson(
        """{"pullRequestId":$id,"title":"Example","repository":{"id":"$repo","project":{"id":"project"}}}""",
        PullRequest::class.java
    )

    @Test fun `default is relevant to the current user across organization`() {
        assertEquals(PullRequestSearchValue.ReviewState.RELEVANT_TO_YOU, PullRequestSearchValue.DEFAULT.review)
        assertTrue(PullRequestSearchValue.DEFAULT.showAllOrg)
    }

    @Test fun `creator and reviewer are separate server queries preserving repo and status`() {
        val alternatives = PullRequestSearchCriteria("active", repositoryId = "repo", relevantUserId = "me").alternatives()
        assertEquals(2, alternatives.size)
        assertEquals("me", alternatives[0].creatorId)
        assertNull(alternatives[0].reviewerId)
        assertNull(alternatives[1].creatorId)
        assertEquals("me", alternatives[1].reviewerId)
        alternatives.forEach {
            assertEquals("repo", it.repositoryId)
            assertEquals("active", it.status)
            assertNull(it.relevantUserId)
        }
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing identity cannot fall back to unrestricted list query`() {
        PullRequestSearchCriteria("active", relevantUserId = "").alternatives()
    }

    @Test fun `overlapping results are deduplicated without losing reviewer-only matches to cap`() {
        val result = PullRequestSearchPager.searchScopes(
            "", PullRequestSearchCriteria("active", relevantUserId = "me"), listOf("org"), 50, 3,
            fetchPage = { _, criteria, _, skip ->
                if (skip > 0) emptyList()
                else if (criteria.creatorId != null) listOf(pr(1), pr(2))
                else listOf(pr(1), pr(3))
            }
        )
        assertEquals(listOf(1, 2, 3), result.pullRequests.map { it.pullRequestId })
        assertEquals(4, result.progress.scanned)
    }

    @Test fun `same PR number in distinct repositories is retained`() {
        val result = PullRequestSearchPager.searchScopes(
            "", PullRequestSearchCriteria("active", relevantUserId = "me"), listOf("org"), 50, 10,
            fetchPage = { _, criteria, _, skip ->
                if (skip > 0) emptyList()
                else listOf(pr(1, if (criteria.creatorId != null) "one" else "two"))
            }
        )
        assertEquals(2, result.pullRequests.size)
        assertTrue(result.progress.complete)
    }

    @Test fun `project endpoints and query alternatives share scan budget`() {
        val calls = mutableListOf<String>()
        val result = PullRequestSearchPager.searchScopes(
            "", PullRequestSearchCriteria("active", relevantUserId = "me"), listOf("p1", "p2"), 50, 10,
            fetchPage = { endpoint, _, _, _ -> calls.add(endpoint); listOf(pr(1)) },
            budget = PullRequestSearchBudget(maxScanned = 50, maxPages = 1)
        )
        assertEquals(listOf("p1"), calls)
        assertFalse(result.progress.complete)
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is honored between query alternatives`() {
        var cancelled = false
        PullRequestSearchPager.searchScopes(
            "", PullRequestSearchCriteria("active", relevantUserId = "me"), listOf("org"), 50, 10,
            fetchPage = { _, _, _, _ -> emptyList<PullRequest>().also { cancelled = true } },
            isCancelled = { cancelled }
        )
    }
}
