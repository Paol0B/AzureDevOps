package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import paol0b.azuredevops.model.WorkItemType
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
 */
@Service(Service.Level.PROJECT)
class WorkItemStateCatalog(private val project: Project) {

    @Volatile
    private var types: List<WorkItemType> = emptyList()

    @Volatile
    var isLoaded: Boolean = false
        private set

    /**
     * Fetches the work item types from Azure DevOps and caches them.
     * Must be called off the EDT (performs a network request).
     */
    @Throws(AzureDevOpsApiException::class)
    fun load(): List<WorkItemType> {
        val fetched = AzureDevOpsApiClient.getInstance(project).getWorkItemTypes()
        update(fetched)
        return fetched
    }

    /** Returns the cached types, loading them first if the catalog is still empty. */
    @Throws(AzureDevOpsApiException::class)
    fun ensureLoaded(): List<WorkItemType> = if (isLoaded) types else load()

    /** Replaces the cached types (used by [load] and by tests). */
    fun update(fetched: List<WorkItemType>) {
        types = fetched
        isLoaded = true
    }

    /** Forces the next [ensureLoaded] call to hit the API again. */
    fun invalidate() {
        isLoaded = false
    }

    fun workItemTypes(): List<WorkItemType> = types

    /** All distinct states across every work item type, in the order Azure DevOps returns them. */
    fun allStates(): List<WorkItemTypeState> = distinctStates(types)

    /** Names of [allStates]. Empty when the catalog is not loaded. */
    fun stateNames(): List<String> = allStates().mapNotNull { it.name }

    /**
     * State names allowed for the given work item type.
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
