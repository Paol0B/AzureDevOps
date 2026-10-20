package paol0b.azuredevops.model

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test

class ReviewerIdentityTest {
    private fun pr(reviewers: String): PullRequest = Gson().fromJson("""{"pullRequestId":1,"title":"Review","sourceRefName":"refs/heads/f","targetRefName":"refs/heads/main","status":"active","reviewers":$reviewers}""", PullRequest::class.java)

    @Test fun `duplicate names and missing unique names do not select another reviewer`() {
        val pullRequest = pr("""[{"id":"other","displayName":"Sam","uniqueName":null},{"id":"self","displayName":"Sam","uniqueName":null}]""")
        assertEquals("self", pullRequest.findReviewerById("SELF")!!.id)
        assertNull(pullRequest.findReviewerById("not-listed"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `missing identity never matches a reviewer with missing ID`() {
        pr("""[{"id":null,"displayName":"Sam"}]""").findReviewerById("")
    }
}
