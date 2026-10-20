package paol0b.azuredevops.toolwindow.review

import org.junit.Assert.*
import org.junit.Test

class DiffCommentLocationTest {
    @Test fun `added unified row targets source file line instead of display row`() {
        assertEquals(DiffCommentLocation(false, 143), DiffCommentLocation.fromUnifiedLines(-1, 142))
    }

    @Test fun `deleted unified row targets target file line`() {
        assertEquals(DiffCommentLocation(true, 143), DiffCommentLocation.fromUnifiedLines(142, -1))
    }

    @Test fun `shared unified row prefers source coordinates after insertions`() {
        assertEquals(DiffCommentLocation(false, 146), DiffCommentLocation.fromUnifiedLines(142, 145))
    }

    @Test fun `unmapped unified rows cannot create a comment`() {
        assertNull(DiffCommentLocation.fromUnifiedLines(-1, -1))
    }
}
