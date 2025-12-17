package paol0b.azuredevops.services

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.xmlb.XmlSerializerUtil
import paol0b.azuredevops.checkout.AzureDevOpsAccountManager
import paol0b.azuredevops.checkout.AzureDevOpsOAuthService
import paol0b.azuredevops.model.AzureDevOpsConfig
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Service to manage Azure DevOps configuration.
 * Automatically detects organization, project, and repository from the remote Git URL.
 * Only saves the PAT securely using PasswordSafe.
 */
@Service(Service.Level.PROJECT)
@State(
    name = "AzureDevOpsConfig",
    storages = [Storage("azureDevOpsConfig.xml")]
)
class AzureDevOpsConfigService(private val project: com.intellij.openapi.project.Project) : PersistentStateComponent<AzureDevOpsConfigService.State> {

    private var myState = State()
    private val logger = Logger.getInstance(AzureDevOpsConfigService::class.java)

    companion object {
        private const val CREDENTIAL_KEY = "paol0b.azuredevops.pat"
        
        fun getInstance(project: com.intellij.openapi.project.Project): AzureDevOpsConfigService {
            return project.service()
        }
    }

    data class State(
        // Keep these fields for backward compatibility and optional manual override
        var manualOrganization: String = "",
        var manualProject: String = "",
        var manualRepository: String = ""
    )

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    /**
     * Gets the complete configuration including PAT from secure storage
     * and auto-detected info from the Git repository
     */
    fun getConfig(): AzureDevOpsConfig {
        val pat = getPersonalAccessToken()
        val detector = AzureDevOpsRepositoryDetector.getInstance(project)
        val repoInfo = detector.detectAzureDevOpsInfo()
        
        // Use auto-detected values, or manual ones if set (override)
        val organization = myState.manualOrganization.ifBlank { repoInfo?.organization ?: "" }
        val project = myState.manualProject.ifBlank { repoInfo?.project ?: "" }
        val repository = myState.manualRepository.ifBlank { repoInfo?.repository ?: "" }
        
        return AzureDevOpsConfig.create(
            organization = organization,
            project = project,
            repository = repository,
            personalAccessToken = pat
        )
    }

    /**
     * Saves the complete configuration (optional for manual override)
     */
    fun saveConfig(config: AzureDevOpsConfig) {
        myState.manualOrganization = config.organization
        myState.manualProject = config.project
        myState.manualRepository = config.repository
        savePersonalAccessToken(config.personalAccessToken)
    }
    
    /**
     * Saves only the PAT (recommended method)
     */
    fun savePersonalAccessTokenOnly(token: String) {
        savePersonalAccessToken(token)
    }

    /**
     * Gets the Personal Access Token from secure storage
     * Priority:
     * 1. Check if repository matches an OAuth account (global credentials)
     * 2. IDE's PasswordSafe (per-project PAT)
     * 3. Git Credential Helper
     */
    private fun getPersonalAccessToken(): String {
        // First, try to match with global OAuth accounts
        val oauthToken = tryGetTokenFromOAuthAccount()
        if (oauthToken != null) {
            return oauthToken
        }
        
        // Second, try from IDE's PasswordSafe (per-project PAT)
        val credentialAttributes = createCredentialAttributes()
        val credentials = PasswordSafe.instance.get(credentialAttributes)
        val savedToken = credentials?.getPasswordAsString() ?: ""
        
        if (savedToken.isNotBlank()) {
            return savedToken
        }
        
        // Third, try to retrieve it from Git Credential Helper
        return tryGetTokenFromGitCredentialHelper() ?: ""
    }
    
    /**
     * Tries to match the current repository with a global OAuth account
     * and retrieve the OAuth token if a match is found.
     * Automatically refreshes expired tokens if a refresh token is available.
     */
    private fun tryGetTokenFromOAuthAccount(): String? {
        try {
            val detector = AzureDevOpsRepositoryDetector.getInstance(project)
            val repoInfo = detector.detectAzureDevOpsInfo() ?: return null
            
            // Get the organization from the repository
            val organization = repoInfo.organization
            if (organization.isBlank()) return null
            
            // Check all OAuth accounts for a matching organization
            val accountManager = AzureDevOpsAccountManager.getInstance()
            val accounts = accountManager.getAccounts()
            
            for (account in accounts) {
                // Extract organization from account server URL
                val accountOrg = extractOrganizationFromUrl(account.serverUrl)
                if (accountOrg.equals(organization, ignoreCase = true)) {
                    // Found matching account
                    val authState = accountManager.getAccountAuthState(account.id)
                    
                    // If token is expired, try to refresh it
                    if (authState == AzureDevOpsAccountManager.AccountAuthState.EXPIRED) {
                        logger.info("Token expired for account ${account.id}, attempting automatic refresh")
                        val refreshToken = accountManager.getRefreshToken(account.id)
                        if (refreshToken != null) {
                            // Try to refresh the token
                            val oauthService = AzureDevOpsOAuthService.getInstance()
                            val result = oauthService.refreshAccessToken(refreshToken, account.serverUrl)
                            if (result != null) {
                                logger.info("Successfully refreshed token for account ${account.id}")
                                // Update the account with new tokens
                                accountManager.updateToken(
                                    account.id,
                                    result.accessToken,
                                    result.refreshToken,
                                    result.expiresIn
                                )
                                return result.accessToken
                            } else {
                                logger.warn("Failed to refresh token for account ${account.id}")
                            }
                        } else {
                            logger.warn("No refresh token available for account ${account.id}")
                        }
                    }
                    
                    // Return the token (valid or after refresh)
                    return accountManager.getToken(account.id)
                }
            }
        } catch (e: Exception) {
            // Log but don't fail
            return null
        }
        
        return null
    }
    
    /**
     * Extracts organization name from Azure DevOps URL
     */
    private fun extractOrganizationFromUrl(url: String): String? {
        return try {
            val uri = java.net.URI(url)
            val path = uri.path.trim('/')
            if (path.isNotEmpty()) {
                path.split("/").firstOrNull()
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Attempts to retrieve the PAT from Git Credential Helper
     */
    private fun tryGetTokenFromGitCredentialHelper(): String? {
        return try {
            val gitCredHelper = GitCredentialHelperService.getInstance(project)
            
            // Check if credential helper is available
            if (!gitCredHelper.isCredentialHelperAvailable()) {
                return null
            }
            
            // Try to retrieve credentials
            val token = gitCredHelper.getCredentialsForCurrentRepository()
            
            if (token != null) {
                // Save the token in PasswordSafe for future use
                savePersonalAccessToken(token)
            }
            
            token
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Saves the Personal Access Token in secure storage
     * Optionally also saves it in Git Credential Helper
     */
    private fun savePersonalAccessToken(token: String) {
        // Save in IDE's PasswordSafe
        val credentialAttributes = createCredentialAttributes()
        val credentials = Credentials("azure-devops", token)
        PasswordSafe.instance.set(credentialAttributes, credentials)
        
        // Try to also save in Git Credential Helper
        trySaveTokenToGitCredentialHelper(token)
    }
    
    /**
     * Attempts to also save the PAT in Git Credential Helper
     * for synchronization with Git CLI
     */
    private fun trySaveTokenToGitCredentialHelper(token: String) {
        try {
            val gitService = GitRepositoryService.getInstance(project)
            val remoteUrl = gitService.getRemoteUrl() ?: return
            
            val gitCredHelper = GitCredentialHelperService.getInstance(project)
            if (gitCredHelper.isCredentialHelperAvailable()) {
                gitCredHelper.saveCredentialsToHelper(remoteUrl, "", token)
            }
        } catch (e: Exception) {
            // Ignore errors, not critical
        }
    }

    /**
     * Clears the Personal Access Token from secure storage
     */
    fun clearPersonalAccessToken() {
        val credentialAttributes = createCredentialAttributes()
        PasswordSafe.instance.set(credentialAttributes, null)
    }

    /**
     * Checks if the configuration is valid
     */
    fun isConfigured(): Boolean = getConfig().isValid()

    private fun createCredentialAttributes(): CredentialAttributes {
        return CredentialAttributes(
            generateServiceName("AzureDevOps", CREDENTIAL_KEY)
        )
    }

    /**
     * Gets the base URL for Azure DevOps APIs
     */
    fun getApiBaseUrl(): String {
        val config = getConfig()
        val encodedOrganization = URLEncoder.encode(config.organization, StandardCharsets.UTF_8.toString())
        return "https://dev.azure.com/$encodedOrganization"
    }
    
    /**
     * Checks if the repository is Azure DevOps OR if there is a valid manual configuration
     */
    fun isAzureDevOpsRepository(): Boolean {
        val detector = AzureDevOpsRepositoryDetector.getInstance(project)
        val autoDetected = detector.isAzureDevOpsRepository()
        
        // If not auto-detected, check if there is a valid manual config
        if (!autoDetected) {
            return myState.manualOrganization.isNotBlank() &&
                   myState.manualProject.isNotBlank() &&
                   myState.manualRepository.isNotBlank()
        }
        
        return true
    }
    
    /**
     * Gets the description of the detected repository
     */
    fun getDetectedRepositoryInfo(): String? {
        val detector = AzureDevOpsRepositoryDetector.getInstance(project)
        return detector.getRepositoryDescription()
    }
}
