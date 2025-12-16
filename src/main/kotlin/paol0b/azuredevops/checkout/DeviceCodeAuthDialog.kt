package paol0b.azuredevops.checkout

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.AzureDevOpsIcons
import java.awt.BorderLayout
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.util.concurrent.CompletableFuture
import javax.swing.*

/**
 * Dialog that shows device code for authentication.
 * The device code is pre-loaded before the dialog is shown.
 * Automatically opens browser and polls for authentication completion.
 */
class DeviceCodeAuthDialog(
    private val project: Project?,
    private val organizationUrl: String,
    private val deviceCodeResponse: AzureDevOpsOAuthService.DeviceCodeResponse
) : DialogWrapper(project, true) {

    private val logger = Logger.getInstance(DeviceCodeAuthDialog::class.java)
    private val codeField = JBTextField()
    private val statusLabel = JBLabel("Click 'Continue' to open browser and authenticate")
    private val instructionLabel = JBLabel()
    private val copyButton = JButton("Copy Code")
    private val openBrowserButton = JButton("Open Browser Again")
    
    private var authenticatedAccount: AzureDevOpsAccount? = null
    private var authenticationFuture: CompletableFuture<AzureDevOpsOAuthService.OAuthResult?>? = null

    init {
        title = "Sign In with Browser"
        
        codeField.isEditable = false
        codeField.text = deviceCodeResponse.userCode
        codeField.horizontalAlignment = JTextField.CENTER
        codeField.font = codeField.font.deriveFont(java.awt.Font.BOLD, 22f)
        codeField.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(java.awt.Color(0, 102, 204), 2),
            BorderFactory.createEmptyBorder(10, 15, 10, 15)
        )
        
        // Copy code to clipboard immediately
        val clipboard = Toolkit.getDefaultToolkit().systemClipboard
        clipboard.setContents(StringSelection(deviceCodeResponse.userCode), null)
        
        copyButton.addActionListener {
            val code = codeField.text
            if (code.isNotEmpty()) {
                val clip = Toolkit.getDefaultToolkit().systemClipboard
                clip.setContents(StringSelection(code), null)
                copyButton.text = "Copied!"
                javax.swing.Timer(2000) { 
                    copyButton.text = "Copy Code" 
                }.apply { 
                    isRepeats = false 
                }.start()
            }
        }
        
        openBrowserButton.addActionListener {
            BrowserUtil.browse(deviceCodeResponse.verificationUri)
        }
        
        setOKButtonText("Continue")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 15))
        panel.preferredSize = JBUI.size(550, 300)
        
        // Header
        val headerPanel = JPanel(BorderLayout(10, 0))
        headerPanel.add(JBLabel(AzureDevOpsIcons.Logo), BorderLayout.WEST)
        headerPanel.add(JBLabel(
            "<html><b style='font-size: 14px'>Authenticate with Microsoft</b></html>"
        ), BorderLayout.CENTER)
        
        // Instructions
        instructionLabel.text = "<html>" +
                "<div style='padding: 10px;'>" +
                "<p><b style='font-size: 12px; color: #0066CC;'>✓ Code copied to clipboard!</b></p>" +
                "<p style='margin-top: 10px;'><b>1.</b> Click 'Continue' below</p>" +
                "<p><b>2.</b> The browser will open automatically</p>" +
                "<p><b>3.</b> Paste the code (Ctrl+V) or enter: <b>${deviceCodeResponse.userCode}</b></p>" +
                "<p><b>4.</b> Sign in with your Microsoft account</p>" +
                "</div></html>"
        
        // Code display - larger and more visible
        val codePanel = JPanel(BorderLayout(8, 5))
        val codeLabelPanel = JPanel(BorderLayout())
        codeLabelPanel.add(JBLabel("<html><b>Your code:</b></html>"), BorderLayout.WEST)
        codeLabelPanel.add(JBLabel("<html><i style='color: #666;'>(automatically copied to clipboard)</i></html>"), BorderLayout.CENTER)
        codePanel.add(codeLabelPanel, BorderLayout.NORTH)
        codePanel.add(codeField, BorderLayout.CENTER)
        
        val buttonPanel = JPanel()
        buttonPanel.add(copyButton)
        buttonPanel.add(openBrowserButton)
        codePanel.add(buttonPanel, BorderLayout.SOUTH)
        
        // Status
        statusLabel.foreground = UIUtil.getContextHelpForeground()
        
        val formPanel = FormBuilder.createFormBuilder()
            .addComponent(instructionLabel)
            .addVerticalGap(10)
            .addComponent(codePanel)
            .addVerticalGap(15)
            .addComponent(statusLabel)
            .panel
        
        panel.add(headerPanel, BorderLayout.NORTH)
        panel.add(formPanel, BorderLayout.CENTER)
        
        return panel
    }

    override fun doOKAction() {
        // When user clicks OK/Continue, start authentication
        if (authenticationFuture == null) {
            setOKButtonText("Authenticating...")
            okAction.isEnabled = false
            statusLabel.text = "Opening browser and waiting for authentication..."
            
            startAuthentication()
        } else {
            // Authentication completed
            super.doOKAction()
        }
    }

    private fun startAuthentication() {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Authenticating with Microsoft",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Waiting for authentication in browser..."
                
                val oauthService = AzureDevOpsOAuthService.getInstance()
                authenticationFuture = oauthService.authenticateWithDeviceCode(organizationUrl, deviceCodeResponse)
                
                try {
                    val result = authenticationFuture?.get()
                    
                    ApplicationManager.getApplication().invokeLater {
                        if (result != null) {
                            // Authentication successful
                            val accountManager = AzureDevOpsAccountManager.getInstance()
                            authenticatedAccount = accountManager.addAccount(result.serverUrl, result.accessToken)
                            
                            statusLabel.text = "✓ Authentication successful!"
                            statusLabel.foreground = java.awt.Color(0, 128, 0)
                            setOKButtonText("Done")
                            okAction.isEnabled = true
                            
                            // Auto-close and return to clone dialog
                            javax.swing.Timer(1000) {
                                close(OK_EXIT_CODE)
                            }.apply { 
                                isRepeats = false 
                            }.start()
                        } else {
                            statusLabel.text = "✗ Authentication failed or was cancelled"
                            statusLabel.foreground = java.awt.Color(200, 0, 0)
                            setOKButtonText("Close")
                            okAction.isEnabled = true
                        }
                    }
                } catch (e: Exception) {
                    logger.error("Authentication error", e)
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = "✗ Error: ${e.message}"
                        statusLabel.foreground = java.awt.Color(200, 0, 0)
                        setOKButtonText("Close")
                        okAction.isEnabled = true
                    }
                }
            }
        })
    }

    fun getAuthenticatedAccount(): AzureDevOpsAccount? = authenticatedAccount
}
