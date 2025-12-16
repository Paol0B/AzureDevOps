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

    private val serverUrlField = JBTextField("https://dev.azure.com/", 60)
    private val tokenField = JBPasswordField()
    private val oauthButton = JButton("Sign in with Browser (OAuth)")
    // private val usePATCheckbox = JCheckBox("Use Personal Access Token instead", false)
    
    private var account: AzureDevOpsAccount? = null
    private var useOAuth = true

    init {
        title = "Sign In to Azure DevOps"
        
        // OAuth button action
        oauthButton.addActionListener {
            performOAuthLogin()
        }
        
        // Toggle between OAuth and PAT mode
        /*
        usePATCheckbox.addActionListener {
            useOAuth = !usePATCheckbox.isSelected
            updateUIMode()
        }
        */
        
        init()
        updateUIMode()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, 15))
        
        // Header with centered icon and title
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            
            // Logo centered and scaled
            val originalIcon = AzureDevOpsIcons.Logo
            val scaledIcon = com.intellij.util.IconUtil.scale(originalIcon, null, 2.5f)
            val logoLabel = JBLabel(scaledIcon).apply {
                alignmentX = JComponent.CENTER_ALIGNMENT
            }
            add(logoLabel)
            add(Box.createVerticalStrut(20))
            
            // Title centered
            val titleLabel = JBLabel("<html><div style='text-align: center;'><b style='font-size: 16px;'>Sign In to Azure DevOps</b></div></html>").apply {
                alignmentX = JComponent.CENTER_ALIGNMENT
                foreground = UIUtil.getLabelForeground()
            }
            add(titleLabel)
            add(Box.createVerticalStrut(8))
            
            // Subtitle centered
            val subtitleLabel = JBLabel("<html><div style='text-align: center; color: gray;'>Connect your Azure DevOps account to get started</div></html>").apply {
                alignmentX = JComponent.CENTER_ALIGNMENT
                foreground = UIUtil.getLabelInfoForeground()
            }
            add(subtitleLabel)
            
            border = JBUI.Borders.empty(10, 20, 10, 20)
        }

        // Content panel with form
        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            
            // Organization URL section with card-like appearance
            val urlCard = JPanel(BorderLayout(10, 10)).apply {
                border = JBUI.Borders.compound(
                    JBUI.Borders.empty(0, 20, 0, 20),
                    JBUI.Borders.empty(10)
                )
                
                val urlLabelPanel = JPanel(BorderLayout()).apply {
                    val label = JBLabel("Organization URL").apply {
                        font = font.deriveFont(java.awt.Font.BOLD)
                    }
                    add(label, BorderLayout.WEST)
                }
                
                val urlFieldPanel = JPanel(BorderLayout()).apply {
                    add(serverUrlField, BorderLayout.CENTER)
                    serverUrlField.apply {
                        putClientProperty("JTextField.placeholderText", "https://dev.azure.com/YourOrganization")
                    }
                }
                
                val examplesLabel = JBLabel("<html><div style='font-size: 11px; color: gray; margin-top: 5px;'>" +
                        "Examples: dev.azure.com/contoso or contoso.visualstudio.com</div></html>").apply {
                    foreground = UIUtil.getLabelInfoForeground()
                }
                
                val innerPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(urlLabelPanel)
                    add(Box.createVerticalStrut(8))
                    add(urlFieldPanel)
                    add(Box.createVerticalStrut(5))
                    add(examplesLabel)
                }
                
                add(innerPanel, BorderLayout.CENTER)
            }
            
            add(urlCard)
            add(Box.createVerticalStrut(20))
            
            // OAuth button - prominent and centered
            val buttonPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                
                oauthButton.apply {
                    preferredSize = JBUI.size(280, 40)
                    maximumSize = JBUI.size(280, 40)
                    font = font.deriveFont(java.awt.Font.BOLD, 14f)
                    alignmentX = JComponent.CENTER_ALIGNMENT
                }
                
                add(oauthButton)
                border = JBUI.Borders.empty(0, 20, 10, 20)
            }
            
            add(buttonPanel)
            
            // Info footer
            val infoPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                
                val infoLabel = JBLabel("<html><div style='text-align: center; font-size: 11px; color: gray;'>" +
                        "You'll be redirected to your browser to complete the sign-in process.<br>" +
                        "Your credentials are stored securely in the IDE.</div></html>").apply {
                    alignmentX = JComponent.CENTER_ALIGNMENT
                    foreground = UIUtil.getLabelInfoForeground()
                }
                
                add(infoLabel)
                border = JBUI.Borders.empty(15, 30, 10, 30)
            }
            
            add(infoPanel)
        }

        mainPanel.add(headerPanel, BorderLayout.NORTH)
        mainPanel.add(contentPanel, BorderLayout.CENTER)
        mainPanel.border = JBUI.Borders.empty(15)
        mainPanel.preferredSize = JBUI.size(500, 420)

        return mainPanel
    }

    private fun updateUIMode() {
        // OAuth mode only - always enabled
        serverUrlField.isEnabled = true
        oauthButton.isEnabled = true
        // tokenField.isEnabled = false
        
        // Update OK button (Cancel for OAuth mode)
        setOKButtonText("Cancel")
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
