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
}
