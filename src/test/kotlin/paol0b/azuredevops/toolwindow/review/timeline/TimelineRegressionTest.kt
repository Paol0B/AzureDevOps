package paol0b.azuredevops.toolwindow.review.timeline

import com.google.gson.Gson
import org.junit.Assert.*
import org.junit.Test
import paol0b.azuredevops.model.*

class TimelineRegressionTest {
    private val gson = Gson()
    private val pr = gson.fromJson("""{"pullRequestId":1,"title":"Review","sourceRefName":"refs/heads/f","targetRefName":"refs/heads/main","status":"active"}""", PullRequest::class.java)
    private fun thread(): CommentThread = gson.fromJson("""{
        "id":10,"status":"active","isDeleted":false,
        "pullRequestThreadContext":{"changeTrackingId":7,"iterationContext":{"firstComparingIteration":1,"secondComparingIteration":3}},
        "threadContext":{"filePath":"/src/File.kt","leftFileStart":{"line":12,"offset":1},"leftFileEnd":{"line":14,"offset":1}},
        "comments":[
            {"id":1,"content":"root","commentType":"text","author":{"id":"user","displayName":"Reviewer"}},
            {"id":2,"content":"middle","commentType":"text","isDeleted":false},
            {"id":3,"content":"last","commentType":"text"}
        ]
    }""", CommentThread::class.java)

    @Test fun `tracked left side comment keeps its actual file coordinates`() {
        val entry = TimelineConverter.buildEntries(pr, listOf(thread())).single { it.type == TimelineEntryType.COMMENT_THREAD }
        assertEquals("/src/File.kt", entry.filePath)
        assertEquals(12, entry.lineStart)
        assertEquals(14, entry.lineEnd)
        assertTrue(entry.isLeftSide)
    }

    @Test fun `soft deleting a middle reply invalidates the timeline cache`() {
        val before = thread()
        val after = before.copy(comments = before.comments!!.map { if (it.id == 2) it.copy(isDeleted = true) else it })
        assertNotEquals(TimelineConverter.calculateHash(listOf(before), emptyList()), TimelineConverter.calculateHash(listOf(after), emptyList()))
        val entry = TimelineConverter.buildEntries(pr, listOf(after)).single { it.type == TimelineEntryType.COMMENT_THREAD }
        assertEquals(listOf(3), entry.replies.map { it.commentId })
    }

    @Test fun `editing a middle reply invalidates the timeline cache`() {
        val before = thread()
        val after = before.copy(comments = before.comments!!.map { if (it.id == 2) it.copy(content = "updated", lastContentUpdatedDate = "2026-09-30T12:00:00Z") else it })
        assertNotEquals(TimelineConverter.calculateHash(listOf(before), emptyList()), TimelineConverter.calculateHash(listOf(after), emptyList()))
    }

    @Test fun `deleting an entire thread invalidates the timeline cache`() {
        val before = thread()
        assertNotEquals(TimelineConverter.calculateHash(listOf(before), emptyList()), TimelineConverter.calculateHash(listOf(before.copy(isDeleted = true)), emptyList()))
    }
}
