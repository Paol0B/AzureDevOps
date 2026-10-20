package paol0b.azuredevops.toolwindow.review

import org.junit.Assert.*
import org.junit.Test

class DiffCommentSelectionTest {
    private val right: (Int, Boolean) -> Int? = { line, left -> if (left) null else line + 10 }

    @Test fun `partial line keeps selected columns and source line`() {
        val range = DiffCommentSelection.resolve("before selected after", 7, 15, right)!!
        assertFalse(range.isLeftSide)
        assertEquals(11, range.startLine)
        assertEquals(8, range.startOffset)
        assertEquals(16, range.endOffset)
    }
    @Test fun `multiline selection maps first and last positions`() {
        val range = DiffCommentSelection.resolve("first\nsecond\nthird", 2, 9, right)!!
        assertEquals(11, range.startLine)
        assertEquals(12, range.endLine)
        assertEquals(3, range.startOffset)
        assertEquals(4, range.endOffset)
    }
    @Test fun `selection ending at next line start excludes that unselected row`() {
        val range = DiffCommentSelection.resolve("first\nsecond", 0, 6, right)!!
        assertEquals(11, range.endLine)
        assertEquals(6, range.endOffset)
    }
    @Test fun `deleted and shared rows can form a left side range`() {
        val range = DiffCommentSelection.resolve("deleted\nshared", 0, 14) { line, left ->
            if (left) line + 20 else if (line == 0) null else 30
        }!!
        assertTrue(range.isLeftSide)
        assertEquals(21, range.startLine)
        assertEquals(22, range.endLine)
    }
    @Test fun `selections crossing added and deleted rows have no single file range`() {
        assertNull(DiffCommentSelection.resolve("deleted\nadded", 0, 13) { line, left ->
            if (left == (line == 0)) line else null
        })
    }
    @Test fun `unmapped or noncontiguous rows and empty selections are rejected`() {
        assertNull(DiffCommentSelection.resolve("one\ntwo\nthree", 0, 13) { line, _ -> if (line == 1) null else line })
        assertNull(DiffCommentSelection.resolve("one\ntwo", 0, 7) { line, _ -> line * 2 })
        assertNull(DiffCommentSelection.resolve("abc", 1, 1, right))
    }
}
