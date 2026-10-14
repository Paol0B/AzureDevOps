package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import paol0b.azuredevops.model.WorkItemType
import paol0b.azuredevops.model.WorkItemTypeCategory
import paol0b.azuredevops.model.WorkItemTypeState

/**
 * Project-level cache of the work item types (and their states) configured in the
 * Azure DevOps project.
 *
 * Azure DevOps processes can be customized: a project may use states such as
 * "TODO", "FIX NEEDED" or "WORK IN PROGRESS" instead of the standard
 * New / Active / Resolved / Closed. Every UI element that lists or interprets states
 * (state filter, change-state popup, sprint grouping, board columns) should therefore
 * read them from this catalog instead of hard-coding the Agile defaults.
 *
 * The catalog is populated from `GET _apis/wit/workitemtypes`, whose `states[]` entries
 * carry a `category` (Proposed, InProgress, Resolved, Completed, Removed). Categories are
 * stable across processes, so they are what we use to decide whether a state means "done".
 *
 * That endpoint returns every type of the process, including the hidden ones (Code Review
 * Request/Response, Feedback Request/Response, Shared Steps, Shared Parameter, Test Plan,
 * Test Suite...) and the disabled ones, each with states the user never sees on a board
 * (Requested, Accepted, Declined, Design, Ready, Inactive...). The state lists exposed to
 * the UI are therefore built from the *visible* types only: not disabled and not listed in
 * `Microsoft.HiddenCategory` (`GET _apis/wit/workitemtypecategories`).
 */
@Service(Service.Level.PROJECT)
class WorkItemStateCatalog(private val project: Project) {

    private val logger = Logger.getInstance(WorkItemStateCatalog::class.java)

    @Volatile
    private var types: List<WorkItemType> = emptyList()

    /** Names of the types listed in the hidden category (never shown on boards). */
    @Volatile
    private var hiddenTypeNames: Set<String> = emptySet()

    @Volatile
    var isLoaded: Boolean = false
        private set

    /**
     * Fetches the work item types from Azure DevOps and caches them.
     * Must be called off the EDT (performs a network request).
     */
    @Throws(AzureDevOpsApiException::class)
    fun load(): List<WorkItemType> {
        val client = AzureDevOpsApiClient.getInstance(project)
        val fetched = client.getWorkItemTypes()
        // The categories call is best effort: without it the hidden types are simply kept.
        val hidden = try {
            hiddenTypeNames(client.getWorkItemTypeCategories())
        } catch (e: Exception) {
            logger.warn("Could not load work item type categories, hidden types will not be filtered: ${e.message}")
            emptySet()
        }
        update(fetched, hidden)
        return fetched
    }

    /** Returns the cached types, loading them first if the catalog is still empty. */
    @Throws(AzureDevOpsApiException::class)
    fun ensureLoaded(): List<WorkItemType> = if (isLoaded) types else load()

    /** Replaces the cached types (used by [load] and by tests). */
    fun update(fetched: List<WorkItemType>, hidden: Set<String> = emptySet()) {
        types = fetched
        hiddenTypeNames = hidden
        isLoaded = true
    }

    /** Forces the next [ensureLoaded] call to hit the API again. */
    fun invalidate() {
        isLoaded = false
    }

    /** Every type returned by Azure DevOps, hidden and disabled ones included. */
    fun workItemTypes(): List<WorkItemType> = types

    /** The types a user can see on a board: neither disabled nor in the hidden category. */
    fun visibleWorkItemTypes(): List<WorkItemType> = visibleTypes(types, hiddenTypeNames)

    /**
     * All distinct states across the visible work item types, in the order Azure DevOps
     * returns them. Falls back to every type when nothing is visible (defensive: a project
     * whose categories could not be matched should still get a state list).
     */
    fun allStates(): List<WorkItemTypeState> {
        val visible = visibleWorkItemTypes()
        return distinctStates(if (visible.isNotEmpty()) visible else types)
    }

    /** Names of [allStates]. Empty when the catalog is not loaded. */
    fun stateNames(): List<String> = allStates().mapNotNull { it.name }

    /**
     * State names allowed for the given work item type (hidden types included, so the
     * change-state popup still works on a Test Case or a Feedback Request).
     * Falls back to [stateNames] when the type is unknown, and to [FALLBACK_STATES]
     * when nothing has been loaded.
     */
    fun statesFor(workItemType: String?): List<String> {
        val ofType = types.firstOrNull { it.name.equals(workItemType, ignoreCase = true) }
            ?.states.orEmpty().mapNotNull { it.name }
        if (ofType.isNotEmpty()) return ofType
        return stateNames().ifEmpty { FALLBACK_STATES }
    }

    /**
     * Names of the states whose category is Completed or Removed: the states a user
     * typically wants to hide from an "active work" list.
     * Falls back to [FALLBACK_COMPLETED_STATES] when the catalog is not loaded.
     */
    fun completedStateNames(): List<String> {
        val names = allStates()
            .filter { isCompletedCategory(it.category) }
            .mapNotNull { it.name }
        return names.ifEmpty { FALLBACK_COMPLETED_STATES }
    }

    /**
     * True when the state means the work is finished (category Resolved, Completed or Removed).
     * Used by the sprint progress bar. Without a loaded catalog, falls back to the usual
     * English state names so the behavior stays identical to the previous releases.
     */
    fun isDone(stateName: String): Boolean {
        val state = allStates().firstOrNull { it.name.equals(stateName, ignoreCase = true) }
        if (state != null) {
            return isDoneCategory(state.category)
        }
        val lower = stateName.lowercase()
        return lower == "closed" || lower == "done" || lower == "resolved" || lower == "removed"
    }

    companion object {
        /** Standard Agile states, used only when the API cannot be reached. */
        val FALLBACK_STATES: List<String> = listOf("New", "Active", "Resolved", "Closed", "Removed")
        val FALLBACK_COMPLETED_STATES: List<String> = listOf("Closed", "Removed")

        const val CATEGORY_RESOLVED = "Resolved"
        const val CATEGORY_COMPLETED = "Completed"
        const val CATEGORY_REMOVED = "Removed"

        fun getInstance(project: Project): WorkItemStateCatalog =
            project.getService(WorkItemStateCatalog::class.java)

        fun isCompletedCategory(category: String?): Boolean =
            category.equals(CATEGORY_COMPLETED, ignoreCase = true) ||
                category.equals(CATEGORY_REMOVED, ignoreCase = true)

        fun isDoneCategory(category: String?): Boolean =
            isCompletedCategory(category) || category.equals(CATEGORY_RESOLVED, ignoreCase = true)

        const val HIDDEN_CATEGORY = "Microsoft.HiddenCategory"

        /** Names of the types listed in the hidden category, case preserved. */
        fun hiddenTypeNames(categories: List<WorkItemTypeCategory>): Set<String> =
            categories
                .filter { it.referenceName.equals(HIDDEN_CATEGORY, ignoreCase = true) }
                .flatMap { it.workItemTypes.orEmpty() }
                .mapNotNull { it.name }
                .toSet()

        /** Types that are neither disabled nor hidden, order preserved. */
        fun visibleTypes(types: List<WorkItemType>, hidden: Set<String>): List<WorkItemType> =
            types.filter { type ->
                type.isDisabled != true &&
                    hidden.none { it.equals(type.name, ignoreCase = true) }
            }

        /** Distinct states (by name) across all types, first occurrence wins, order preserved. */
        fun distinctStates(types: List<WorkItemType>): List<WorkItemTypeState> {
            val seen = linkedSetOf<String>()
            val result = mutableListOf<WorkItemTypeState>()
            for (type in types) {
                for (state in type.states.orEmpty()) {
                    val name = state.name ?: continue
                    if (seen.add(name)) result.add(state)
                }
            }
            return result
        }
    }
}
