package paol0b.azuredevops.toolwindow.review

import org.junit.Assert.*
import org.junit.Test
import paol0b.azuredevops.model.FileCommentRange

class InlineCommentDraftsTest {
    private val key = InlineCommentDraftKey(84, "project", "repo", "/file.kt", FileCommentRange(false, 2, 3, 2, 5), "source", "target")

    @Test fun reopeningTargetRetainsDraftAndPendingSubmission() {
        val drafts = InlineCommentDrafts<MutableList<String>>()
        val original = drafts.getOrCreate(key) { mutableListOf("draft", "pending") }
        drafts.getOrCreate(key.copy(filePath = "/other.kt")) { mutableListOf("other") }
        val reopened = drafts.getOrCreate(key.copy()) { mutableListOf("lost") }
        assertSame(original, reopened)
        assertEquals(listOf("draft", "pending"), reopened)
    }

    @Test fun rangeSideAndRevisionNeverShareDrafts() {
        val drafts = InlineCommentDrafts<Any>()
        val original = drafts.getOrCreate(key) { Any() }
        for (other in listOf(key.copy(sourceCommit = "new"), key.copy(targetCommit = "new"),
            key.copy(range = key.range.copy(isLeftSide = true)), key.copy(range = key.range.copy(endOffset = 6)),
            key.copy(pullRequestId = 85), key.copy(repositoryId = "other"))) {
            assertNotSame(original, drafts.getOrCreate(other) { Any() })
        }
    }

    @Test fun discardOrSuccessClearsOnlyMatchingDraft() {
        val drafts = InlineCommentDrafts<Any>()
        val original = drafts.getOrCreate(key) { Any() }
        val otherKey = key.copy(filePath = "/other.kt")
        val other = drafts.getOrCreate(otherKey) { Any() }
        drafts.remove(key)
        val replacement = drafts.getOrCreate(key) { Any() }
        assertNotSame(original, replacement)
        assertSame(other, drafts.getOrCreate(otherKey) { Any() })
        drafts.remove(key)
        assertNotSame(replacement, drafts.getOrCreate(key) { Any() })
    }
    @Test fun olderRevisionDraftsRemainDiscoverable() {
        val drafts = InlineCommentDrafts<Any>()
        drafts.getOrCreate(key) { Any() }
        val newRevision = key.copy(sourceCommit = "new")
        drafts.getOrCreate(newRevision) { Any() }
        assertEquals(listOf(key, newRevision), drafts.keys())
        drafts.remove(key)
        assertEquals(listOf(newRevision), drafts.keys())
    }
}
