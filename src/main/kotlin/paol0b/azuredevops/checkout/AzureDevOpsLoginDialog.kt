package paol0b.azuredevops.checkout

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
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Dialog for logging into Azure DevOps.
 * Collects server URL and Personal Access Token.
 */
class AzureDevOpsLoginDialog(private val project: Project?) : DialogWrapper(project, true) {

    private val serverUrlField = JBTextField("https://dev.azure.com/", 40)
    private val tokenField = JBPasswordField()
    
    private var account: AzureDevOpsAccount? = null

    init {
        title = "Log In to Azure DevOps"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val mainPanel = JPanel(BorderLayout())
        
        val infoLabel = JBLabel(
            "<html><b>Azure DevOps Login</b><br><br>" +
            "Enter your Azure DevOps server URL and Personal Access Token.<br>" +
            "The PAT must have 'Code (Read)' permission to list and clone repositories.<br><br>" +
            "<i>Examples:</i><br>" +
            "• https://dev.azure.com/YourOrganization<br>" +
            "• https://YourOrganization.visualstudio.com</html>"
        ).apply {
            foreground = UIUtil.getLabelForeground()
        }

        val formPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Server URL:"), serverUrlField, 1, false)
            .addTooltip("Azure DevOps organization URL (e.g., https://dev.azure.com/MyOrg)")
            .addLabeledComponent(JBLabel("Personal Access Token:"), tokenField, 1, false)
            .addTooltip("PAT with 'Code (Read)' permission")
            .addComponentFillVertically(JPanel(), 0)
            .panel

        mainPanel.add(infoLabel, BorderLayout.NORTH)
        mainPanel.add(formPanel, BorderLayout.CENTER)
        
        mainPanel.border = JBUI.Borders.empty(10)
        mainPanel.preferredSize = JBUI.size(500, 250)

        return mainPanel
    }

    override fun doValidate(): ValidationInfo? {
        val serverUrl = serverUrlField.text.trim()
        val token = String(tokenField.password).trim()

        if (serverUrl.isBlank()) {
            return ValidationInfo("Server URL is required", serverUrlField)
        }

        if (!isValidAzureDevOpsUrl(serverUrl)) {
            return ValidationInfo("Invalid Azure DevOps URL", serverUrlField)
        }

        if (token.isBlank()) {
            return ValidationInfo("Personal Access Token is required", tokenField)
        }

        return null
    }

    override fun doOKAction() {
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
            // Test with a simple API call to list projects
            val url = "$serverUrl/_apis/projects?api-version=7.0"
            val connection = java.net.URI(url).toURL().openConnection() as java.net.HttpURLConnection
            
            try {
                connection.requestMethod = "GET"
                val credentials = ":$token"
                val encodedCredentials = java.util.Base64.getEncoder()
                    .encodeToString(credentials.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
                connection.setRequestProperty("Authorization", "Basic $encodedCredentials")
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                val responseCode = connection.responseCode
                responseCode == java.net.HttpURLConnection.HTTP_OK
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            false
        }
    }
}
