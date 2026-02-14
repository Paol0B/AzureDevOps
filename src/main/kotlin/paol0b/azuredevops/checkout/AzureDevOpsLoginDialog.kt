package paol0b.azuredevops.checkout

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
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.AzureDevOpsIcons
import paol0b.azuredevops.services.PatValidationService
import java.awt.BorderLayout
import java.awt.CardLayout
import javax.swing.*

/**
 * Dialog for logging into Azure DevOps with OAuth 2.0 or Personal Access Token.
 *
 * The dialog shows a toggle that lets the user switch between OAuth (device-code
 * flow) and PAT authentication inside the same panel.
 *
 * For PAT login the token is validated before being accepted:
 *   - Clone permission (list repos)
 *   - Pull Request permission (list PRs)
 *
 * All server URLs are accepted without a hardcoded domain check so that
 * self-hosted Azure DevOps Server instances will work in the future.
 */
class AzureDevOpsLoginDialog(private val project: Project?) : DialogWrapper(project, true) {

    private val serverUrlField = JBTextField("https://dev.azure.com/", 60)
    private val tokenField = JBPasswordField()
    private val oauthButton = JButton("Sign in with Browser (OAuth)")
    private val patLoginButton = JButton("Validate & Sign In")
    private val toggleLink = JButton("Use Personal Access Token instead")

    private val cardLayout = CardLayout()
    private val cardPanel = JPanel(cardLayout)
    private val patInfoLabel = JBLabel()

    private var account: AzureDevOpsAccount? = null
    private var useOAuth = true

    init {
        title = "Sign In to Azure DevOps"

        oauthButton.addActionListener { performOAuthLogin() }
        patLoginButton.addActionListener { performPatLogin() }
        toggleLink.addActionListener { toggleMode() }

        // Borderless link-like appearance for the toggle
        toggleLink.isBorderPainted = false
        toggleLink.isContentAreaFilled = false
        toggleLink.foreground = JBUI.CurrentTheme.Link.Foreground.ENABLED
        toggleLink.cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)

        init()
    }

    // -------- actions --------

    override fun createActions(): Array<Action> = arrayOf(cancelAction)

    // -------- UI --------

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout(0, 15))

        // --- Header ---
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            val originalIcon = AzureDevOpsIcons.Logo
            val scaledIcon = com.intellij.util.IconUtil.scale(originalIcon, null, 2.5f)
            add(JBLabel(scaledIcon).apply { alignmentX = JComponent.CENTER_ALIGNMENT })
            add(Box.createVerticalStrut(20))

            add(JBLabel("<html><div style='text-align:center;'><b style='font-size:16px;'>Sign In to Azure DevOps</b></div></html>").apply {
                alignmentX = JComponent.CENTER_ALIGNMENT
                foreground = UIUtil.getLabelForeground()
            })
            add(Box.createVerticalStrut(8))

            add(JBLabel("<html><div style='text-align:center;color:gray;'>Connect your Azure DevOps account to get started</div></html>").apply {
                alignmentX = JComponent.CENTER_ALIGNMENT
                foreground = UIUtil.getLabelInfoForeground()
            })

            border = JBUI.Borders.empty(10, 20)
        }

        // --- Organization URL (shared by both modes) ---
        val urlCard = JPanel(BorderLayout(10, 10)).apply {
            border = JBUI.Borders.compound(JBUI.Borders.empty(0, 20), JBUI.Borders.empty(10))

            val inner = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)

                add(JPanel(BorderLayout()).apply {
                    add(JBLabel("Organization URL").apply { font = font.deriveFont(java.awt.Font.BOLD) }, BorderLayout.WEST)
                })
                add(Box.createVerticalStrut(8))
                add(JPanel(BorderLayout()).apply {
                    add(serverUrlField, BorderLayout.CENTER)
                    serverUrlField.putClientProperty("JTextField.placeholderText", "https://dev.azure.com/YourOrganization")
                })
                add(Box.createVerticalStrut(5))
                add(JBLabel("<html><div style='font-size:11px;color:gray;'>Examples: dev.azure.com/contoso or contoso.visualstudio.com</div></html>").apply {
                    foreground = UIUtil.getLabelInfoForeground()
                })
            }
            add(inner, BorderLayout.CENTER)
        }

        // --- OAuth card ---
        val oauthCard = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            val btnPanel = JPanel().apply {
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
            add(btnPanel)

            add(JBLabel("<html><div style='text-align:center;font-size:11px;color:gray;'>" +
                    "You'll be redirected to your browser to complete the sign-in process.<br>" +
                    "Your credentials are stored securely in the IDE.</div></html>").apply {
                alignmentX = JComponent.CENTER_ALIGNMENT
                foreground = UIUtil.getLabelInfoForeground()
                border = JBUI.Borders.empty(10, 30, 5, 30)
            })
        }

        // --- PAT card ---
        val patCard = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)

            val tokenPanel = JPanel(BorderLayout(10, 10)).apply {
                border = JBUI.Borders.compound(JBUI.Borders.empty(0, 20), JBUI.Borders.empty(10))
                val inner = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    add(JPanel(BorderLayout()).apply {
                        add(JBLabel("Personal Access Token").apply { font = font.deriveFont(java.awt.Font.BOLD) }, BorderLayout.WEST)
                    })
                    add(Box.createVerticalStrut(8))
                    add(JPanel(BorderLayout()).apply {
                        add(tokenField, BorderLayout.CENTER)
                        tokenField.putClientProperty("JTextField.placeholderText", "Paste your PAT here")
                    })
                    add(Box.createVerticalStrut(5))
                    add(JBLabel("<html><div style='font-size:11px;color:gray;'>Required scopes: Code (Read), Pull Requests (Read)</div></html>").apply {
                        foreground = UIUtil.getLabelInfoForeground()
                    })
                }
                add(inner, BorderLayout.CENTER)
            }
            add(tokenPanel)
            add(Box.createVerticalStrut(10))

            val btnPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                patLoginButton.apply {
                    preferredSize = JBUI.size(280, 40)
                    maximumSize = JBUI.size(280, 40)
                    font = font.deriveFont(java.awt.Font.BOLD, 14f)
                    alignmentX = JComponent.CENTER_ALIGNMENT
                }
                add(patLoginButton)
                border = JBUI.Borders.empty(0, 20, 5, 20)
            }
            add(btnPanel)

            // Validation result label
            patInfoLabel.apply {
                alignmentX = JComponent.CENTER_ALIGNMENT
                foreground = UIUtil.getLabelInfoForeground()
                border = JBUI.Borders.empty(5, 30, 5, 30)
            }
            add(patInfoLabel)
        }

        // --- Card container ---
        cardPanel.add(oauthCard, "OAUTH")
        cardPanel.add(patCard, "PAT")

        // --- Toggle link at the bottom ---
        val togglePanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(Box.createHorizontalGlue())
            add(toggleLink)
            add(Box.createHorizontalGlue())
            border = JBUI.Borders.empty(5, 20, 10, 20)
        }

        // --- Content ---
        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(urlCard)
            add(Box.createVerticalStrut(15))
            add(cardPanel)
            add(togglePanel)
        }

        mainPanel.add(headerPanel, BorderLayout.NORTH)
        mainPanel.add(contentPanel, BorderLayout.CENTER)
        mainPanel.border = JBUI.Borders.empty(15)
        mainPanel.preferredSize = JBUI.size(500, 480)

        return mainPanel
    }

    // -------- mode toggle --------

    private fun toggleMode() {
        useOAuth = !useOAuth
        if (useOAuth) {
            cardLayout.show(cardPanel, "OAUTH")
            toggleLink.text = "Use Personal Access Token instead"
        } else {
            cardLayout.show(cardPanel, "PAT")
            toggleLink.text = "Use OAuth (Sign in with Browser) instead"
        }
    }

    // -------- OAuth flow --------

    private fun performOAuthLogin() {
        val serverUrl = serverUrlField.text.trim().removeSuffix("/")

        if (serverUrl.isBlank() || !isValidServerUrl(serverUrl)) {
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

        ProgressManager.getInstance().run(object : Task.Modal(project, "Requesting Authentication Code...", true) {
            var deviceCodeResponse: AzureDevOpsOAuthService.DeviceCodeResponse? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Connecting to Microsoft..."
                deviceCodeResponse = AzureDevOpsOAuthService.getInstance().requestDeviceCodeSync()
            }

            override fun onSuccess() {
                val response = deviceCodeResponse
                if (response != null) {
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
                Messages.showErrorDialog(contentPanel, "Error: ${error.message}", "Authentication Error")
            }
        })
    }

    // -------- PAT flow --------

    private fun performPatLogin() {
        val serverUrl = serverUrlField.text.trim().removeSuffix("/")
        val pat = String(tokenField.password).trim()

        if (serverUrl.isBlank() || !isValidServerUrl(serverUrl)) {
            Messages.showErrorDialog(contentPanel, "Please enter a valid Azure DevOps URL.", "Invalid URL")
            return
        }
        if (pat.isBlank()) {
            Messages.showErrorDialog(contentPanel, "Please enter your Personal Access Token.", "Token Required")
            return
        }

        patLoginButton.isEnabled = false
        patInfoLabel.text = "<html><div style='color:gray;'>Validating PAT…</div></html>"

        ProgressManager.getInstance().run(object : Task.Modal(project, "Validating Personal Access Token...", true) {
            var result: PatValidationService.ValidationResult? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Checking permissions on $serverUrl..."
                result = PatValidationService.getInstance().validate(serverUrl, pat)
            }

            override fun onSuccess() {
                patLoginButton.isEnabled = true
                val r = result ?: return

                if (r.valid) {
                    patInfoLabel.text = "<html><div style='color:green;'>✓ ${escapeHtml(r.message)}</div></html>"
                    val accountManager = AzureDevOpsAccountManager.getInstance()
                    account = accountManager.addPatAccount(serverUrl, pat, r.message)
                    close(OK_EXIT_CODE)
                } else {
                    patInfoLabel.text = "<html><div style='color:red;'>✗ ${escapeHtml(r.message)}</div></html>"
                }
            }

            override fun onThrowable(error: Throwable) {
                patLoginButton.isEnabled = true
                patInfoLabel.text = "<html><div style='color:red;'>Error: ${escapeHtml(error.message ?: "Unknown error")}</div></html>"
            }
        })
    }

    // -------- validation --------

    override fun doValidate(): ValidationInfo? = null

    override fun doOKAction() {
        super.doOKAction()
    }

    // -------- helpers --------

    /**
     * Accepts any HTTPS URL so that self-hosted instances work too.
     */
    private fun isValidServerUrl(url: String): Boolean {
        return try {
            val uri = java.net.URI(url)
            uri.scheme?.lowercase()?.startsWith("http") == true && uri.host != null
        } catch (_: Exception) {
            false
        }
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
