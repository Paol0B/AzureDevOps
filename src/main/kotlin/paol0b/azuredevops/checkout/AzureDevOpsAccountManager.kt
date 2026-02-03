package paol0b.azuredevops.checkout

import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName

/**
 * Application-level service to manage Azure DevOps accounts and credentials.
 * Stores account metadata and tokens securely using IntelliJ's PasswordSafe.
 */
@Service(Service.Level.APP)
@State(
    name = "AzureDevOpsAccountsSettings",
    storages = [Storage("azureDevOpsAccounts.xml")]
)
class AzureDevOpsAccountManager : PersistentStateComponent<AzureDevOpsAccountManager.State> {

    private val logger = Logger.getInstance(AzureDevOpsAccountManager::class.java)
    private var myState = State()

    data class State(
        var accounts: MutableList<AccountData> = mutableListOf()
    )

    data class AccountData(
        var id: String = "",
        var serverUrl: String = "",
        var displayName: String = "",
        var expiresAt: Long = 0,  // Unix timestamp (millis) when token expires
        var lastRefreshed: Long = 0  // Unix timestamp (millis) of last token refresh
    )
    
    enum class AccountAuthState {
        VALID,       // Token is valid and not expired
        EXPIRED,     // Token has expired
        REVOKED,     // Token was revoked or invalid
        UNKNOWN      // State cannot be determined
    }

    companion object {
        fun getInstance(): AzureDevOpsAccountManager {
            return ApplicationManager.getApplication().getService(AzureDevOpsAccountManager::class.java)
        }

        private const val CREDENTIAL_SERVICE_NAME = "AzureDevOps"
    }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    /**
     * Get all Azure DevOps accounts
     */
    fun getAccounts(): List<AzureDevOpsAccount> {
        return myState.accounts.map { 
            AzureDevOpsAccount(
                id = it.id,
                serverUrl = it.serverUrl,
                displayName = it.displayName
            )
        }
    }

    /**
     * Add a new Azure DevOps account with credentials
     */
    fun addAccount(serverUrl: String, token: String, refreshToken: String? = null, expiresIn: Int? = null): AzureDevOpsAccount {
        val id = generateAccountId(serverUrl)
        val displayName = extractDisplayName(serverUrl)
        
        val now = System.currentTimeMillis()
        val expiresAt = if (expiresIn != null) {
            now + (expiresIn * 1000L)
        } else {
            0L  // Unknown expiry
        }

        // Store account metadata
        val accountData = AccountData(
            id = id,
            serverUrl = serverUrl,
            displayName = displayName,
            expiresAt = expiresAt,
            lastRefreshed = now
        )
        myState.accounts.add(accountData)

        // Store tokens securely
        saveToken(id, token)
        if (refreshToken != null) {
            saveRefreshToken(id, refreshToken)
        }

        logger.info("Added Azure DevOps account: $displayName (expires: ${if (expiresAt > 0) java.util.Date(expiresAt) else "unknown"})")

        return AzureDevOpsAccount(id, serverUrl, displayName)
    }

    /**
     * Remove an account
     */
    fun removeAccount(accountId: String) {
        myState.accounts.removeIf { it.id == accountId }
        
        // Remove token from PasswordSafe
        val credentialAttributes = createCredentialAttributes(accountId)
        PasswordSafe.instance.set(credentialAttributes, null)
        
        logger.info("Removed Azure DevOps account: $accountId")
    }

    /**
     * Get Personal Access Token for an account
     */
    fun getToken(accountId: String): String? {
        val credentialAttributes = createCredentialAttributes(accountId)
        return PasswordSafe.instance.getPassword(credentialAttributes)
    }
    
    /**
     * Get Refresh Token for an account
     */
    fun getRefreshToken(accountId: String): String? {
        val credentialAttributes = createCredentialAttributes(accountId, "refresh")
        return PasswordSafe.instance.getPassword(credentialAttributes)
    }

    /**
     * Update token for an existing account
     */
    fun updateToken(accountId: String, newToken: String, newRefreshToken: String? = null, expiresIn: Int? = null) {
        saveToken(accountId, newToken)
        if (newRefreshToken != null) {
            saveRefreshToken(accountId, newRefreshToken)
        }
        
        // Update expiry time
        val account = myState.accounts.find { it.id == accountId }
        if (account != null) {
            val now = System.currentTimeMillis()
            account.lastRefreshed = now
            if (expiresIn != null) {
                account.expiresAt = now + (expiresIn * 1000L)
            }
            
            // Update token in git credential helper for all open projects matching this account
            updateTokenInGitCredentials(account, newToken)
        }
    }
    
    /**
     * Updates the token in Git credential helper for all projects that use this account.
     * This ensures that git operations will use the refreshed token instead of the expired one.
     */
    private fun updateTokenInGitCredentials(account: AccountData, newToken: String) {
        try {
            // Get all open projects
            val projectManager = com.intellij.openapi.project.ProjectManager.getInstance()
            val openProjects = projectManager.openProjects
            
            if (openProjects.isEmpty()) {
                logger.debug("No open projects to update git credentials for account ${account.id}")
                return
            }
            
            val accountOrg = extractOrganizationFromUrl(account.serverUrl)
            if (accountOrg == null) {
                logger.warn("Could not extract organization from account server URL: ${account.serverUrl}")
                return
            }
            
            // Iterate through all open projects
            for (project in openProjects) {
                try {
                    // Check if this project uses Azure DevOps and matches this account's organization
                    val detector = paol0b.azuredevops.services.AzureDevOpsRepositoryDetector.getInstance(project)
                    val repoInfo = detector.detectAzureDevOpsInfo()
                    
                    if (repoInfo != null && repoInfo.organization.equals(accountOrg, ignoreCase = true)) {
                        // This project matches the account's organization
                        logger.info("Updating git credentials for project: ${project.name}, organization: ${repoInfo.organization}")
                        
                        val gitCredHelper = paol0b.azuredevops.services.GitCredentialHelperService.getInstance(project)
                        val gitService = paol0b.azuredevops.services.GitRepositoryService.getInstance(project)
                        
                        val remoteUrl = gitService.getRemoteUrl()
                        if (remoteUrl != null && gitCredHelper.isCredentialHelperAvailable()) {
                            // Update the token in git credential helper
                            val success = gitCredHelper.saveCredentialsToHelper(remoteUrl, "oauth", newToken)
                            if (success) {
                                logger.info("Successfully updated git credentials for project: ${project.name}")
                            } else {
                                logger.warn("Failed to update git credentials for project: ${project.name}")
                            }
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Error updating git credentials for project: ${project.name}", e)
                    // Continue with next project
                }
            }
        } catch (e: Exception) {
            logger.error("Error updating git credentials", e)
            // Don't fail the token update if git credential update fails
        }
    }
    
    /**
     * Extracts organization name from Azure DevOps URL
     * Supports cloud (dev.azure.com, visualstudio.com) and on-premise instances
     */
    private fun extractOrganizationFromUrl(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            
            // Special case: visualstudio.com uses subdomain format
            // https://{organization}.visualstudio.com
            if (uri.host?.endsWith(".visualstudio.com") == true) {
                return uri.host?.substringBefore(".visualstudio.com")
            }
            
            // Standard format (cloud and on-premise): https://{host}/{organization}
            // Examples: https://dev.azure.com/{organization}, https://devops.company.com/{organization}
            val path = uri.path.trim('/')
            return if (path.isNotEmpty()) {
                path.split("/").firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract organization from URL: $url", e)
            null
        }
    }
    
    /**
     * Get authentication state for an account
     */
    fun getAccountAuthState(accountId: String): AccountAuthState {
        val account = myState.accounts.find { it.id == accountId } ?: return AccountAuthState.UNKNOWN
        val token = getToken(accountId) ?: return AccountAuthState.REVOKED
        
        // Check if token is expired
        if (account.expiresAt > 0 && System.currentTimeMillis() > account.expiresAt) {
            return AccountAuthState.EXPIRED
        }
        
        return AccountAuthState.VALID
    }

    private fun saveToken(accountId: String, token: String) {
        val credentialAttributes = createCredentialAttributes(accountId)
        val credentials = Credentials("", token)
        PasswordSafe.instance.set(credentialAttributes, credentials)
    }
    
    private fun saveRefreshToken(accountId: String, refreshToken: String) {
        val credentialAttributes = createCredentialAttributes(accountId, "refresh")
        val credentials = Credentials("", refreshToken)
        PasswordSafe.instance.set(credentialAttributes, credentials)
    }

    private fun createCredentialAttributes(accountId: String, suffix: String = ""): CredentialAttributes {
        val key = if (suffix.isNotEmpty()) "$accountId-$suffix" else accountId
        return CredentialAttributes(
            generateServiceName(CREDENTIAL_SERVICE_NAME, key)
        )
    }

    private fun generateAccountId(serverUrl: String): String {
        // Create a stable ID from the server URL
        return serverUrl.hashCode().toString()
    }

    private fun extractDisplayName(serverUrl: String): String {
        // Extract organization name from URL
        // https://dev.azure.com/{org} -> {org}
        return try {
            val uri = java.net.URI(serverUrl)
            val path = uri.path.trim('/')
            if (path.isNotEmpty()) {
                path.split("/").firstOrNull() ?: uri.host
            } else {
                uri.host
            }
        } catch (e: Exception) {
            serverUrl
        }
    }
}
