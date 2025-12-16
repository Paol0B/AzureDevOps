package paol0b.azuredevops.checkout

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.AzureDevOpsIcons
import java.awt.BorderLayout
import javax.swing.*

/**
 * Dialog for logging into Azure DevOps with OAuth 2.0 or Personal Access Token.
 * 
 * OAuth Flow:
 * 1. User enters their Azure DevOps organization URL
 * 2. Clicks "Sign in with Browser"
 * 3. Browser opens for OAuth authentication
 * 4. After successful auth, credentials are saved globally in the IDE
 */
class AzureDevOpsLoginDialog(private val project: Project?) : DialogWrapper(project, true) {

    private val serverUrlField = JBTextField("https://dev.azure.com/", 40)
    private val tokenField = JBPasswordField()
    private val oauthButton = JButton("Sign in with Browser (OAuth)")
    private val usePATCheckbox = JCheckBox("Use Personal Access Token instead", false)
    
    private var account: AzureDevOpsAccount? = null
    private var useOAuth = true

    init {
        title = "Sign In to Azure DevOps"
        
        // OAuth button action
        oauthButton.addActionListener {
            performOAuthLogin()
        }
        
        // Toggle between OAuth and PAT mode
        usePATCheckbox.addActionListener {
            useOAuth = !usePATCheckbox.isSelected
            updateUIMode()
        }
        
        init()
        updateUIMode()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, 10))
        
        // Header with icon and info
        val headerPanel = JPanel(BorderLayout(10, 0))
        val iconLabel = JBLabel(AzureDevOpsIcons.Logo)
        val infoLabel = JBLabel(
            "<html><b style='font-size: 14px'>Sign In to Azure DevOps</b><br><br>" +
            "<b>Step 1:</b> Enter your organization URL<br>" +
            "<b>Step 2:</b> Click 'Sign in with Browser' for OAuth authentication<br><br>" +
            "<i>Or use a Personal Access Token if you prefer manual authentication.</i></html>"
        ).apply {
            foreground = UIUtil.getLabelForeground()
        }
        
        headerPanel.add(iconLabel, BorderLayout.WEST)
        headerPanel.add(infoLabel, BorderLayout.CENTER)

        // Server URL field (always visible)
        val urlPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Organization URL:"), serverUrlField, 1, false)
            .addTooltip("<html>Enter your Azure DevOps organization URL:<br>" +
                    "• https://dev.azure.com/<b>YourOrganization</b><br>" +
                    "• https://<b>YourOrganization</b>.visualstudio.com</html>")
            .panel

        // OAuth button
        val oauthPanel = JPanel(BorderLayout()).apply {
            oauthButton.preferredSize = JBUI.size(200, 32)
            add(oauthButton, BorderLayout.WEST)
            border = JBUI.Borders.empty(10, 0)
        }

        // Separator
        val separatorPanel = JPanel(BorderLayout()).apply {
            add(JSeparator(), BorderLayout.CENTER)
            add(usePATCheckbox, BorderLayout.SOUTH)
            border = JBUI.Borders.empty(10, 0)
        }

        // PAT form (shown only when checkbox is selected)
        val patPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Personal Access Token:"), tokenField, 1, false)
            .addTooltip("<html>Create a PAT at:<br>" +
                    "<b>Azure DevOps</b> → <b>User Settings</b> → <b>Personal Access Tokens</b><br>" +
                    "Required scopes: <b>Code (Read)</b></html>")
            .panel

        patPanel.border = JBUI.Borders.empty(5, 0)

        mainPanel.add(headerPanel, BorderLayout.NORTH)
        
        val centerPanel = JPanel()
        centerPanel.layout = BoxLayout(centerPanel, BoxLayout.Y_AXIS)
        centerPanel.add(urlPanel)
        centerPanel.add(oauthPanel)
        centerPanel.add(separatorPanel)
        centerPanel.add(patPanel)
        
        mainPanel.add(centerPanel, BorderLayout.CENTER)
        mainPanel.border = JBUI.Borders.empty(10)
        mainPanel.preferredSize = JBUI.size(550, 350)

        return mainPanel
    }

    private fun updateUIMode() {
        val usePAT = !useOAuth
        tokenField.isEnabled = usePAT
        oauthButton.isEnabled = !usePAT
        
        // Server URL is always enabled
        serverUrlField.isEnabled = true
        
        // Update OK button
        setOKButtonText(if (usePAT) "Sign In" else "Cancel")
    }

    private fun performOAuthLogin() {
        val serverUrl = serverUrlField.text.trim().removeSuffix("/")
        
        if (serverUrl.isBlank() || !isValidAzureDevOpsUrl(serverUrl)) {
            Messages.showErrorDialog(
                contentPanel,
                "Please enter a valid Azure DevOps organization URL.\n\n" +
                "Examples:\n" +
                "• https://dev.azure.com/YourOrganization\n" +
                "• https://YourOrganization.visualstudio.com",
                "Invalid URL"
            )
            return
        }

        // Request device code first (blocking)
        ProgressManager.getInstance().run(object : Task.Modal(
            project,
            "Requesting Authentication Code...",
            true
        ) {
            var deviceCodeResponse: AzureDevOpsOAuthService.DeviceCodeResponse? = null
            
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Connecting to Microsoft..."
                
                val oauthService = AzureDevOpsOAuthService.getInstance()
                deviceCodeResponse = oauthService.requestDeviceCodeSync()
            }
            
            override fun onSuccess() {
                val response = deviceCodeResponse
                if (response != null) {
                    // Show device code dialog with pre-loaded code
                    val deviceCodeDialog = DeviceCodeAuthDialog(project, serverUrl, response)
                    if (deviceCodeDialog.showAndGet()) {
                        val authenticatedAccount = deviceCodeDialog.getAuthenticatedAccount()
                        if (authenticatedAccount != null) {
                            account = authenticatedAccount
                            close(OK_EXIT_CODE)
                        }
                    }
                } else {
                    Messages.showErrorDialog(
                        contentPanel,
                        "Failed to request authentication code from Microsoft.\n\n" +
                        "Please check your internet connection and try again.",
                        "Authentication Error"
                    )
                }
            }
            
            override fun onThrowable(error: Throwable) {
                Messages.showErrorDialog(
                    contentPanel,
                    "Error: ${error.message}",
                    "Authentication Error"
                )
            }
        })
    }

    override fun doValidate(): ValidationInfo? {
        // Only validate if using PAT mode
        if (useOAuth) {
            return null
        }

        val serverUrl = serverUrlField.text.trim()
        val token = String(tokenField.password).trim()

        if (serverUrl.isBlank()) {
            return ValidationInfo("Organization URL is required", serverUrlField)
        }

        if (!isValidAzureDevOpsUrl(serverUrl)) {
            return ValidationInfo("Invalid Azure DevOps URL format", serverUrlField)
        }

        if (token.isBlank()) {
            return ValidationInfo("Personal Access Token is required", tokenField)
        }

        return null
    }

    override fun doOKAction() {
        // Only handle PAT mode here, OAuth is handled by the button
        if (useOAuth) {
            super.doOKAction()
            return
        }

        val serverUrl = serverUrlField.text.trim().removeSuffix("/")
        val token = String(tokenField.password).trim()

        // Test connection before saving
        if (!testConnection(serverUrl, token)) {
            Messages.showErrorDialog(
                project,
                "Failed to connect to Azure DevOps.\n\n" +
                "Please verify:\n" +
                "• Server URL is correct\n" +
                "• Personal Access Token is valid\n" +
                "• Token has 'Code (Read)' permission",
                "Connection Failed"
            )
            return
        }

        // Save account
        val accountManager = AzureDevOpsAccountManager.getInstance()
        account = accountManager.addAccount(serverUrl, token)

        super.doOKAction()
    }

    fun getAccount(): AzureDevOpsAccount? = account

    private fun isValidAzureDevOpsUrl(url: String): Boolean {
        return try {
            val uri = java.net.URI(url)
            val host = uri.host?.lowercase() ?: ""
            host.contains("dev.azure.com") || 
            host.contains("visualstudio.com") ||
            host.contains("azure.com")
        } catch (e: Exception) {
            false
        }
    }

    private fun testConnection(serverUrl: String, token: String): Boolean {
        return try {
            // Use OAuth service for consistent testing
            val oauthService = AzureDevOpsOAuthService.getInstance()
            val result = oauthService.authenticateWithPAT(serverUrl, token)
            result != null
        } catch (e: Exception) {
            false
        }
    }
}
