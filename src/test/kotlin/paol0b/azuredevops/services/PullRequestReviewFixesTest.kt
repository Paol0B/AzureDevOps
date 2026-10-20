package paol0b.azuredevops.services

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import paol0b.azuredevops.model.PullRequest

class PullRequestReviewFixesTest {
    private fun pr(id: Int, target: String) = Gson().fromJson(
        """{"pullRequestId":$id,"title":"Fix","sourceRefName":"refs/heads/hotfix","targetRefName":"refs/heads/$target","status":"active"}""", PullRequest::class.java)

    @Test fun `multiple active PRs require selection and show their targets`() {
        val first = pr(1, "main")
        val second = pr(2, "release")
        val selected = BranchPullRequestSelection.choose(listOf(first, second)) { labels ->
            assertTrue(labels[0].contains("#1") && labels[0].contains("main"))
            assertTrue(labels[1].contains("#2") && labels[1].contains("release"))
            1
        }
        assertSame(second, selected)
        assertNull(BranchPullRequestSelection.choose(listOf(first, second)) { -1 })
    }

    @Test fun `legacy branch callers report ambiguity instead of no PR`() {
        assertNull(BranchPullRequestSelection.unambiguous(emptyList()))
        val only = pr(1, "main")
        assertSame(only, BranchPullRequestSelection.unambiguous(listOf(only)))
        try {
            BranchPullRequestSelection.unambiguous(listOf(only, pr(2, "release")))
            fail("Expected explicit ambiguous target error")
        } catch (error: IllegalStateException) {
            assertTrue(error.message!!.contains("Multiple active PRs"))
        }
    }

    @Test fun `single active PR does not prompt`() {
        val only = pr(1, "main")
        assertSame(only, BranchPullRequestSelection.choose(listOf(only)) { fail("Unexpected chooser"); -1 })
    }

    @Test fun `missing numeric ID falls back to title search`() {
        assertNull(PullRequestExactSearch.resolve("1234") { throw AzureDevOpsApiException("missing", statusCode = 404) })
        assertEquals(1234, PullRequestExactSearch.resolve("1234") { pr(it.id, "main") }!!.pullRequestId)
    }

    @Test fun `authentication errors and explicit IDs never fall back`() {
        for ((query, code) in listOf("1234" to 401, "1234" to 403, "#1234" to 404,
            "https://dev.azure.com/org/p/_git/r/pullrequest/1234" to 404)) {
            val error = AzureDevOpsApiException("failure", statusCode = code)
            try { PullRequestExactSearch.resolve(query) { throw error }; fail("Expected lookup error") }
            catch (caught: AzureDevOpsApiException) { assertSame(error, caught) }
        }
    }
}
