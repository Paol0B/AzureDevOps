package paol0b.azuredevops.model

import com.google.gson.annotations.SerializedName

/**
 * Rappresenta una modifica di file in una Pull Request
 */
data class PullRequestChange(
    @SerializedName("changeId")
    val changeId: Int?,
    
    @SerializedName("changeType")
    val changeType: String?, // "add", "edit", "delete", "rename"
    
    @SerializedName("item")
    val item: GitItem?,
    
    @SerializedName("originalPath")
    val originalPath: String? // Per rename/move
)

data class GitItem(
    @SerializedName("objectId")
    val objectId: String?, // SHA del blob
    
    @SerializedName("path")
    val path: String?,
    
    @SerializedName("gitObjectType")
    val gitObjectType: String?, // "blob", "tree"
    
    @SerializedName("commitId")
    val commitId: String?,
    
    @SerializedName("url")
    val url: String?
)

data class PullRequestChanges(
    @SerializedName("changeEntries")
    val changeEntries: List<PullRequestChange>?,
    
    @SerializedName("count")
    val count: Int?
)
