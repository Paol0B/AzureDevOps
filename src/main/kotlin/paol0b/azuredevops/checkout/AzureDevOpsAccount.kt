package paol0b.azuredevops.checkout

/**
 * Represents an Azure DevOps account/connection stored in the IDE
 */
data class AzureDevOpsAccount(
    val id: String,
    val serverUrl: String,
    val displayName: String
) {
    override fun toString(): String = displayName
}

/**
 * Represents an Azure DevOps repository in the clone dialog
 */
data class AzureDevOpsRepository(
    val id: String,
    val name: String,
    val projectName: String,
    val remoteUrl: String,
    val webUrl: String
)
