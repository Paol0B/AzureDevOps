package paol0b.azuredevops.services

import com.intellij.credentialStore.CredentialAttributes
import com.intellij.credentialStore.Credentials
import com.intellij.credentialStore.generateServiceName
import com.intellij.ide.passwordSafe.PasswordSafe
import com.intellij.openapi.components.*
import com.intellij.util.xmlb.XmlSerializerUtil
import paol0b.azuredevops.model.AzureDevOpsConfig

/**
 * Servizio per gestire la configurazione di Azure DevOps.
 * Rileva automaticamente organization, project e repository dall'URL Git remoto.
 * Salva solo il PAT in modo sicuro usando PasswordSafe.
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
        // Manteniamo questi campi per retrocompatibilità e override manuale (opzionale)
        var manualOrganization: String = "",
        var manualProject: String = "",
        var manualRepository: String = ""
    )

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    /**
     * Ottiene la configurazione completa includendo il PAT dal secure storage
     * e le informazioni auto-rilevate dal repository Git
     */
    fun getConfig(): AzureDevOpsConfig {
        val pat = getPersonalAccessToken()
        val detector = AzureDevOpsRepositoryDetector.getInstance(project)
        val repoInfo = detector.detectAzureDevOpsInfo()
        
        // Usa i valori auto-rilevati, oppure quelli manuali se impostati (override)
        val organization = myState.manualOrganization.ifBlank { repoInfo?.organization ?: "" }
        val project = myState.manualProject.ifBlank { repoInfo?.project ?: "" }
        val repository = myState.manualRepository.ifBlank { repoInfo?.repository ?: "" }
        
        return AzureDevOpsConfig(
            organization = organization,
            project = project,
            repository = repository,
            personalAccessToken = pat
        )
    }

    /**
     * Salva la configurazione completa (opzionale per override manuale)
     */
    fun saveConfig(config: AzureDevOpsConfig) {
        myState.manualOrganization = config.organization
        myState.manualProject = config.project
        myState.manualRepository = config.repository
        savePersonalAccessToken(config.personalAccessToken)
    }
    
    /**
     * Salva solo il PAT (metodo consigliato)
     */
    fun savePersonalAccessTokenOnly(token: String) {
        savePersonalAccessToken(token)
    }

    /**
     * Ottiene il Personal Access Token dal secure storage
     * Se non trovato, tenta di recuperarlo dal Git Credential Helper
     */
    private fun getPersonalAccessToken(): String {
        // Prima prova dal PasswordSafe dell'IDE
        val credentialAttributes = createCredentialAttributes()
        val credentials = PasswordSafe.instance.get(credentialAttributes)
        val savedToken = credentials?.getPasswordAsString() ?: ""
        
        if (savedToken.isNotBlank()) {
            return savedToken
        }
        
        // Se non trovato, tenta di recuperarlo dal Git Credential Helper
        return tryGetTokenFromGitCredentialHelper() ?: ""
    }
    
    /**
     * Tenta di recuperare il PAT dal Git Credential Helper
     */
    private fun tryGetTokenFromGitCredentialHelper(): String? {
        return try {
            val gitCredHelper = GitCredentialHelperService.getInstance(project)
            
            // Verifica se il credential helper è disponibile
            if (!gitCredHelper.isCredentialHelperAvailable()) {
                return null
            }
            
            // Tenta di recuperare le credenziali
            val token = gitCredHelper.getCredentialsForCurrentRepository()
            
            if (token != null) {
                // Salva il token nel PasswordSafe per uso futuro
                savePersonalAccessToken(token)
            }
            
            token
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Salva il Personal Access Token nel secure storage
     * Opzionalmente salva anche nel Git Credential Helper
     */
    private fun savePersonalAccessToken(token: String) {
        // Salva nel PasswordSafe dell'IDE
        val credentialAttributes = createCredentialAttributes()
        val credentials = Credentials("azure-devops", token)
        PasswordSafe.instance.set(credentialAttributes, credentials)
        
        // Tenta di salvare anche nel Git Credential Helper
        trySaveTokenToGitCredentialHelper(token)
    }
    
    /**
     * Tenta di salvare il PAT anche nel Git Credential Helper
     * per sincronizzazione con Git CLI
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
            // Ignora errori, non è critico
        }
    }

    /**
     * Pulisce il Personal Access Token dal secure storage
     */
    fun clearPersonalAccessToken() {
        val credentialAttributes = createCredentialAttributes()
        PasswordSafe.instance.set(credentialAttributes, null)
    }

    /**
     * Verifica se la configurazione è valida
     */
    fun isConfigured(): Boolean = getConfig().isValid()

    private fun createCredentialAttributes(): CredentialAttributes {
        return CredentialAttributes(
            generateServiceName("AzureDevOps", CREDENTIAL_KEY)
        )
    }

    /**
     * Ottiene l'URL base per le API di Azure DevOps
     */
    fun getApiBaseUrl(): String {
        val config = getConfig()
        return "https://dev.azure.com/${config.organization}"
    }
    
    /**
     * Verifica se il repository è di Azure DevOps O se c'è una configurazione manuale valida
     */
    fun isAzureDevOpsRepository(): Boolean {
        val detector = AzureDevOpsRepositoryDetector.getInstance(project)
        val autoDetected = detector.isAzureDevOpsRepository()
        
        // Se non auto-rilevato, controlla se c'è una config manuale valida
        if (!autoDetected) {
            return myState.manualOrganization.isNotBlank() &&
                   myState.manualProject.isNotBlank() &&
                   myState.manualRepository.isNotBlank()
        }
        
        return true
    }
    
    /**
     * Ottiene la descrizione del repository rilevato
     */
    fun getDetectedRepositoryInfo(): String? {
        val detector = AzureDevOpsRepositoryDetector.getInstance(project)
        return detector.getRepositoryDescription()
    }
}
