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
        var displayName: String = ""
    )

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
    fun addAccount(serverUrl: String, token: String): AzureDevOpsAccount {
        val id = generateAccountId(serverUrl)
        val displayName = extractDisplayName(serverUrl)

        // Store account metadata
        val accountData = AccountData(
            id = id,
            serverUrl = serverUrl,
            displayName = displayName
        )
        myState.accounts.add(accountData)

        // Store token securely
        saveToken(id, token)

        logger.info("Added Azure DevOps account: $displayName")

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
     * Update token for an existing account
     */
    fun updateToken(accountId: String, newToken: String) {
        saveToken(accountId, newToken)
    }

    private fun saveToken(accountId: String, token: String) {
        val credentialAttributes = createCredentialAttributes(accountId)
        val credentials = Credentials("", token)
        PasswordSafe.instance.set(credentialAttributes, credentials)
    }

    private fun createCredentialAttributes(accountId: String): CredentialAttributes {
        return CredentialAttributes(
            generateServiceName(CREDENTIAL_SERVICE_NAME, accountId)
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
