package paol0b.azuredevops.toolwindow.review

import org.junit.Assert.assertEquals
import org.junit.Test

class CommentLineRangeTest {

    @Test
    fun `uses selected range when clicked line is inside selection`() {
        assertEquals(CommentLineRange(4, 8), CommentLineRange.fromSelection(6, 4, 8))
    }

    @Test
    fun `uses clicked line when click is outside selection`() {
        assertEquals(CommentLineRange(10, 10), CommentLineRange.fromSelection(10, 4, 8))
    }

    @Test
    fun `normalizes reversed selection lines`() {
        assertEquals(CommentLineRange(4, 8), CommentLineRange.fromSelection(5, 8, 4))
    }

    @Test
    fun `uses clicked line when there is no selection`() {
        assertEquals(CommentLineRange(3, 3), CommentLineRange.fromSelection(3, null, null))
    }
}
