package paol0b.azuredevops.ui

import com.intellij.openapi.options.Configurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.checkout.AzureDevOpsAccount
import paol0b.azuredevops.checkout.AzureDevOpsAccountManager
import paol0b.azuredevops.model.AzureDevOpsConfig
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.AzureDevOpsConfigService
import paol0b.azuredevops.services.AzureDevOpsRepositoryDetector
import paol0b.azuredevops.services.AzureDevOpsSettingsService
import paol0b.azuredevops.services.AzureDevOpsStatusBarService
import paol0b.azuredevops.services.CommentsPollingService
import paol0b.azuredevops.services.PullRequestsPollingService
import java.awt.BorderLayout
import java.awt.event.ItemEvent
import javax.swing.*

/**
 * Project-level configuration for Azure DevOps.
 * Contains account selection and plugin settings (polling intervals, etc.)
 */
class AzureDevOpsProjectConfigurable(private val project: Project) : Configurable {

    private var mainPanel: JPanel? = null
    private val accountComboBox = ComboBox<AccountItem>()
    private val testConnectionButton = JButton("Test Connection")
    private val manageAccountsButton = JButton("Manage Global Accounts...")

    private val detectorInfoLabel = JBLabel()
    private val accountStatusLabel = JBLabel()

    // Polling spinners
    private val prIntervalSpinner = JSpinner(SpinnerNumberModel(30L, 10L, 300L, 5L))
    private val commentsIntervalSpinner = JSpinner(SpinnerNumberModel(15L, 5L, 120L, 5L))
    private val timelineIntervalSpinner = JSpinner(SpinnerNumberModel(15L, 5L, 120L, 5L))
    private val statusBarIntervalSpinner = JSpinner(SpinnerNumberModel(60L, 15L, 300L, 15L))

    data class AccountItem(
        val account: AzureDevOpsAccount?,
        val displayText: String
    ) {
        override fun toString(): String = displayText
    }

    override fun getDisplayName(): String = "Azure DevOps"

    override fun createComponent(): JComponent {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val detector = AzureDevOpsRepositoryDetector.getInstance(project)
        val detectedInfo = detector.detectAzureDevOpsInfo()

        // Setup detector info
        if (detectedInfo != null) {
            detectorInfoLabel.text = "<html><b style='color: green;'>✓ Azure DevOps Repository Detected</b><br>" +
                "<font color='gray' size='-1'>" +
                "Organization: <b>${detectedInfo.organization}</b><br>" +
                "Project: <b>${detectedInfo.project}</b><br>" +
                "Repository: <b>${detectedInfo.repository}</b>" +
                "</font></html>"
        } else {
            detectorInfoLabel.text = "<html><b style='color: orange;'>⚠ Not an Azure DevOps Repository</b><br>" +
                "<font color='gray' size='-1'>This project is not connected to Azure DevOps</font></html>"
        }

        // Load accounts
        loadAccounts()

        // Account selection listener
        accountComboBox.addItemListener { e ->
            if (e.stateChange == ItemEvent.SELECTED) {
                updateAccountStatus()
            }
        }

        // Buttons
        testConnectionButton.addActionListener {
            testConnection()
        }

        manageAccountsButton.addActionListener {
            // Open global accounts settings
            com.intellij.openapi.options.ShowSettingsUtil.getInstance()
                .showSettingsDialog(project, "Azure DevOps Accounts")
            // Reload accounts after returning
            loadAccounts()
        }

        val buttonPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            add(testConnectionButton)
            add(Box.createHorizontalStrut(5))
            add(manageAccountsButton)
        }

        // Load current polling settings
        val settings = AzureDevOpsSettingsService.getInstance(project).state
        prIntervalSpinner.value = settings.pullRequestIntervalSeconds
        commentsIntervalSpinner.value = settings.commentsIntervalSeconds
        timelineIntervalSpinner.value = settings.timelineIntervalSeconds
        statusBarIntervalSpinner.value = settings.statusBarIntervalSeconds

        val formBuilder = FormBuilder.createFormBuilder()
            // --- Repository & Account ---
            .addComponent(detectorInfoLabel)
            .addSeparator()
            .addLabeledComponent(JBLabel("Account:"), accountComboBox, 1, false)
            .addComponentToRightColumn(accountStatusLabel, 1)
            .addVerticalGap(10)
            .addComponentToRightColumn(buttonPanel, 1)
            .addSeparator()
            .addComponent(JBLabel("<html><font size='-1' color='gray'>" +
                "<b>Note:</b> Accounts are managed globally. Use 'Manage Global Accounts...' to add, remove, or refresh tokens." +
                "</font></html>"), 1)
            // --- Polling ---
            .addVerticalGap(15)
            .addComponent(JBLabel("<html><b>Polling Intervals</b></html>"))
            .addComponent(JBLabel("<html><font size='-1' color='gray'>" +
                "Configure how often the plugin checks for updates. Lower values mean faster updates but more network usage." +
                "</font></html>"), 1)
            .addVerticalGap(5)
            .addLabeledComponent(JBLabel("Pull Requests (seconds):"), prIntervalSpinner, 1, false)
            .addLabeledComponent(JBLabel("Comments (seconds):"), commentsIntervalSpinner, 1, false)
            .addLabeledComponent(JBLabel("Timeline (seconds):"), timelineIntervalSpinner, 1, false)
            .addLabeledComponent(JBLabel("Status Bar (seconds):"), statusBarIntervalSpinner, 1, false)
            .addComponentFillVertically(JPanel(), 0)

        val formPanel = formBuilder.panel

        mainPanel = JPanel(BorderLayout()).apply {
            add(formPanel, BorderLayout.NORTH)
            border = JBUI.Borders.empty(10)
        }

        updateAccountStatus()

        return mainPanel!!
    }

    private fun loadAccounts() {
        val accountManager = AzureDevOpsAccountManager.getInstance()
        val accounts = accountManager.getAccounts()

        accountComboBox.removeAllItems()

        // Add "None" option
        accountComboBox.addItem(AccountItem(null, "-- No Account (Use PAT from settings) --"))

        // Add all accounts
        accounts.forEach { account ->
            val state = accountManager.getAccountAuthState(account.id)
            val statusIcon = when (state) {
                AzureDevOpsAccountManager.AccountAuthState.VALID -> "✓"
                AzureDevOpsAccountManager.AccountAuthState.EXPIRED -> "⚠"
                AzureDevOpsAccountManager.AccountAuthState.REVOKED -> "✗"
                else -> "?"
            }
            accountComboBox.addItem(AccountItem(account, "$statusIcon ${account.displayName}"))
        }

        // Try to select current account (match by URL)
        val detector = AzureDevOpsRepositoryDetector.getInstance(project)
        val detectedInfo = detector.detectAzureDevOpsInfo()
        if (detectedInfo != null) {
            val matchingAccount = accounts.find {
                it.serverUrl.contains(detectedInfo.organization, ignoreCase = true)
            }
            if (matchingAccount != null) {
                for (i in 0 until accountComboBox.itemCount) {
                    val item = accountComboBox.getItemAt(i)
                    if (item.account?.id == matchingAccount.id) {
                        accountComboBox.selectedIndex = i
                        break
                    }
                }
            }
        }
    }

    private fun updateAccountStatus() {
        val selectedItem = accountComboBox.selectedItem as? AccountItem
        if (selectedItem?.account != null) {
            val accountManager = AzureDevOpsAccountManager.getInstance()
            val state = accountManager.getAccountAuthState(selectedItem.account.id)

            accountStatusLabel.text = when (state) {
                AzureDevOpsAccountManager.AccountAuthState.VALID ->
                    "<html><font color='green'>✓ Token is valid</font></html>"
                AzureDevOpsAccountManager.AccountAuthState.EXPIRED ->
                    "<html><font color='orange'>⚠ Token expired - use 'Manage Accounts' to refresh</font></html>"
                AzureDevOpsAccountManager.AccountAuthState.REVOKED ->
                    "<html><font color='red'>✗ Token revoked - use 'Manage Accounts' to re-authenticate</font></html>"
                else ->
                    "<html><font color='gray'>? Token status unknown</font></html>"
            }
        } else {
            accountStatusLabel.text = "<html><font color='gray'>Using project-level PAT (legacy mode)</font></html>"
        }
    }

    private fun testConnection() {
        val detector = AzureDevOpsRepositoryDetector.getInstance(project)
        val repoInfo = detector.detectAzureDevOpsInfo()

        if (repoInfo == null) {
            Messages.showErrorDialog(
                project,
                "Unable to detect Azure DevOps repository.\nMake sure the project is cloned from Azure DevOps.",
                "Repository Not Detected"
            )
            return
        }

        val selectedItem = accountComboBox.selectedItem as? AccountItem
        val token = if (selectedItem?.account != null) {
            val accountManager = AzureDevOpsAccountManager.getInstance()
            accountManager.getToken(selectedItem.account.id)
        } else {
            // Fallback to project-level PAT
            AzureDevOpsConfigService.getInstance(project).getConfig().personalAccessToken
        }

        if (token.isNullOrBlank()) {
            Messages.showErrorDialog(
                project,
                "No authentication token available.\nPlease select an account or configure a PAT.",
                "No Token"
            )
            return
        }

        try {
            val apiClient = AzureDevOpsApiClient.getInstance(project)
            val url = apiClient.buildApiUrl(repoInfo.project, repoInfo.repository, "?api-version=7.0")

            testConnectionDirectly(url, token)

            Messages.showInfoMessage(
                project,
                "Successfully connected to Azure DevOps!\n\n" +
                        "Organization: ${repoInfo.organization}\n" +
                        "Project: ${repoInfo.project}\n" +
                        "Repository: ${repoInfo.repository}",
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
            val encodedCredentials = java.util.Base64.getEncoder().encodeToString(
                credentials.toByteArray(java.nio.charset.StandardCharsets.UTF_8)
            )
            connection.setRequestProperty("Authorization", "Basic $encodedCredentials")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode

            if (responseCode != java.net.HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw Exception(when (responseCode) {
                    401 -> "Authentication failed. Check the token (401)"
                    403 -> "Insufficient permissions (403)"
                    404 -> "Repository not found (404)"
                    else -> "HTTP Error $responseCode: $errorBody"
                })
            }
        } finally {
            connection.disconnect()
        }
    }

    override fun isModified(): Boolean {
        val settings = AzureDevOpsSettingsService.getInstance(project).state
        return prIntervalSpinner.value as Long != settings.pullRequestIntervalSeconds ||
                commentsIntervalSpinner.value as Long != settings.commentsIntervalSeconds ||
                timelineIntervalSpinner.value as Long != settings.timelineIntervalSeconds ||
                statusBarIntervalSpinner.value as Long != settings.statusBarIntervalSeconds
    }

    override fun apply() {
        val settings = AzureDevOpsSettingsService.getInstance(project)
        val state = settings.state
        state.pullRequestIntervalSeconds = prIntervalSpinner.value as Long
        state.commentsIntervalSeconds = commentsIntervalSpinner.value as Long
        state.timelineIntervalSeconds = timelineIntervalSpinner.value as Long
        state.statusBarIntervalSeconds = statusBarIntervalSpinner.value as Long

        // Reschedule active polling services with new intervals
        PullRequestsPollingService.getInstance(project).reschedule()
        CommentsPollingService.getInstance(project).reschedule()
        AzureDevOpsStatusBarService.getInstance(project).reschedule()
    }

    override fun reset() {
        loadAccounts()
        updateAccountStatus()

        val settings = AzureDevOpsSettingsService.getInstance(project).state
        prIntervalSpinner.value = settings.pullRequestIntervalSeconds
        commentsIntervalSpinner.value = settings.commentsIntervalSeconds
        timelineIntervalSpinner.value = settings.timelineIntervalSeconds
        statusBarIntervalSpinner.value = settings.statusBarIntervalSeconds
    }

    override fun disposeUIResources() {
        mainPanel = null
    }
}
