package paol0b.azuredevops.services

import org.junit.Assert.*
import org.junit.Test
import paol0b.azuredevops.model.*

class RegularEditorCommentTargetTest {
    private val change = PullRequestChange(1, 7, "edit", GitItem("sha", "/src/file.kt", "blob", null, null), null)
    @Test fun `caret comments target the source line`() {
        val target = RegularEditorCommentTarget.resolve("one\ntwo\n", "one\ntwo\n", "/src/file.kt", listOf(change), 5)
        assertEquals(FileCommentRange(false, 2), target.range)
        assertEquals(7, target.change.changeTrackingId)
    }
    @Test fun `selection preserves source columns and excludes unselected next line`() {
        val target = RegularEditorCommentTarget.resolve("one\ntwo\n", "one\r\ntwo\r\n", "/src/file.kt", listOf(change), 0, 1, 8)
        assertEquals(FileCommentRange(false, 1, 2, 2, 4), target.range)
    }
    @Test fun `unsaved or unpushed code cannot be anchored to different PR text`() {
        assertThrows(IllegalArgumentException::class.java) {
            RegularEditorCommentTarget.resolve("changed\none\n", "one\n", "/src/file.kt", listOf(change), 0)
        }
    }
    @Test fun `file must be an exact source path changed by the PR`() {
        for (path in listOf("/file.kt", "/other/src/file.kt", "/src/File.kt")) {
            assertThrows(IllegalArgumentException::class.java) {
                RegularEditorCommentTarget.resolve("one", "one", path, listOf(change), 0)
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            RegularEditorCommentTarget.resolve("one", "one", "/src/file.kt", listOf(change.copy(changeType = "delete")), 0)
        }
    }
}
