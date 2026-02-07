package paol0b.azuredevops.model

import com.google.gson.annotations.SerializedName

/**
 * Policy evaluation result for a Pull Request
 */
data class PolicyEvaluation(
    @SerializedName("evaluationId")
    val evaluationId: String?,
    @SerializedName("configuration")
    val configuration: PolicyConfiguration?,
    @SerializedName("status")
    val status: String?, // "approved", "rejected", "running", "queued", "notApplicable", "broken"
    @SerializedName("context")
    val context: PolicyContext?
) {
    fun isApproved(): Boolean = status == "approved"
    fun isRejected(): Boolean = status == "rejected"
    fun isRunning(): Boolean = status == "running" || status == "queued"
    
    fun getDisplayName(): String {
        return configuration?.type?.displayName 
            ?: configuration?.settings?.displayName 
            ?: "Unknown Policy"
    }
}

data class PolicyConfiguration(
    @SerializedName("id")
    val id: Int?,
    @SerializedName("type")
    val type: PolicyType?,
    @SerializedName("settings")
    val settings: PolicySettings?,
    @SerializedName("isEnabled")
    val isEnabled: Boolean?,
    @SerializedName("isBlocking")
    val isBlocking: Boolean?
)

data class PolicyType(
    @SerializedName("id")
    val id: String?,
    @SerializedName("displayName")
    val displayName: String?
)

data class PolicySettings(
    @SerializedName("displayName")
    val displayName: String?,
    @SerializedName("minimumApproverCount")
    val minimumApproverCount: Int?,
    @SerializedName("creatorVoteCounts")
    val creatorVoteCounts: Boolean?,
    @SerializedName("allowDownvotes")
    val allowDownvotes: Boolean?,
    @SerializedName("resetOnSourcePush")
    val resetOnSourcePush: Boolean?,
    @SerializedName("requiredReviewerIds")
    val requiredReviewerIds: List<String>?,
    @SerializedName("buildDefinitionId")
    val buildDefinitionId: Int?,
    @SerializedName("statusName")
    val statusName: String?,
    @SerializedName("statusGenre")
    val statusGenre: String?
)

data class PolicyContext(
    @SerializedName("isExpired")
    val isExpired: Boolean?,
    @SerializedName("buildDefinitionName")
    val buildDefinitionName: String?
)

data class PolicyEvaluationListResponse(
    val value: List<PolicyEvaluation>?,
    val count: Int?
)

/**
 * Git commit reference for PR commits listing
 */
data class GitCommitRef(
    @SerializedName("commitId")
    val commitId: String?,
    @SerializedName("comment")
    val comment: String?,
    @SerializedName("author")
    val author: GitUserDate?,
    @SerializedName("committer")
    val committer: GitUserDate?,
    @SerializedName("url")
    val url: String?
)

data class GitUserDate(
    @SerializedName("name")
    val name: String?,
    @SerializedName("email")
    val email: String?,
    @SerializedName("date")
    val date: String?
)

data class GitCommitListResponse(
    val value: List<GitCommitRef>?,
    val count: Int?
)
