package paol0b.azuredevops.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.AzureDevOpsConfig
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.AzureDevOpsConfigService
import paol0b.azuredevops.services.AzureDevOpsRepositoryDetector
import paol0b.azuredevops.services.GitCredentialHelperService
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

/**
 * Pannello di configurazione per Azure DevOps nelle impostazioni del progetto
 * Supporta modalità automatica e manuale con selezione esplicita
 */
class AzureDevOpsConfigurable(private val project: Project) : Configurable {

    private var mainPanel: JPanel? = null
    private val organizationField = JBTextField()
    private val projectField = JBTextField()
    private val repositoryField = JBTextField()
    private val patField = JBPasswordField()
    private val testConnectionButton = JButton("Test Connection")
    private val loadFromGitButton = JButton("Load from Git Credentials")
    
    // Radio buttons per selezione modalità
    private val autoModeRadio = JRadioButton("Auto-detect from Git", true)
    private val manualModeRadio = JRadioButton("Manual Configuration", false)
    private val modeGroup = ButtonGroup()
    
    private var currentMode: ConfigMode = ConfigMode.AUTO

    enum class ConfigMode {
        AUTO, MANUAL
    }

    override fun getDisplayName(): String = "Azure DevOps"

    override fun createComponent(): JComponent {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()
        
        // Rileva le informazioni del repository
        val detector = AzureDevOpsRepositoryDetector.getInstance(project)
        val detectedInfo = detector.detectAzureDevOpsInfo()
        val isAutoDetected = detector.isAzureDevOpsRepository()

        // Determina modalità iniziale
        currentMode = if (isAutoDetected && detectedInfo != null) {
            ConfigMode.AUTO
        } else {
            ConfigMode.MANUAL
        }

        // Setup radio buttons
        modeGroup.add(autoModeRadio)
        modeGroup.add(manualModeRadio)
        
        autoModeRadio.isSelected = (currentMode == ConfigMode.AUTO)
        manualModeRadio.isSelected = (currentMode == ConfigMode.MANUAL)
        
        // Aggiungi listener per cambio modalità
        autoModeRadio.addActionListener { 
            currentMode = ConfigMode.AUTO
            updateFieldsEditability()
        }
        manualModeRadio.addActionListener { 
            currentMode = ConfigMode.MANUAL
            updateFieldsEditability()
        }

        // Carica i valori correnti
        organizationField.text = config.organization
        projectField.text = config.project
        repositoryField.text = config.repository
        patField.text = config.personalAccessToken

        // Setup bottoni
        testConnectionButton.addActionListener {
            testConnection()
        }
        
        loadFromGitButton.addActionListener {
            loadCredentialsFromGit()
        }

        // Crea il pannello principale
        val formBuilder = FormBuilder.createFormBuilder()
        
        // Sezione: Modalità di configurazione
        formBuilder.addLabeledComponent(
            JBLabel("<html><b>Configuration Mode:</b></html>"),
            createModeSelectionPanel()
        )
        formBuilder.addSeparator()
        
        // Mostra info in base alla modalità
        if (currentMode == ConfigMode.AUTO) {
            if (detectedInfo != null) {
                val infoLabel = JBLabel("<html><b style='color: green;'>✓ Repository Auto-detected</b><br>" +
                    "<font color='gray' size='-1'>Organization: ${detectedInfo.organization}<br>" +
                    "Project: ${detectedInfo.project}<br>" +
                    "Repository: ${detectedInfo.repository}</font></html>")
                formBuilder.addComponent(infoLabel, 1)
            } else {
                val warningLabel = JBLabel("<html><b style='color: orange;'>⚠ Unable to Auto-detect</b><br>" +
                    "<font color='gray' size='-1'>Switch to Manual Configuration or check your Git remote URL</font></html>")
                formBuilder.addComponent(warningLabel, 1)
            }
        } else {
            val manualLabel = JBLabel("<html><b style='color: #6897BB;'>ℹ Manual Configuration Mode</b><br>" +
                "<font color='gray' size='-1'>Enter your Azure DevOps details manually</font></html>")
            formBuilder.addComponent(manualLabel, 1)
        }
        
        formBuilder.addSeparator()
        
        // Campi repository
        formBuilder.addLabeledComponent(JBLabel("Organization:"), organizationField, 1, false)
        formBuilder.addLabeledComponent(JBLabel("Project:"), projectField, 1, false)
        formBuilder.addLabeledComponent(JBLabel("Repository:"), repositoryField, 1, false)
        formBuilder.addSeparator()
        
        // PAT field
        formBuilder
            .addLabeledComponent(JBLabel("Personal Access Token (PAT):"), patField, 1, false)
            .addTooltip("Token with 'Code (Read & Write)' and 'Pull Request (Read & Write)' permissions")
        
        // Pannello pulsanti
        val buttonPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        buttonPanel.add(loadFromGitButton)
        buttonPanel.add(testConnectionButton)
        
        formBuilder
            .addComponentToRightColumn(buttonPanel, 1)
            .addComponentFillVertically(JPanel(), 0)

        val formPanel = formBuilder.panel

        mainPanel = JPanel(BorderLayout()).apply {
            add(formPanel, BorderLayout.NORTH)
        }
        
        // Imposta editabilità iniziale
        updateFieldsEditability()

        return mainPanel!!
    }
    
    /**
     * Crea il pannello per la selezione della modalità
     */
    private fun createModeSelectionPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 15, 0))
        panel.add(autoModeRadio)
        panel.add(manualModeRadio)
        return panel
    }
    
    /**
     * Aggiorna l'editabilità dei campi in base alla modalità selezionata
     */
    private fun updateFieldsEditability() {
        val isManual = (currentMode == ConfigMode.MANUAL)
        organizationField.isEditable = isManual
        projectField.isEditable = isManual
        repositoryField.isEditable = isManual
        
        // Se si passa a modalità AUTO, ricarica i valori rilevati
        if (!isManual) {
            val configService = AzureDevOpsConfigService.getInstance(project)
            val config = configService.getConfig()
            organizationField.text = config.organization
            projectField.text = config.project
            repositoryField.text = config.repository
        }
    }

    override fun isModified(): Boolean {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        val patChanged = String(patField.password) != config.personalAccessToken
        
        if (currentMode == ConfigMode.MANUAL) {
            return patChanged ||
                    organizationField.text != config.organization ||
                    projectField.text != config.project ||
                    repositoryField.text != config.repository
        }
        
        return patChanged
    }

    override fun apply() {
        val configService = AzureDevOpsConfigService.getInstance(project)
        
        if (currentMode == ConfigMode.MANUAL) {
            // Salva configurazione manuale completa
            val newConfig = AzureDevOpsConfig(
                organization = organizationField.text.trim(),
                project = projectField.text.trim(),
                repository = repositoryField.text.trim(),
                personalAccessToken = String(patField.password).trim()
            )
            configService.saveConfig(newConfig)
        } else {
            // Salva solo PAT (auto-detection attivo)
            configService.savePersonalAccessTokenOnly(String(patField.password).trim())
        }
    }

    override fun reset() {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        organizationField.text = config.organization
        projectField.text = config.project
        repositoryField.text = config.repository
        patField.text = config.personalAccessToken
        
        // Ripristina la modalità in base alla rilevazione
        val detector = AzureDevOpsRepositoryDetector.getInstance(project)
        val isAutoDetected = detector.isAzureDevOpsRepository()
        
        currentMode = if (isAutoDetected) ConfigMode.AUTO else ConfigMode.MANUAL
        autoModeRadio.isSelected = (currentMode == ConfigMode.AUTO)
        manualModeRadio.isSelected = (currentMode == ConfigMode.MANUAL)
        
        updateFieldsEditability()
    }

    /**
     * Tenta di caricare le credenziali dal Git Credential Helper
     */
    private fun loadCredentialsFromGit() {
        val gitCredHelper = GitCredentialHelperService.getInstance(project)
        
        // Verifica se il credential helper è disponibile
        if (!gitCredHelper.isCredentialHelperAvailable()) {
            Messages.showWarningDialog(
                project,
                "Git Credential Helper non disponibile.\n\n" +
                        "Assicurati di aver configurato Git correttamente e di avere salvato le credenziali.\n" +
                        "Esempio: git config --global credential.helper manager",
                "Credential Helper Non Disponibile"
            )
            return
        }
        
        // Tenta di recuperare le credenziali
        val token = gitCredHelper.getCredentialsForCurrentRepository()
        
        if (token != null && token.isNotBlank()) {
            patField.text = token
            Messages.showMessageDialog(
                project,
                "Personal Access Token caricato con successo dal Git Credential Helper!\n\n" +
                        "Token trovato e inserito nel campo PAT.\n" +
                        "Clicca 'Test Connection' per verificare che funzioni.",
                "Credenziali Caricate",
                Messages.getInformationIcon()
            )
        } else {
            Messages.showMessageDialog(
                project,
                "Nessuna credenziale trovata nel Git Credential Helper.\n\n" +
                        "Possibili cause:\n" +
                        "• Non hai mai salvato credenziali per questo repository\n" +
                        "• Il credential helper non è configurato\n" +
                        "• Le credenziali sono salvate con un URL diverso\n\n" +
                        "Suggerimento: esegui un 'git fetch' o 'git pull' e inserisci il PAT quando richiesto,\n" +
                        "poi ritorna qui e riprova.",
                "Credenziali Non Trovate",
                Messages.getInformationIcon()
            )
        }
    }

    private fun testConnection() {
        val configService = AzureDevOpsConfigService.getInstance(project)
        
        val pat = String(patField.password).trim()
        val org = organizationField.text.trim()
        val proj = projectField.text.trim()
        val repo = repositoryField.text.trim()

        // Validazione campi
        if (pat.isBlank()) {
            Messages.showErrorDialog(
                project,
                "Inserisci il Personal Access Token prima di testare la connessione.",
                "PAT Mancante"
            )
            return
        }

        if (currentMode == ConfigMode.MANUAL && (org.isBlank() || proj.isBlank() || repo.isBlank())) {
            Messages.showErrorDialog(
                project,
                "Compila Organization, Project e Repository prima di testare la connessione.",
                "Configurazione Incompleta"
            )
            return
        }

        // Crea il config per il test (senza salvare ancora)
        val testConfig = if (currentMode == ConfigMode.MANUAL) {
            AzureDevOpsConfig(
                organization = org,
                project = proj,
                repository = repo,
                personalAccessToken = pat
            )
        } else {
            // In modalità auto, usa i valori rilevati
            val detector = AzureDevOpsRepositoryDetector.getInstance(project)
            val repoInfo = detector.detectAzureDevOpsInfo()
            
            if (repoInfo == null) {
                Messages.showErrorDialog(
                    project,
                    "Impossibile rilevare automaticamente il repository Azure DevOps.\n" +
                            "Assicurati che il progetto sia clonato da Azure DevOps.",
                    "Repository Non Rilevato"
                )
                return
            }
            
            AzureDevOpsConfig(
                organization = repoInfo.organization,
                project = repoInfo.project,
                repository = repoInfo.repository,
                personalAccessToken = pat
            )
        }

        // Verifica che il config sia valido
        if (!testConfig.isValid()) {
            Messages.showErrorDialog(
                project,
                "Configurazione non valida. Assicurati che tutti i campi siano compilati.",
                "Configurazione Incompleta"
            )
            return
        }

        try {
            // Testa la connessione direttamente con il config di test
            val apiClient = AzureDevOpsApiClient.getInstance(project)
            val url = "https://dev.azure.com/${testConfig.organization}/${testConfig.project}/_apis/git/repositories/${testConfig.repository}?api-version=7.0"
            
            // Usa executeGet direttamente con i parametri di test
            testConnectionDirectly(url, testConfig.personalAccessToken)
            
            Messages.showInfoMessage(
                project,
                "Connessione ad Azure DevOps riuscita!\n\n" +
                        "Organization: ${testConfig.organization}\n" +
                        "Project: ${testConfig.project}\n" +
                        "Repository: ${testConfig.repository}",
                "Test Connessione Riuscito"
            )
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "Test connessione fallito:\n\n${e.message}",
                "Errore Connessione"
            )
        }
    }
    
    private fun testConnectionDirectly(url: String, token: String) {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        
        try {
            connection.requestMethod = "GET"
            val credentials = ":$token"
            val encodedCredentials = java.util.Base64.getEncoder().encodeToString(credentials.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            connection.setRequestProperty("Authorization", "Basic $encodedCredentials")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            
            if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw Exception(when (responseCode) {
                    401 -> "Autenticazione fallita. Verifica il Personal Access Token (401)"
                    403 -> "Permessi insufficienti. Verifica i permessi del PAT (403)"
                    404 -> "Repository non trovato. Verifica Organization, Project e Repository (404)"
                    else -> "Errore HTTP $responseCode: $errorBody"
                })
            }
        } finally {
            connection.disconnect()
        }
    }

    override fun disposeUIResources() {
        mainPanel = null
    }
}
