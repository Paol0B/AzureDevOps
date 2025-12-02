package paol0b.azuredevops.services

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil
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
     * If not found, tries to retrieve it from the Git Credential Helper
     */
    private fun getPersonalAccessToken(): String {
        // First try from IDE's PasswordSafe
        val credentialAttributes = createCredentialAttributes()
        val credentials = PasswordSafe.instance.get(credentialAttributes)
        val savedToken = credentials?.getPasswordAsString() ?: ""
        
        if (savedToken.isNotBlank()) {
            return savedToken
        }
        
        // If not found, try to retrieve it from Git Credential Helper
        return tryGetTokenFromGitCredentialHelper() ?: ""
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
