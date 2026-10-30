package paol0b.azuredevops.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import paol0b.azuredevops.model.BacklogConfiguration
import paol0b.azuredevops.model.BacklogLevel
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

    private fun level(id: String, vararg typeNames: String) = BacklogLevel(
        id = id, name = null, workItemTypes = typeNames.map { WorkItemTypeReference(name = it) }
    )

    /** The types Azure DevOps returns for a process: visible ones plus the hidden/disabled ones. */
    private val hiddenProcess = listOf(
        type("Bug", "TODO" to "Proposed", "Closed" to "Completed"),
        type("Code Review Request", "Requested" to "Proposed", "Accepted" to "InProgress", "Closed" to "Completed"),
        type("Feedback Response", "Active" to "InProgress", "Declined" to "Removed"),
        type("Test Plan", "Design" to "Proposed", "Ready" to "InProgress", "Inactive" to "Completed"),
        // Not hidden, but never on a board either
        type("Test Case", "Design" to "Proposed", "Ready" to "InProgress", "Closed" to "Completed"),
        type("Epic", "New" to "Proposed", "Active" to "InProgress", "Resolved" to "Resolved", "Closed" to "Completed"),
        type("Issue", "Active" to "InProgress", "Closed" to "Completed", disabled = true)
    )

    private val categories = listOf(
        category("Microsoft.BugCategory", "Bug"),
        category("Microsoft.EpicCategory", "Epic"),
        category("Microsoft.TestCaseCategory", "Test Case"),
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
    fun `board type names come from the backlog categories only`() {
        assertEquals(setOf("Bug", "Epic"), WorkItemStateCatalog.boardTypeNames(categories))
        assertEquals(emptySet<String>(), WorkItemStateCatalog.boardTypeNames(emptyList()))
    }

    @Test
    fun `visible types are the enabled board types when categories are known`() {
        val hidden = WorkItemStateCatalog.hiddenTypeNames(categories)
        val board = WorkItemStateCatalog.boardTypeNames(categories)
        val visible = WorkItemStateCatalog.visibleTypes(hiddenProcess, hidden, board).map { it.name }
        assertEquals(listOf("Bug", "Epic"), visible)
    }

    @Test
    fun `without board categories visible types drop hidden and disabled types only`() {
        val hidden = WorkItemStateCatalog.hiddenTypeNames(categories)
        val visible = WorkItemStateCatalog.visibleTypes(hiddenProcess, hidden).map { it.name }
        assertEquals(listOf("Bug", "Test Case", "Epic"), visible)
    }

    @Test
    fun `states of hidden, disabled and non-board types are not offered`() {
        val hidden = WorkItemStateCatalog.hiddenTypeNames(categories)
        val board = WorkItemStateCatalog.boardTypeNames(categories)
        val visible = WorkItemStateCatalog.visibleTypes(hiddenProcess, hidden, board)
        val names = WorkItemStateCatalog.distinctStates(visible).map { it.name }
        assertEquals(listOf("TODO", "Closed", "New", "Active", "Resolved"), names)
        assertFalse(names.contains("Requested"))
        assertFalse(names.contains("Design"))
        assertFalse(names.contains("Ready"))
    }

    @Test
    fun `without categories every enabled type stays visible`() {
        val visible = WorkItemStateCatalog.visibleTypes(hiddenProcess, emptySet()).map { it.name }
        assertEquals(listOf("Bug", "Code Review Request", "Feedback Response", "Test Plan", "Test Case", "Epic"), visible)
    }

    @Test
    fun `a board type listed in the hidden category is not visible`() {
        // Inherited process: Feature is in the Feature category AND in the hidden category
        val types = listOf(
            type("Feature", "New" to "Proposed", "Closed" to "Completed"),
            type("User Story", "TODO" to "Proposed", "Closed" to "Completed")
        )
        val hidden = setOf("Feature")
        val board = setOf("Feature", "User Story")
        val visible = WorkItemStateCatalog.visibleTypes(types, hidden, board).map { it.name }
        assertEquals(listOf("User Story"), visible)
    }

    /** A customized inherited process as returned by Azure DevOps (states only). */
    @Test
    fun `customized process only offers the states of the backlog types`() {
        val custom = arrayOf(
            "TODO" to "Proposed", "WORK IN PROGRESS" to "InProgress", "VERIFICATION NEEDED" to "InProgress",
            "AWAITING" to "InProgress", "FIX NEEDED" to "InProgress", "Closed" to "Completed", "Removed" to "Removed"
        )
        val types = listOf(
            type("Bug", *custom),
            type("Code Review Request", "Requested" to "Proposed", "Closed" to "Completed"),
            type("Epic", "New" to "Proposed", "Active" to "InProgress", "Resolved" to "InProgress", "Closed" to "Completed", disabled = true),
            type("Feature", *custom, disabled = true),
            type("Feedback Request", "Active" to "InProgress", "Closed" to "Completed"),
            type("Task", "TODO" to "Proposed", "WORK IN PROGRESS" to "InProgress", "Closed" to "Completed", "Removed" to "Removed"),
            type("Test Case", "Design" to "Proposed", "Ready" to "InProgress", "Closed" to "Completed"),
            type("Test Plan", "Active" to "InProgress", "Inactive" to "Completed"),
            type("Test Suite", "In Planning" to "InProgress", "In Progress" to "InProgress", "Completed" to "Completed"),
            type("User Story", *custom),
            type("Issue", *custom, disabled = true),
            type("Iteration", "TODO" to "Proposed", "VERIFICATION NEEDED" to "InProgress", "WORK IN PROGRESS" to "InProgress", "Closed" to "Completed"),
            type("Test", "New" to "Proposed", "Active" to "InProgress", "Closed" to "Completed"),
            type("Customer Story", "TODO" to "Proposed", "WORK IN PROGRESS" to "InProgress", "Resolved" to "InProgress", "Closed" to "Completed"),
            type("Scanner Story", *custom),
            type("Ticket", *custom),
            type("Internal project", *custom)
        )
        val processCategories = listOf(
            category("Microsoft.BugCategory", "Bug"),
            category("Microsoft.EpicCategory", "Epic"),
            category("Microsoft.FeatureCategory", "Customer Story", "Feature"),
            category("Microsoft.RequirementCategory", "Ticket", "Internal project", "User Story"),
            category("Microsoft.TestCaseCategory", "Test Case"),
            category("Microsoft.TestPlanCategory", "Test Plan"),
            category("Microsoft.TestSuiteCategory", "Test Suite"),
            category("Microsoft.TaskCategory", "Iteration", "Task"),
            category("Microsoft.HiddenCategory", "Epic", "Feature", "Issue", "Code Review Request",
                "Feedback Request", "Test Plan", "Test Suite")
        )
        val hidden = WorkItemStateCatalog.hiddenTypeNames(processCategories)
        val board = WorkItemStateCatalog.boardTypeNames(processCategories)
        val visible = WorkItemStateCatalog.visibleTypes(types, hidden, board)
        assertEquals(
            listOf("Bug", "Task", "User Story", "Iteration", "Customer Story", "Ticket", "Internal project"),
            visible.map { it.name }
        )
        val names = WorkItemStateCatalog.distinctStates(visible).map { it.name }
        assertEquals(
            listOf("TODO", "WORK IN PROGRESS", "VERIFICATION NEEDED", "AWAITING", "FIX NEEDED", "Closed", "Removed", "Resolved"),
            names
        )
        // Before the fix the hidden filter alone still let these through
        for (s in listOf("Design", "Ready", "New", "Active")) assertFalse(s, names.contains(s))
    }

    @Test
    fun `backlog configuration gives the board types, custom portfolio levels included`() {
        val backlog = BacklogConfiguration(
            requirementBacklog = level("Microsoft.RequirementCategory", "User Story", "Ticket"),
            taskBacklog = level("Microsoft.TaskCategory", "Task"),
            portfolioBacklogs = listOf(
                level("Custom.InitiativeCategory", "Initiative"),
                level("Microsoft.EpicCategory", "Epic")
            ),
            bugsBehavior = "off",
            hiddenBacklogs = listOf("Microsoft.EpicCategory")
        )
        val board = WorkItemStateCatalog.boardTypeNames(backlog, categories)
        // Bugs come from the bug category even when they are kept off the backlog
        assertEquals(setOf("User Story", "Ticket", "Task", "Initiative", "Epic", "Bug"), board)
        assertFalse(board.contains("Test Case"))
    }

    @Test
    fun `without backlog configuration the standard board categories are used`() {
        assertEquals(setOf("Bug", "Epic"), WorkItemStateCatalog.boardTypeNames(null, categories))
        val empty = BacklogConfiguration(null, null, null, null, null)
        assertEquals(setOf("Bug", "Epic"), WorkItemStateCatalog.boardTypeNames(empty, categories))
    }

    @Test
    fun `a disabled board type is not visible`() {
        val board = setOf("Bug", "Issue")
        val visible = WorkItemStateCatalog.visibleTypes(hiddenProcess, emptySet(), board).map { it.name }
        assertEquals(listOf("Bug"), visible)
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
