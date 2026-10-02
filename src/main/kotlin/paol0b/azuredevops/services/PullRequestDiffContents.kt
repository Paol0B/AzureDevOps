package paol0b.azuredevops.services

import com.google.gson.JsonParser
import paol0b.azuredevops.model.*

/** Only a side known to be absent from the change type may be represented by empty text. */
object PullRequestDiffContents {
    fun load(
        change: PullRequestChange?,
        filePath: String,
        sourceCommit: String?,
        targetCommit: String?,
        fetch: (String, String) -> String
    ): Pair<String, String> {
        require(filePath.isNotBlank()) { "File path is missing." }
        fun requiredCommit(commit: String?, side: String) = commit?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("The pull request does not provide a $side commit for this diff.")
        val added = change?.isAddedFile() == true
        val deleted = change?.isRemovedFile() == true
        if (change != null && !added && !deleted && !change.hasChangeType("edit") && !change.hasChangeType("rename")) {
            throw IllegalStateException("Cannot load diff for unsupported change type: ${change.changeType}")
        }
        val oldPath = change?.previousPath()?.takeIf { it.isNotBlank() } ?: filePath
        val oldContent = if (added) "" else fetch(requiredCommit(targetCommit, "target"), oldPath)
        val newContent = if (deleted) "" else fetch(requiredCommit(sourceCommit, "source"), change?.effectivePath()?.takeIf { it.isNotBlank() } ?: filePath)
        return oldContent to newContent
    }

    fun decodeFileContent(response: String): String {
        val content = JsonParser.parseString(response).asJsonObject.get("content")
        check(content != null && content.isJsonPrimitive && content.asJsonPrimitive.isString) {
            "Azure DevOps did not return text content for this file."
        }
        return content.asString
    }
}
