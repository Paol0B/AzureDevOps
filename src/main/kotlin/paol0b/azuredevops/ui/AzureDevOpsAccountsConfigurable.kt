package paol0b.azuredevops.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
import com.intellij.ui.AnActionButton
import com.intellij.ui.JBColor
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.table.JBTable
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.checkout.AzureDevOpsAccount
import paol0b.azuredevops.checkout.AzureDevOpsAccountManager
import paol0b.azuredevops.checkout.AzureDevOpsLoginDialog
import paol0b.azuredevops.checkout.AzureDevOpsOAuthService
import java.awt.BorderLayout
import java.awt.Component
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/**
 * Application-level configuration for Azure DevOps accounts
 */
class AzureDevOpsAccountsConfigurable : Configurable {

    private var mainPanel: JPanel? = null
    private lateinit var accountsTable: JBTable
    private lateinit var accountsModel: AccountsTableModel
    private lateinit var accountInfoPanel: JPanel
    private val accountManager = AzureDevOpsAccountManager.getInstance()
    private val oauthService = AzureDevOpsOAuthService.getInstance()
    private var countdownTimer: javax.swing.Timer? = null

    override fun getDisplayName(): String = "Azure DevOps Accounts"

    override fun createComponent(): JComponent {
        accountsModel = AccountsTableModel()
        accountsTable = JBTable(accountsModel).apply {
            setDefaultRenderer(Any::class.java, AccountStatusCellRenderer())
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
            selectionModel.addListSelectionListener { 
                updateAccountInfoPanel()
            }
        }

        val decorator = ToolbarDecorator.createDecorator(accountsTable)
            .setAddAction { addAccount() }
            .setRemoveAction { removeAccount() }
            .addExtraActions(
                object : AnActionButton("Refresh Token", "Refresh the authentication token for selected account", AllIcons.Actions.Refresh) {
                    override fun actionPerformed(e: AnActionEvent) {
                        refreshToken()
                    }
                },
                object : AnActionButton("Re-login", "Re-authenticate with selected account", AllIcons.Actions.Execute) {
                    override fun actionPerformed(e: AnActionEvent) {
                        reAuthenticate()
                    }
                }
            )
            .setPreferredSize(JBUI.size(600, 300))

        val decoratorPanel = decorator.createPanel()

        val infoLabel = JBLabel("<html><b>Global Accounts</b><br>" +
            "<font size='-1' color='gray'>Manage your Azure DevOps accounts. " +
            "Accounts are stored globally and available across all projects.</font></html>")

        // Account info panel
        accountInfoPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.emptyTop(10)
            add(JBLabel("<html><i>Select an account to view details</i></html>"), BorderLayout.CENTER)
        }

        val centerPanel = JPanel(BorderLayout()).apply {
            add(decoratorPanel, BorderLayout.CENTER)
            add(accountInfoPanel, BorderLayout.SOUTH)
        }

        mainPanel = JPanel(BorderLayout()).apply {
            add(infoLabel, BorderLayout.NORTH)
            add(centerPanel, BorderLayout.CENTER)
            border = JBUI.Borders.empty(10)
        }

        loadAccounts()

        return mainPanel!!
    }

    private fun updateAccountInfoPanel() {
        // Stop any existing timer
        countdownTimer?.stop()
        countdownTimer = null
        
        val selectedRow = accountsTable.selectedRow
        if (selectedRow < 0) {
            accountInfoPanel.removeAll()
            accountInfoPanel.add(JBLabel("<html><i>Select an account to view details</i></html>"), BorderLayout.CENTER)
            accountInfoPanel.revalidate()
            accountInfoPanel.repaint()
            return
        }

        val accountWithStatus = accountsModel.getAccountAt(selectedRow)
        val account = accountWithStatus.account
        
        val infoText = StringBuilder("<html><b>Account Details:</b><br><br>")
        infoText.append("<b>Display Name:</b> ${account.displayName}<br>")
        infoText.append("<b>Server URL:</b> ${account.serverUrl}<br>")
        infoText.append("<b>Account ID:</b> ${account.id}<br>")
        infoText.append("<b>Status:</b> ${formatAuthState(accountWithStatus.state)}<br>")
        
        if (accountWithStatus.expiresAt > 0) {
            val expiryDate = Date(accountWithStatus.expiresAt)
            val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss")
            infoText.append("<b>Token Expires:</b> ${dateFormat.format(expiryDate)}<br>")
            
            val now = System.currentTimeMillis()
            val timeLeft = accountWithStatus.expiresAt - now
            if (timeLeft > 0) {
                val daysLeft = timeLeft / (1000 * 60 * 60 * 24)
                val hoursLeft = (timeLeft % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)
                val minutesLeft = (timeLeft % (1000 * 60 * 60)) / (1000 * 60)
                val secondsLeft = (timeLeft % (1000 * 60)) / 1000
                infoText.append("<b>Time Remaining:</b> ${daysLeft}d ${hoursLeft}h ${minutesLeft}m ${secondsLeft}s<br>")
            } else {
                infoText.append("<b>Time Remaining:</b> <font color='red'>Expired</font><br>")
            }
        } else {
            infoText.append("<b>Token Expires:</b> Unknown<br>")
        }
        
        if (accountWithStatus.lastRefreshed > 0) {
            val refreshDate = Date(accountWithStatus.lastRefreshed)
            val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss")
            infoText.append("<b>Last Refreshed:</b> ${dateFormat.format(refreshDate)}<br>")
        }
        
        val hasRefreshToken = accountManager.getRefreshToken(account.id) != null
        infoText.append("<b>Has Refresh Token:</b> ${if (hasRefreshToken) "Yes" else "No"}<br>")
        
        infoText.append("</html>")
        
        val infoLabel = JBLabel(infoText.toString())
        accountInfoPanel.removeAll()
        accountInfoPanel.add(infoLabel, BorderLayout.CENTER)
        accountInfoPanel.revalidate()
        accountInfoPanel.repaint()
        
        // Start countdown timer if token is not expired
        if (accountWithStatus.expiresAt > 0 && accountWithStatus.expiresAt > System.currentTimeMillis()) {
            countdownTimer = javax.swing.Timer(1000) {
                SwingUtilities.invokeLater {
                    val now = System.currentTimeMillis()
                    val timeLeft = accountWithStatus.expiresAt - now
                    
                    if (timeLeft > 0) {
                        val daysLeft = timeLeft / (1000 * 60 * 60 * 24)
                        val hoursLeft = (timeLeft % (1000 * 60 * 60 * 24)) / (1000 * 60 * 60)
                        val minutesLeft = (timeLeft % (1000 * 60 * 60)) / (1000 * 60)
                        val secondsLeft = (timeLeft % (1000 * 60)) / 1000
                        
                        val newInfoText = StringBuilder("<html><b>Account Details:</b><br><br>")
                        newInfoText.append("<b>Display Name:</b> ${account.displayName}<br>")
                        newInfoText.append("<b>Server URL:</b> ${account.serverUrl}<br>")
                        newInfoText.append("<b>Account ID:</b> ${account.id}<br>")
                        newInfoText.append("<b>Status:</b> ${formatAuthState(accountWithStatus.state)}<br>")
                        
                        val expiryDate = Date(accountWithStatus.expiresAt)
                        val dateFormat = SimpleDateFormat("MMM dd, yyyy HH:mm:ss")
                        newInfoText.append("<b>Token Expires:</b> ${dateFormat.format(expiryDate)}<br>")
                        newInfoText.append("<b>Time Remaining:</b> ${daysLeft}d ${hoursLeft}h ${minutesLeft}m ${secondsLeft}s<br>")
                        
                        if (accountWithStatus.lastRefreshed > 0) {
                            val refreshDate = Date(accountWithStatus.lastRefreshed)
                            newInfoText.append("<b>Last Refreshed:</b> ${dateFormat.format(refreshDate)}<br>")
                        }
                        
                        val hasRefreshToken = accountManager.getRefreshToken(account.id) != null
                        newInfoText.append("<b>Has Refresh Token:</b> ${if (hasRefreshToken) "Yes" else "No"}<br>")
                        newInfoText.append("</html>")
                        
                        infoLabel.text = newInfoText.toString()
                    } else {
                        countdownTimer?.stop()
                        // Reload to show expired state
                        updateAccountInfoPanel()
                    }
                }
            }
            countdownTimer?.start()
        }
    }

    private fun formatAuthState(state: AzureDevOpsAccountManager.AccountAuthState): String {
        return when (state) {
            AzureDevOpsAccountManager.AccountAuthState.VALID -> "<font color='green'>✓ Valid</font>"
            AzureDevOpsAccountManager.AccountAuthState.EXPIRED -> "<font color='orange'>⚠ Expired</font>"
            AzureDevOpsAccountManager.AccountAuthState.REVOKED -> "<font color='red'>✗ Revoked</font>"
            AzureDevOpsAccountManager.AccountAuthState.UNKNOWN -> "<font color='gray'>? Unknown</font>"
        }
    }

    private fun loadAccounts() {
        accountsModel.setAccounts(accountManager.getAccounts())
        updateAccountInfoPanel()
    }

    private fun addAccount() {
        val dialog = AzureDevOpsLoginDialog(null)
        if (dialog.showAndGet()) {
            loadAccounts()
        }
    }

    private fun removeAccount() {
        val selectedRow = accountsTable.selectedRow
        if (selectedRow >= 0) {
            val accountWithStatus = accountsModel.getAccountAt(selectedRow)
            val account = accountWithStatus.account
            val result = Messages.showYesNoDialog(
                null,
                "Are you sure you want to remove account '${account.displayName}'?\nThis will remove all stored credentials.",
                "Remove Account",
                Messages.getQuestionIcon()
            )
            if (result == Messages.YES) {
                accountManager.removeAccount(account.id)
                loadAccounts()
            }
        }
    }

    private fun refreshToken() {
        val selectedRow = accountsTable.selectedRow
        if (selectedRow < 0) {
            Messages.showWarningDialog(null, "Please select an account", "No Account Selected")
            return
        }

        val accountWithStatus = accountsModel.getAccountAt(selectedRow)
        val account = accountWithStatus.account
        val refreshToken = accountManager.getRefreshToken(account.id)

        if (refreshToken == null) {
            Messages.showWarningDialog(
                null as com.intellij.openapi.project.Project?,
                "No refresh token available for this account.\nPlease re-authenticate using 'Re-login' button.",
                "No Refresh Token"
            )
            return
        }

        ProgressManager.getInstance().run(object : Task.Modal(
            null,
            "Refreshing Access Token...",
            false
        ) {
            var success = false
            var errorMessage: String? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Refreshing token for ${account.displayName}..."

                try {
                    val result = oauthService.refreshAccessToken(refreshToken, account.serverUrl)
                    if (result != null) {
                        accountManager.updateToken(
                            account.id,
                            result.accessToken,
                            result.refreshToken,
                            result.expiresIn
                        )
                        success = true
                    } else {
                        errorMessage = "Failed to refresh token. The refresh token may be expired or invalid."
                    }
                } catch (e: Exception) {
                    errorMessage = e.message
                }
            }

            override fun onSuccess() {
                if (success) {
                    loadAccounts()  // Reload the table data
                    updateAccountInfoPanel()  // Update the info panel to show new expiry
                    Messages.showInfoMessage(
                        null as com.intellij.openapi.project.Project?,
                        "Access token refreshed successfully for '${account.displayName}'",
                        "Token Refreshed"
                    )
                } else {
                    Messages.showErrorDialog(
                        null as com.intellij.openapi.project.Project?,
                        errorMessage ?: "Unknown error occurred",
                        "Refresh Failed"
                    )
                }
            }
        })
    }

    private fun reAuthenticate() {
        val selectedRow = accountsTable.selectedRow
        if (selectedRow < 0) {
            Messages.showWarningDialog(null, "Please select an account", "No Account Selected")
            return
        }

        val accountWithStatus = accountsModel.getAccountAt(selectedRow)
        val account = accountWithStatus.account
        val result = Messages.showYesNoDialog(
            null,
            "Re-authenticate with '${account.displayName}'?\nThis will open a browser window for authentication.",
            "Re-authenticate",
            Messages.getQuestionIcon()
        )

        if (result == Messages.YES) {
            // Remove old account and add new one through login flow
            accountManager.removeAccount(account.id)
            val dialog = AzureDevOpsLoginDialog(null)
            if (dialog.showAndGet()) {
                loadAccounts()
                Messages.showInfoMessage(
                    null as com.intellij.openapi.project.Project?,
                    "Successfully re-authenticated!",
                    "Authentication Complete"
                )
            } else {
                // Restore if cancelled? Or leave removed?
                Messages.showWarningDialog(
                    null as com.intellij.openapi.project.Project?,
                    "Authentication was cancelled. The account has been removed.",
                    "Cancelled"
                )
            }
        }
    }

    override fun isModified(): Boolean = false

    override fun apply() {
        // No-op: changes are applied immediately
    }

    override fun reset() {
        loadAccounts()
    }

    override fun disposeUIResources() {
        countdownTimer?.stop()
        countdownTimer = null
        mainPanel = null
    }

    /**
     * Data class for account with status information
     */
    data class AccountWithStatus(
        val account: AzureDevOpsAccount,
        val state: AzureDevOpsAccountManager.AccountAuthState,
        val expiresAt: Long,
        val lastRefreshed: Long
    )

    /**
     * Table model for accounts list
     */
    private inner class AccountsTableModel : AbstractTableModel() {
        private var accounts: List<AccountWithStatus> = emptyList()

        fun setAccounts(accountsList: List<AzureDevOpsAccount>) {
            accounts = accountsList.map { account ->
                val accountData = accountManager.getState().accounts.find { it.id == account.id }
                AccountWithStatus(
                    account = account,
                    state = accountManager.getAccountAuthState(account.id),
                    expiresAt = accountData?.expiresAt ?: 0,
                    lastRefreshed = accountData?.lastRefreshed ?: 0
                )
            }
            fireTableDataChanged()
        }

        fun getAccountAt(row: Int): AccountWithStatus = accounts[row]

        override fun getRowCount(): Int = accounts.size

        override fun getColumnCount(): Int = 4

        override fun getColumnName(column: Int): String = when (column) {
            0 -> "Account"
            1 -> "URL"
            2 -> "Status"
            3 -> "Expires"
            else -> ""
        }

        override fun getValueAt(rowIndex: Int, columnIndex: Int): Any {
            val item = accounts[rowIndex]
            return when (columnIndex) {
                0 -> item.account.displayName
                1 -> item.account.serverUrl
                2 -> item.state
                3 -> {
                    if (item.expiresAt > 0) {
                        val date = Date(item.expiresAt)
                        SimpleDateFormat("MMM dd, yyyy HH:mm").format(date)
                    } else {
                        "Unknown"
                    }
                }
                else -> ""
            }
        }
    }

    /**
     * Custom cell renderer for status column
     */
    private class AccountStatusCellRenderer : DefaultTableCellRenderer() {
        override fun getTableCellRendererComponent(
            table: JTable?,
            value: Any?,
            isSelected: Boolean,
            hasFocus: Boolean,
            row: Int,
            column: Int
        ): Component {
            val component = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column)

            if (column == 2 && value is AzureDevOpsAccountManager.AccountAuthState) {
                text = when (value) {
                    AzureDevOpsAccountManager.AccountAuthState.VALID -> "✓ Valid"
                    AzureDevOpsAccountManager.AccountAuthState.EXPIRED -> "⚠ Expired"
                    AzureDevOpsAccountManager.AccountAuthState.REVOKED -> "✗ Revoked"
                    AzureDevOpsAccountManager.AccountAuthState.UNKNOWN -> "? Unknown"
                }

                if (!isSelected) {
                    foreground = when (value) {
                        AzureDevOpsAccountManager.AccountAuthState.VALID ->
                            JBColor(0x008000, 0x00A000)   // green (light, dark)

                        AzureDevOpsAccountManager.AccountAuthState.EXPIRED ->
                            JBColor(0xFF8C00, 0xFFA040)   // orange

                        AzureDevOpsAccountManager.AccountAuthState.REVOKED ->
                            JBColor(0xC80000, 0xFF4040)   // red

                        AzureDevOpsAccountManager.AccountAuthState.UNKNOWN ->
                            JBColor(0x808080, 0xA0A0A0)   // gray
                    }
                }

            }

            return component
        }
    }
}
