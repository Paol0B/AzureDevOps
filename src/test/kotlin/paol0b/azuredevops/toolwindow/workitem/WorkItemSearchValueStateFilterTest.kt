package paol0b.azuredevops.toolwindow.workitem

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkItemSearchValueStateFilterTest {

    @Test
    fun `empty selection is the ALL filter`() {
        val filter = WorkItemSearchValue.StateFilter()
        assertTrue(filter.isAll)
        assertEquals("All", filter.displayName)
        assertEquals(WorkItemSearchValue.StateFilter.ALL, filter)
    }

    @Test
    fun `display name lists one or two states`() {
        assertEquals("WORK IN PROGRESS", WorkItemSearchValue.StateFilter(listOf("WORK IN PROGRESS")).displayName)
        assertEquals("TODO, AWAITING", WorkItemSearchValue.StateFilter(listOf("TODO", "AWAITING")).displayName)
    }

    @Test
    fun `display name summarizes three or more states`() {
        val filter = WorkItemSearchValue.StateFilter(listOf("TODO", "AWAITING", "FIX NEEDED"))
        assertFalse(filter.isAll)
        assertEquals("3 states", filter.displayName)
    }

    @Test
    fun `excluded states are prefixed with Not`() {
        assertEquals("Not Closed", WorkItemSearchValue.StateFilter(listOf("Closed"), exclude = true).displayName)
        assertEquals("Not Closed, Removed", WorkItemSearchValue.StateFilter(listOf("Closed", "Removed"), exclude = true).displayName)
        assertEquals("Not 3 states", WorkItemSearchValue.StateFilter(listOf("a", "b", "c"), exclude = true).displayName)
    }

    @Test
    fun `state filter counts as one active filter`() {
        val value = WorkItemSearchValue(state = WorkItemSearchValue.StateFilter(listOf("TODO")))
        assertEquals(1, value.filterCount)
    }
}
