package paol0b.azuredevops.services

import org.junit.Assert.*
import org.junit.Test
import paol0b.azuredevops.model.GitItem
import paol0b.azuredevops.model.PullRequestChange

class ReviewedFilesStateTest {
    private fun change(sha: String? = "abcdef0123456789", path: String = "/.ownership/meetingRoom.json") =
        PullRequestChange(1, 2, "edit", GitItem(sha, path, "blob", null, null), null)

    private fun response(viewedState: String) = """{"dataProviders":{"${ReviewedFilesState.PROVIDER}":{"visit":{"viewedState":$viewedState}}}}"""

    @Test fun `website marks apply only to the matching file version`() {
        val state = ReviewedFilesState.decode(response("\"{\\\"hashes\\\":{\\\"1@abcdef01@/.ownership/meetingRoom.json\\\":2}}\""))
        assertTrue(state.isReviewed(change()))
        assertFalse(state.isReviewed(change("fedcba9876543210")))
        assertFalse(state.isReviewed(change(path = "/other/meetingRoom.json")))
    }

    @Test fun `website unreviewed marks and a new user's empty state are unchecked`() {
        assertFalse(ReviewedFilesState.decode(response("\"{\\\"hashes\\\":{\\\"1@abcdef01@/.ownership/meetingRoom.json\\\":1}}\"")).isReviewed(change()))
        assertFalse(ReviewedFilesState.decode(response("\"{\\\"hashes\\\":[]}\"")).isReviewed(change()))
        assertFalse(ReviewedFilesState.decode(response("null")).isReviewed(change()))
    }

    @Test fun `marking a file updates only that version in the correct PR repository`() {
        val request = ReviewedFilesState.query("repo-id", 123, change(), true)
        assertEquals(listOf(ReviewedFilesState.PROVIDER), request["contributionIds"])
        val context = request["dataProviderContext"] as Map<*, *>
        val properties = context["properties"] as Map<*, *>
        assertEquals("repo-id", properties["repositoryId"])
        assertEquals(123, properties["pullRequestId"])
        assertEquals(listOf("1@abcdef01@/.ownership/meetingRoom.json"), properties["modifyHashes"])
        assertEquals(2, properties["modifyViewedStatus"])
        val unchecked = (ReviewedFilesState.query("repo-id", 123, change(), false)["dataProviderContext"] as Map<*, *>)["properties"] as Map<*, *>
        assertEquals(1, unchecked["modifyViewedStatus"])
    }

    @Test fun `sync reads do not modify existing website marks`() {
        val context = ReviewedFilesState.query("repo-id", 123)["dataProviderContext"] as Map<*, *>
        val properties = context["properties"] as Map<*, *>
        assertFalse(properties.containsKey("modifyHashes"))
        assertFalse(properties.containsKey("modifyViewedStatus"))
    }

    @Test fun `unavailable or malformed provider data must not appear as unchecked files`() {
        for (body in listOf("{}", """{"dataProviders":{}}""", response("\"broken\""))) {
            assertThrows(IllegalStateException::class.java) { ReviewedFilesState.decode(body) }
        }
    }

    @Test fun `deleted files use the original path when no item is returned`() {
        val deleted = PullRequestChange(1, 2, "delete", null, "/deleted.json")
        assertEquals("1@00000000@/deleted.json", ReviewedFilesState.hash(deleted))
    }
}
