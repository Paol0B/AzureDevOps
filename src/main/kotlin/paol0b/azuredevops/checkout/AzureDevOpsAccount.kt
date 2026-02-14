package paol0b.azuredevops.checkout

/**
 * Type of authentication used for an Azure DevOps account.
 */
enum class AuthType {
    OAUTH,
    PAT
}

/**
 * Represents an Azure DevOps account/connection stored in the IDE
 */
data class AzureDevOpsAccount(
    val id: String,
    val serverUrl: String,
    val displayName: String,
    val authType: AuthType = AuthType.OAUTH,
    val selfHosted: Boolean = false
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
