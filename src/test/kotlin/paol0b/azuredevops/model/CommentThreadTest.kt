package paol0b.azuredevops.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class CommentThreadTest {

    @Test
    fun `position context falls back when pull request context only has iteration metadata`() {
        val metadataOnlyContext = ThreadContext(null, null, null, null, null)
        val positionedContext = ThreadContext(
            filePath = "/src/App.kt",
            rightFileStart = LineInfo(line = 17, offset = 1),
            rightFileEnd = LineInfo(line = 17, offset = 1),
            leftFileStart = null,
            leftFileEnd = null
        )
        val thread = CommentThread(
            id = 1,
            pullRequestThreadContext = metadataOnlyContext,
            comments = emptyList(),
            status = ThreadStatus.Active,
            threadContext = positionedContext,
            isDeleted = false
        )

        assertSame(positionedContext, thread.getPositionContext())
        assertEquals(17, thread.getPositionContext()?.rightFileStart?.line)
    }
}
