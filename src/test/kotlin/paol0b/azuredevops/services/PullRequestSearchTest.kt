package paol0b.azuredevops.services

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import paol0b.azuredevops.model.PullRequest
import java.util.concurrent.CancellationException

class PullRequestSearchTest {
    private fun pr(id: Int, title: String): PullRequest = Gson().fromJson(
        """{"pullRequestId":$id,"title":"$title","sourceRefName":"refs/heads/feature","targetRefName":"refs/heads/main","status":"active"}""",
        PullRequest::class.java
    )

    @Test fun `IDs and URLs use exact lookup`() {
        assertEquals(12345, PullRequestLookup.parse("12345")!!.id)
        assertEquals(12345, PullRequestLookup.parse(" #12345 ")!!.id)
        val lookup = PullRequestLookup.parse("https://dev.azure.com/org/My%20Project/_git/My%2BRepo/pullrequest/12345?_a=overview")!!
        assertEquals(12345, lookup.id)
        assertEquals("My Project", lookup.project)
        assertEquals("My+Repo", lookup.repository)
        lookup.requireSameServer("https://dev.azure.com/org")
        assertNull(PullRequestLookup.parse("release 2026"))
    }

    @Test fun `cloud URL aliases resolve to the same organization`() {
        PullRequestLookup.parse("https://dev.azure.com/org/p/_git/r/pullrequest/1")!!
            .requireSameServer("https://org.visualstudio.com")
        PullRequestLookup.parse("https://org.visualstudio.com/p/_git/r/pullrequest/1")!!
            .requireSameServer("https://dev.azure.com/org")
        PullRequestLookup.parse("https://dev.azure.com:443/org/p/_git/r/pullrequest/1")!!
            .requireSameServer("https://dev.azure.com/org")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `cloud alias from another organization is rejected`() {
        PullRequestLookup.parse("https://other.visualstudio.com/p/_git/r/pullrequest/1")!!
            .requireSameServer("https://dev.azure.com/org")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `URL from another organization is rejected`() {
        PullRequestLookup.parse("https://dev.azure.com/other/p/_git/r/pullrequest/1")!!
            .requireSameServer("https://dev.azure.com/org")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero ID is not a title search`() { PullRequestLookup.parse("#0") }

    @Test fun `server filters are encoded in the request`() {
        val url = PullRequestSearchCriteria("all", "author-id", "reviewer-id", "repo-id")
            .listUrl("https://dev.azure.com/org/_apis/git/pullrequests", 50, 100)
        assertEquals("https://dev.azure.com/org/_apis/git/pullrequests?searchCriteria.status=all&searchCriteria.creatorId=author-id&searchCriteria.reviewerId=reviewer-id&searchCriteria.repositoryId=repo-id&%24top=50&%24skip=100&api-version=7.0", url)
    }

    @Test fun `text search scans beyond the normal loaded list and short pages`() {
        val skips = mutableListOf<Int>()
        val pages = mapOf(0 to listOf(pr(3, "unrelated")), 1 to listOf(pr(2, "another")), 2 to listOf(pr(1, "Fix Needle")))
        val result = PullRequestSearchPager.search("needle", 50, 10, { _, skip ->
            skips.add(skip)
            pages[skip].orEmpty()
        })
        assertEquals(listOf(1), result.pullRequests.map { it.pullRequestId })
        assertEquals(listOf(0, 1, 2, 3), skips)
        assertEquals(3, result.progress.scanned)
        assertTrue(result.progress.complete)
    }

    @Test fun `unsupported filters are applied before counting the result limit`() {
        val pages = mapOf(0 to listOf(pr(3, "Fix unrelated")), 1 to listOf(pr(1, "Fix wanted")))
        val result = PullRequestSearchPager.search("fix", 50, 1, { _, skip -> pages[skip].orEmpty() },
            accept = { it.pullRequestId == 1 })
        assertEquals(listOf(1), result.pullRequests.map { it.pullRequestId })
        assertEquals(2, result.progress.scanned)
    }

    @Test fun `server URLs support encoded names and on premises collections`() {
        val lookup = PullRequestLookup.parse("https://tfs.example.com:8080/tfs/Collection/Project/_git/Repo/pullrequest/22")!!
        lookup.requireSameServer("https://tfs.example.com:8080/tfs/Collection")
        assertEquals("Project", lookup.project)
        assertEquals("Repo", lookup.repository)
        val url = PullRequestSearchCriteria("active", repositoryId = "A & B").listUrl("https://tfs.example.com/prs", 50, 0)
        assertTrue(url.contains("searchCriteria.repositoryId=A+%26+B"))
    }

    @Test fun `result limit reports incomplete search`() {
        val result = PullRequestSearchPager.search("fix", 50, 1, { _, _ -> listOf(pr(2, "Fix first"), pr(1, "Fix second")) })
        assertEquals(listOf(2), result.pullRequests.map { it.pullRequestId })
        assertFalse(result.progress.complete)
    }

    @Test(expected = CancellationException::class)
    fun `obsolete searches stop before fetching another page`() {
        PullRequestSearchPager.search("fix", 50, 10, { _, _ -> fail("Must not fetch"); emptyList() }, isCancelled = { true })
    }

    @Test(expected = IllegalStateException::class)
    fun `server ignoring pagination does not claim complete results`() {
        PullRequestSearchPager.search("missing", 50, 10, { _, _ -> listOf(pr(1, "other")) })
    }

    @Test(expected = IllegalStateException::class)
    fun `download errors do not become empty results`() {
        PullRequestSearchPager.search("fix", 50, 10, { _, _ -> throw IllegalStateException("Server unavailable") })
    }

    @Test fun `rare text query stops after default examined PR budget`() {
        var fetched = 0
        val result = PullRequestSearchPager.search("needle", 200, 10, { size, skip ->
            fetched += size
            if (skip >= 6000) emptyList() else (skip until skip + size).map { pr(it + 1, "other") }
        })
        assertEquals(5000, fetched)
        assertEquals(5000, result.progress.scanned)
        assertFalse(result.progress.complete)
    }

    @Test fun `short pages stop after default request budget`() {
        var requests = 0
        val result = PullRequestSearchPager.search("needle", 200, 10, { _, skip ->
            requests++
            if (skip >= 101) emptyList() else listOf(pr(skip + 1, "other"))
        })
        assertEquals(100, requests)
        assertEquals(100, result.progress.scanned)
        assertFalse(result.progress.complete)
    }

    @Test fun `cancelling between pages retains published partial matches`() {
        var cancelled = false
        var requests = 0
        var published = emptyList<PullRequest>()
        try {
            PullRequestSearchPager.search("needle", 50, 10,
                fetchPage = { _, _ -> requests++; listOf(pr(1, "needle first")) },
                isCancelled = { cancelled },
                onPage = { matches, _ -> published = matches; cancelled = true })
            fail("Expected cancellation")
        } catch (_: CancellationException) {
            assertEquals(1, requests)
            assertEquals(listOf(1), published.map { it.pullRequestId })
        }
    }

    @Test fun `examined budget is shared across project scopes and bounds last page`() {
        val budget = PullRequestSearchBudget(maxScanned = 3, maxPages = 100)
        val requests = mutableListOf<Int>()
        val first = PullRequestSearchPager.search("needle", 2, 10,
            fetchPage = { size, skip ->
                requests.add(size)
                if (skip == 0) listOf(pr(1, "needle first"), pr(2, "other")) else emptyList()
            }, budget = budget)
        assertTrue(first.progress.complete)
        val second = PullRequestSearchPager.search("needle", 2, 10,
            fetchPage = { size, _ -> requests.add(size); listOf(pr(3, "needle second")) }, budget = budget)
        assertEquals(listOf(2, 1, 1), requests)
        assertEquals(listOf(3), second.pullRequests.map { it.pullRequestId })
        assertFalse(second.progress.complete)
        assertTrue(budget.exhausted)
        val third = PullRequestSearchPager.search("needle", 2, 10,
            fetchPage = { _, _ -> fail("Exhausted shared budget must not fetch another scope"); emptyList() }, budget = budget)
        assertFalse(third.progress.complete)
    }

    @Test fun `page request budget is shared across empty project scopes`() {
        val budget = PullRequestSearchBudget(maxScanned = 5000, maxPages = 1)
        assertTrue(PullRequestSearchPager.search("needle", 200, 10,
            fetchPage = { _, _ -> emptyList() }, budget = budget).progress.complete)
        val next = PullRequestSearchPager.search("needle", 200, 10,
            fetchPage = { _, _ -> fail("Request budget was consumed by previous scope"); emptyList() }, budget = budget)
        assertFalse(next.progress.complete)
        assertEquals(0, next.progress.scanned)
    }

}
