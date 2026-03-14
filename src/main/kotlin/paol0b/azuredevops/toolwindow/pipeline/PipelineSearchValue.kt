package paol0b.azuredevops.toolwindow.pipeline

/**
 * Immutable value representing the current state of all Pipeline list filters.
 * Modeled after [paol0b.azuredevops.toolwindow.filters.PullRequestSearchValue].
 */
data class PipelineSearchValue(
    val searchQuery: String? = null,
    val result: ResultFilter? = null,
    val definition: DefinitionFilter? = null,
    val branch: String? = null,
    val requestedBy: RequestedByFilter? = null,
    val sort: Sort? = null
) {

    val filterCount: Int
        get() {
            var count = 0
            if (searchQuery != null) count++
            if (result != null) count++
            if (definition != null) count++
            if (branch != null) count++
            if (requestedBy != null) count++
            if (sort != null) count++
            return count
        }

    enum class ResultFilter(val displayName: String, val apiResult: String?, val apiStatus: String?) {
        SUCCEEDED("Succeeded", "succeeded", null),
        FAILED("Failed", "failed", null),
        CANCELED("Canceled", "canceled", null),
        PARTIALLY_SUCCEEDED("Partially Succeeded", "partiallySucceeded", null),
        IN_PROGRESS("In Progress", null, "inProgress");

        override fun toString(): String = displayName
    }

    data class DefinitionFilter(
        val id: Int,
        val name: String
    ) {
        override fun toString(): String = name
    }

    enum class RequestedByFilter(val displayName: String) {
        ME("Me"),
        ALL("All Users");

        override fun toString(): String = displayName
    }

    enum class Sort(val displayName: String) {
        NEWEST("Newest first"),
        OLDEST("Oldest first");

        override fun toString(): String = displayName
    }

    companion object {
        val DEFAULT = PipelineSearchValue()
        val EMPTY = PipelineSearchValue()
    }
}

enum class PipelineQuickFilter(val displayName: String) {
    ALL_RUNS("All runs"),
    MY_RUNS("My runs"),
    FAILED_RUNS("Failed runs"),
    RUNNING("Currently running");

    override fun toString(): String = displayName
}
