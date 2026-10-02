package paol0b.azuredevops.services

import com.google.gson.JsonParser
import paol0b.azuredevops.model.PullRequestChange
import paol0b.azuredevops.model.effectivePath

/** Format used by the Azure DevOps PR website's UtilViewed and UtilVisit modules. */
data class ReviewedFilesState(private val hashes: Map<String, Int> = emptyMap()) {
    fun isReviewed(change: PullRequestChange): Boolean = hashes[hash(change)] == 2

    companion object {
        const val PROVIDER = "ms.vss-code-web.pr-detail-visit-data-provider"

        fun hash(change: PullRequestChange): String =
            "1@${change.item?.objectId?.takeIf { it.isNotEmpty() }?.take(8) ?: "00000000"}@${change.effectivePath()}"

        fun query(repositoryId: String, pullRequestId: Int, change: PullRequestChange? = null,
                  reviewed: Boolean? = null): Map<String, Any> {
            require(repositoryId.isNotBlank() && pullRequestId > 0)
            require((change == null) == (reviewed == null))
            val properties = mutableMapOf<String, Any>("repositoryId" to repositoryId, "pullRequestId" to pullRequestId)
            if (change != null && reviewed != null) {
                require(change.effectivePath().isNotBlank())
                properties["modifyHashes"] = listOf(hash(change))
                properties["modifyViewedStatus"] = if (reviewed) 2 else 1
            }
            return mapOf("contributionIds" to listOf(PROVIDER), "dataProviderContext" to mapOf("properties" to properties))
        }

        fun decode(response: String): ReviewedFilesState {
            try {
                val root = JsonParser.parseString(response).asJsonObject
                val exception = root.getAsJsonObject("dataProviderExceptions")?.get(PROVIDER)
                if (exception != null && !exception.isJsonNull) error("Azure DevOps review status provider failed")
                val provider = root.getAsJsonObject("dataProviders")?.getAsJsonObject(PROVIDER)
                    ?: error("Azure DevOps did not return reviewed-file status")
                val visit = provider.get("visit")
                if (visit == null || visit.isJsonNull) return ReviewedFilesState()
                val serialized = visit.asJsonObject.get("viewedState")
                if (serialized == null || serialized.isJsonNull || serialized.asString.isEmpty()) return ReviewedFilesState()
                val hashes = JsonParser.parseString(serialized.asString).asJsonObject.get("hashes")
                    ?: error("Azure DevOps returned invalid reviewed-file status")
                if (hashes.isJsonArray && hashes.asJsonArray.isEmpty) return ReviewedFilesState()
                return ReviewedFilesState(hashes.asJsonObject.entrySet().associate { (key, value) -> key to value.asInt })
            } catch (e: Exception) {
                throw IllegalStateException("Could not read Azure DevOps reviewed-file status", e)
            }
        }
    }
}
