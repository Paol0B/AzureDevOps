package paol0b.azuredevops.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import paol0b.azuredevops.model.WorkItemType
import paol0b.azuredevops.model.WorkItemTypeState

class WorkItemStateCatalogTest {

    private fun type(name: String, vararg states: Pair<String, String>) = WorkItemType(
        name = name, description = null, color = null, icon = null,
        states = states.map { WorkItemTypeState(name = it.first, color = null, category = it.second) }
    )

    private val customProcess = listOf(
        type("Bug",
            "TODO" to "Proposed", "FIX NEEDED" to "InProgress", "WORK IN PROGRESS" to "InProgress",
            "VERIFICATION NEEDED" to "Resolved", "Closed" to "Completed", "Removed" to "Removed"),
        type("Task",
            "TODO" to "Proposed", "WORK IN PROGRESS" to "InProgress", "AWAITING" to "InProgress",
            "Closed" to "Completed", "Removed" to "Removed")
    )

    @Test
    fun `distinct states keep first occurrence order`() {
        val names = WorkItemStateCatalog.distinctStates(customProcess).map { it.name }
        assertEquals(
            listOf("TODO", "FIX NEEDED", "WORK IN PROGRESS", "VERIFICATION NEEDED", "Closed", "Removed", "AWAITING"),
            names
        )
    }

    @Test
    fun `completed categories are Completed and Removed only`() {
        assertTrue(WorkItemStateCatalog.isCompletedCategory("Completed"))
        assertTrue(WorkItemStateCatalog.isCompletedCategory("removed"))
        assertFalse(WorkItemStateCatalog.isCompletedCategory("Resolved"))
        assertFalse(WorkItemStateCatalog.isCompletedCategory("InProgress"))
        assertFalse(WorkItemStateCatalog.isCompletedCategory(null))
    }

    @Test
    fun `done categories include Resolved`() {
        assertTrue(WorkItemStateCatalog.isDoneCategory("Resolved"))
        assertTrue(WorkItemStateCatalog.isDoneCategory("Completed"))
        assertFalse(WorkItemStateCatalog.isDoneCategory("Proposed"))
    }
}
