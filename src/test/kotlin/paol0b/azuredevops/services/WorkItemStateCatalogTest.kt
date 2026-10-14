package paol0b.azuredevops.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import paol0b.azuredevops.model.WorkItemType
import paol0b.azuredevops.model.WorkItemTypeCategory
import paol0b.azuredevops.model.WorkItemTypeReference
import paol0b.azuredevops.model.WorkItemTypeState

class WorkItemStateCatalogTest {

    private fun type(name: String, vararg states: Pair<String, String>, disabled: Boolean? = null) = WorkItemType(
        name = name, description = null, color = null, icon = null,
        states = states.map { WorkItemTypeState(name = it.first, color = null, category = it.second) },
        isDisabled = disabled
    )

    private fun category(referenceName: String, vararg typeNames: String) = WorkItemTypeCategory(
        referenceName = referenceName, name = null,
        workItemTypes = typeNames.map { WorkItemTypeReference(name = it) }
    )

    /** The types Azure DevOps returns for a process: visible ones plus the hidden/disabled ones. */
    private val hiddenProcess = listOf(
        type("Bug", "TODO" to "Proposed", "Closed" to "Completed"),
        type("Code Review Request", "Requested" to "Proposed", "Accepted" to "InProgress", "Closed" to "Completed"),
        type("Feedback Response", "Active" to "InProgress", "Declined" to "Removed"),
        type("Test Plan", "Design" to "Proposed", "Ready" to "InProgress", "Inactive" to "Completed"),
        type("Issue", "Active" to "InProgress", "Closed" to "Completed", disabled = true)
    )

    private val categories = listOf(
        category("Microsoft.BugCategory", "Bug"),
        category("Microsoft.HiddenCategory", "Code Review Request", "Feedback Response", "Test Plan")
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
    fun `hidden type names come from the hidden category only`() {
        assertEquals(
            setOf("Code Review Request", "Feedback Response", "Test Plan"),
            WorkItemStateCatalog.hiddenTypeNames(categories)
        )
        assertEquals(emptySet<String>(), WorkItemStateCatalog.hiddenTypeNames(emptyList()))
    }

    @Test
    fun `visible types drop hidden and disabled types`() {
        val hidden = WorkItemStateCatalog.hiddenTypeNames(categories)
        val visible = WorkItemStateCatalog.visibleTypes(hiddenProcess, hidden).map { it.name }
        assertEquals(listOf("Bug"), visible)
    }

    @Test
    fun `states of hidden and disabled types are not offered`() {
        val hidden = WorkItemStateCatalog.hiddenTypeNames(categories)
        val visible = WorkItemStateCatalog.visibleTypes(hiddenProcess, hidden)
        val names = WorkItemStateCatalog.distinctStates(visible).map { it.name }
        assertEquals(listOf("TODO", "Closed"), names)
        assertFalse(names.contains("Requested"))
        assertFalse(names.contains("Design"))
    }

    @Test
    fun `without categories every enabled type stays visible`() {
        val visible = WorkItemStateCatalog.visibleTypes(hiddenProcess, emptySet()).map { it.name }
        assertEquals(listOf("Bug", "Code Review Request", "Feedback Response", "Test Plan"), visible)
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
