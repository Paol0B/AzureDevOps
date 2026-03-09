package paol0b.azuredevops.model

import com.google.gson.annotations.SerializedName

/**
 * Represents a file change in a Pull Request
 */
data class PullRequestChange(
    @SerializedName("changeId")
    val changeId: Int?,

    @SerializedName("changeTrackingId")
    val changeTrackingId: Int?,
    
    @SerializedName("changeType")
    val changeType: String?, // "add", "edit", "delete", "rename"
    
    @SerializedName("item")
    val item: GitItem?,
    
    @SerializedName("originalPath")
    val originalPath: String? // For rename/move
)

private val FILE_CHANGE_TYPE_PRIORITY = listOf("add", "delete", "rename", "edit")

fun PullRequestChange.changeTypeTokens(): Set<String> {
    return changeType
        ?.split(',')
        ?.asSequence()
        ?.map { it.trim().lowercase() }
        ?.filter { it.isNotEmpty() }
        ?.toSet()
        .orEmpty()
}

fun PullRequestChange.hasChangeType(type: String): Boolean {
    return changeTypeTokens().contains(type.lowercase())
}

fun PullRequestChange.primaryChangeType(): String {
    val tokens = changeTypeTokens()
    return FILE_CHANGE_TYPE_PRIORITY.firstOrNull(tokens::contains)
        ?: changeType?.substringBefore(',')?.trim()?.lowercase()
        ?: "unknown"
}

fun PullRequestChange.previousPath(): String {
    return if (hasChangeType("rename")) {
        originalPath ?: item?.path.orEmpty()
    } else {
        item?.path.orEmpty()
    }
}

data class GitItem(
    @SerializedName("objectId")
    val objectId: String?, // SHA object
    
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
