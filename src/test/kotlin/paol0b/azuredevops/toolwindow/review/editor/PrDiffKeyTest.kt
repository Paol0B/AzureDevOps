package paol0b.azuredevops.toolwindow.review.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PrDiffKeyTest {

    @Test
    fun `different files in the same PR get different tabs`() {
        assertNotEquals(
            PrDiffKey(42, "/src/First.kt", "repo-1"),
            PrDiffKey(42, "/src/Second.kt", "repo-1")
        )
    }

    @Test
    fun `same PR id in different repositories gets different tabs`() {
        assertNotEquals(
            PrDiffKey(42, "/src/File.kt", "repo-1"),
            PrDiffKey(42, "/src/File.kt", "repo-2")
        )
    }

    @Test
    fun `same PR file identity reuses its existing tab`() {
        assertEquals(
            PrDiffKey(42, "/src/File.kt", "repo-1"),
            PrDiffKey(42, "/src/File.kt", "repo-1")
        )
    }
}
