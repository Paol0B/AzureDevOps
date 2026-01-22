package paol0b.azuredevops.model

import com.google.gson.annotations.SerializedName

/**
 * Merge strategy options for completing a Pull Request
 */
enum class MergeStrategy {
    @SerializedName("noFastForward")
    NO_FAST_FORWARD,
    
    @SerializedName("squash")
    SQUASH,
    
    @SerializedName("rebase")
    REBASE,
    
    @SerializedName("rebaseMerge")
    REBASE_MERGE;
    
    fun getDisplayName(): String = when(this) {
        NO_FAST_FORWARD -> "Merge commit (no fast-forward)"
        SQUASH -> "Squash commit"
        REBASE -> "Rebase and fast-forward"
        REBASE_MERGE -> "Rebase and merge"
    }
    
    fun toApiValue(): String = when(this) {
        NO_FAST_FORWARD -> "noFastForward"
        SQUASH -> "squash"
        REBASE -> "rebase"
        REBASE_MERGE -> "rebaseMerge"
    }
}

/**
 * Request to complete a Pull Request
 */
data class CompletePullRequestRequest(
    @SerializedName("status")
    val status: String = "completed",
    
    @SerializedName("lastMergeSourceCommit")
    val lastMergeSourceCommit: CommitRef,
    
    @SerializedName("completionOptions")
    val completionOptions: CompletionOptions
)

/**
 * Options for completing a Pull Request
 */
data class CompletionOptions(
    @SerializedName("mergeStrategy")
    val mergeStrategy: String,
    
    @SerializedName("deleteSourceBranch")
    val deleteSourceBranch: Boolean = false,
    
    @SerializedName("mergeCommitMessage")
    val mergeCommitMessage: String? = null,
    
    @SerializedName("bypassPolicy")
    val bypassPolicy: Boolean = false,
    
    @SerializedName("bypassReason")
    val bypassReason: String? = null,
    
    @SerializedName("transitionWorkItems")
    val transitionWorkItems: Boolean = true
)

/**
 * Request to set auto-complete on a Pull Request
 */
data class SetAutoCompleteRequest(
    @SerializedName("autoCompleteSetBy")
    val autoCompleteSetBy: AutoCompleteSetBy,
    
    @SerializedName("completionOptions")
    val completionOptions: CompletionOptions
)

/**
 * User who set the auto-complete
 */
data class AutoCompleteSetBy(
    @SerializedName("id")
    val id: String
)

/**
 * Result of a PR completion or auto-complete operation
 */
data class CompletionResult(
    val success: Boolean,
    val message: String,
    val pullRequest: PullRequest? = null,
    val error: String? = null
)
