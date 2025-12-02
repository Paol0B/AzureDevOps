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
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.swing.*

/**
 * Configuration panel for Azure DevOps in project settings
 * Supports automatic and manual mode with explicit selection
 */
class AzureDevOpsConfigurable(private val project: Project) : Configurable {

    private var mainPanel: JPanel? = null
    private val organizationField = JBTextField()
    private val projectField = JBTextField()
    private val repositoryField = JBTextField()
    private val patField = JBPasswordField()
    private val testConnectionButton = JButton("Test Connection")
    private val loadFromGitButton = JButton("Load from Git Credentials")
    
    // Radio buttons for mode selection
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
        
        // Detect repository info
        val detector = AzureDevOpsRepositoryDetector.getInstance(project)
        val detectedInfo = detector.detectAzureDevOpsInfo()
        val isAutoDetected = detector.isAzureDevOpsRepository()

        // Determine initial mode
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
        
        // Add listeners for mode change
        autoModeRadio.addActionListener {
            currentMode = ConfigMode.AUTO
            updateFieldsEditability()
        }
        manualModeRadio.addActionListener { 
            currentMode = ConfigMode.MANUAL
            updateFieldsEditability()
        }

        // Load current values
        organizationField.text = config.organization
        projectField.text = config.project
        repositoryField.text = config.repository
        patField.text = config.personalAccessToken

        // Setup buttons
        testConnectionButton.addActionListener {
            testConnection()
        }
        
        loadFromGitButton.addActionListener {
            loadCredentialsFromGit()
        }

        // Create the main panel
        val formBuilder = FormBuilder.createFormBuilder()
        
        // Section: Configuration mode
        formBuilder.addLabeledComponent(
            JBLabel("<html><b>Configuration Mode:</b></html>"),
            createModeSelectionPanel()
        )
        formBuilder.addSeparator()
        
        // Show info based on mode
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
        
        // Repository fields
        formBuilder.addLabeledComponent(JBLabel("Organization:"), organizationField, 1, false)
        formBuilder.addLabeledComponent(JBLabel("Project:"), projectField, 1, false)
        formBuilder.addLabeledComponent(JBLabel("Repository:"), repositoryField, 1, false)
        formBuilder.addSeparator()
        
        // PAT field
        formBuilder
            .addLabeledComponent(JBLabel("Personal Access Token (PAT):"), patField, 1, false)
            .addTooltip("Token with 'Code (Read & Write)' and 'Pull Request (Read & Write)' permissions")
        
        // Button panel
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
        
        // Set initial editability
        updateFieldsEditability()

        return mainPanel!!
    }
    
    /**
     * Creates the panel for mode selection
     */
    private fun createModeSelectionPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 15, 0))
        panel.add(autoModeRadio)
        panel.add(manualModeRadio)
        return panel
    }
    
    /**
     * Updates the editability of fields based on the selected mode
     */
    private fun updateFieldsEditability() {
        val isManual = (currentMode == ConfigMode.MANUAL)
        organizationField.isEditable = isManual
        projectField.isEditable = isManual
        repositoryField.isEditable = isManual
        
        // If switching to AUTO mode, reload detected values
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
            // Save complete manual configuration
            val newConfig = AzureDevOpsConfig.create(
                organization = organizationField.text.trim(),
                project = projectField.text.trim(),
                repository = repositoryField.text.trim(),
                personalAccessToken = String(patField.password).trim()
            )
            configService.saveConfig(newConfig)
        } else {
            // Save only PAT (auto-detection active)
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
        
        // Restore mode based on detection
        val detector = AzureDevOpsRepositoryDetector.getInstance(project)
        val isAutoDetected = detector.isAzureDevOpsRepository()
        
        currentMode = if (isAutoDetected) ConfigMode.AUTO else ConfigMode.MANUAL
        autoModeRadio.isSelected = (currentMode == ConfigMode.AUTO)
        manualModeRadio.isSelected = (currentMode == ConfigMode.MANUAL)
        
        updateFieldsEditability()
    }

    /**
     * Attempts to load credentials from Git Credential Helper
     */
    private fun loadCredentialsFromGit() {
        val gitCredHelper = GitCredentialHelperService.getInstance(project)
        
        // Check if credential helper is available
        if (!gitCredHelper.isCredentialHelperAvailable()) {
            Messages.showWarningDialog(
                project,
                "Git Credential Helper not available.\n\n" +
                        "Make sure you have configured Git correctly and have saved credentials.\n" +
                        "Example: git config --global credential.helper manager",
                "Credential Helper Not Available"
            )
            return
        }
        
        // Try to retrieve credentials
        val token = gitCredHelper.getCredentialsForCurrentRepository()
        
        if (token != null && token.isNotBlank()) {
            patField.text = token
            Messages.showMessageDialog(
                project,
                "Personal Access Token successfully loaded from Git Credential Helper!\n\n" +
                        "Token found and inserted in the PAT field.\n" +
                        "Click 'Test Connection' to verify it works.",
                "Credentials Loaded",
                Messages.getInformationIcon()
            )
        } else {
            Messages.showMessageDialog(
                project,
                "No credentials found in Git Credential Helper.\n\n" +
                        "Possible causes:\n" +
                        "• You have never saved credentials for this repository\n" +
                        "• The credential helper is not configured\n" +
                        "• Credentials are saved with a different URL\n\n" +
                        "Tip: run 'git fetch' or 'git pull' and enter the PAT when prompted,\n" +
                        "then come back here and try again.",
                "Credentials Not Found",
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

        // Field validation
        if (pat.isBlank()) {
            Messages.showErrorDialog(
                project,
                "Enter the Personal Access Token before testing the connection.",
                "PAT Missing"
            )
            return
        }

        if (currentMode == ConfigMode.MANUAL && (org.isBlank() || proj.isBlank() || repo.isBlank())) {
            Messages.showErrorDialog(
                project,
                "Fill in Organization, Project, and Repository before testing the connection.",
                "Incomplete Configuration"
            )
            return
        }

        // Create config for test (without saving yet)
        val testConfig = if (currentMode == ConfigMode.MANUAL) {
            AzureDevOpsConfig.create(
                organization = org,
                project = proj,
                repository = repo,
                personalAccessToken = pat
            )
        } else {
            // In auto mode, use detected values
            val detector = AzureDevOpsRepositoryDetector.getInstance(project)
            val repoInfo = detector.detectAzureDevOpsInfo()
            
            if (repoInfo == null) {
                Messages.showErrorDialog(
                    project,
                    "Unable to automatically detect the Azure DevOps repository.\n" +
                            "Make sure the project is cloned from Azure DevOps.",
                    "Repository Not Detected"
                )
                return
            }
            
            AzureDevOpsConfig.create(
                organization = repoInfo.organization,
                project = repoInfo.project,
                repository = repoInfo.repository,
                personalAccessToken = pat
            )
        }

        // Check that the config is valid
        if (!testConfig.isValid()) {
            Messages.showErrorDialog(
                project,
                "Invalid configuration. Make sure all fields are filled.",
                "Incomplete Configuration"
            )
            return
        }

        try {
            // Test the connection directly with the test config
            val apiClient = AzureDevOpsApiClient.getInstance(project)
            // URL encode components to handle special characters (spaces, accents, etc.)
            val encodedOrg = URLEncoder.encode(testConfig.organization, StandardCharsets.UTF_8.toString())
            val encodedProject = URLEncoder.encode(testConfig.project, StandardCharsets.UTF_8.toString())
            val encodedRepo = URLEncoder.encode(testConfig.repository, StandardCharsets.UTF_8.toString())
            val url = "https://dev.azure.com/$encodedOrg/$encodedProject/_apis/git/repositories/$encodedRepo?api-version=7.0"
            
            // Use executeGet directly with test parameters
            testConnectionDirectly(url, testConfig.personalAccessToken)
            
            Messages.showInfoMessage(
                project,
                "Successfully connected to Azure DevOps!\n\n" +
                        "Organization: ${testConfig.organization}\n" +
                        "Project: ${testConfig.project}\n" +
                        "Repository: ${testConfig.repository}",
                "Connection Test Successful"
            )
        } catch (e: Exception) {
            Messages.showErrorDialog(
                project,
                "Connection test failed:\n\n${e.message}",
                "Connection Error"
            )
        }
    }
    
    private fun testConnectionDirectly(url: String, token: String) {
        val connection = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
        
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
                    401 -> "Authentication failed. Check the Personal Access Token (401)"
                    403 -> "Insufficient permissions. Check PAT permissions (403)"
                    404 -> "Repository not found. Check Organization, Project, and Repository (404)"
                    else -> "HTTP Error $responseCode: $errorBody"
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
