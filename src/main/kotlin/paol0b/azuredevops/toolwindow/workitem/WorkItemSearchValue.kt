package paol0b.azuredevops.toolwindow.workitem

/**
 * Immutable value representing the current state of all Work Item list filters.
 * Modeled after [paol0b.azuredevops.toolwindow.filters.PullRequestSearchValue].
 */
data class WorkItemSearchValue(
    val searchQuery: String? = null,
    val type: TypeFilter? = null,
    val state: StateFilter? = null,
    val assignedTo: AssignedToFilter? = null,
    val iteration: IterationFilter? = null,
    val sort: Sort? = null,
    val priority: PriorityFilter? = null
) {
    val filterCount: Int
        get() {
            var count = 0
            if (searchQuery != null) count++
            if (type != null) count++
            if (state != null) count++
            if (assignedTo != null && assignedTo != AssignedToFilter.ME) count++
            if (iteration != null) count++
            if (sort != null) count++
            if (priority != null) count++
            return count
        }

    enum class TypeFilter(val displayName: String) {
        BUG("Bug"),
        TASK("Task"),
        USER_STORY("User Story"),
        FEATURE("Feature"),
        EPIC("Epic");

        override fun toString(): String = displayName
    }

    enum class StateFilter(val displayName: String) {
        NEW("New"),
        ACTIVE("Active"),
        RESOLVED("Resolved"),
        CLOSED("Closed"),
        ALL("All");

        override fun toString(): String = displayName
    }

    enum class AssignedToFilter(val displayName: String) {
        ME("Assigned to me"),
        ALL("All users");

        override fun toString(): String = displayName
    }

    data class IterationFilter(
        val path: String?,
        val name: String,
        val isCurrent: Boolean = false
    ) {
        override fun toString(): String = name
    }

    enum class Sort(val displayName: String) {
        RECENTLY_CHANGED("Recently changed"),
        OLDEST("Oldest"),
        PRIORITY("By priority");

        override fun toString(): String = displayName
    }

    enum class PriorityFilter(val displayName: String, val value: Int) {
        P1("P1 - Critical", 1),
        P2("P2 - High", 2),
        P3("P3 - Medium", 3),
        P4("P4 - Low", 4);

        override fun toString(): String = displayName
    }

    companion object {
        val DEFAULT = WorkItemSearchValue(assignedTo = AssignedToFilter.ME)
        val EMPTY = WorkItemSearchValue()
    }
}

enum class WorkItemQuickFilter(val displayName: String) {
    MY_WORK_ITEMS("My work items"),
    ALL_ACTIVE("All active"),
    MY_BUGS("My bugs"),
    CURRENT_SPRINT("Current sprint");

    override fun toString(): String = displayName
}
