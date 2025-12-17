package paol0b.azuredevops.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages
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
    private val accountManager = AzureDevOpsAccountManager.getInstance()
    private val oauthService = AzureDevOpsOAuthService.getInstance()

    override fun getDisplayName(): String = "Azure DevOps Accounts"

    override fun createComponent(): JComponent {
        accountsModel = AccountsTableModel()
        accountsTable = JBTable(accountsModel).apply {
            setDefaultRenderer(Any::class.java, AccountStatusCellRenderer())
            setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        }

        val decorator = ToolbarDecorator.createDecorator(accountsTable)
            .setAddAction { addAccount() }
            .setRemoveAction { removeAccount() }
            .addExtraAction(object : ToolbarDecorator.ElementActionButton("Refresh Token", 
                "Refresh the authentication token for selected account", 
                AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: java.awt.event.ActionEvent) {
                    refreshToken()
                }
            })
            .addExtraAction(object : ToolbarDecorator.ElementActionButton("Re-login", 
                "Re-authenticate with selected account", 
                AllIcons.Actions.Execute) {
                override fun actionPerformed(e: java.awt.event.ActionEvent) {
                    reAuthenticate()
                }
            })
            .setPreferredSize(JBUI.size(600, 300))

        val decoratorPanel = decorator.createPanel()

        val infoLabel = JBLabel("<html><b>Global Accounts</b><br>" +
            "<font size='-1' color='gray'>Manage your Azure DevOps accounts. " +
            "Accounts are stored globally and available across all projects.</font></html>")

        mainPanel = JPanel(BorderLayout()).apply {
            add(infoLabel, BorderLayout.NORTH)
            add(decoratorPanel, BorderLayout.CENTER)
            border = JBUI.Borders.empty(10)
        }

        loadAccounts()

        return mainPanel!!
    }

    private fun loadAccounts() {
        accountsModel.setAccounts(accountManager.getAccounts())
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
            val account = accountsModel.getAccountAt(selectedRow)
            val result = Messages.showYesNoDialog(
                mainPanel,
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
            Messages.showWarningDialog(mainPanel, "Please select an account", "No Account Selected")
            return
        }

        val account = accountsModel.getAccountAt(selectedRow)
        val refreshToken = accountManager.getRefreshToken(account.id)

        if (refreshToken == null) {
            Messages.showWarningDialog(
                mainPanel,
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
                    Messages.showInfoMessage(
                        mainPanel,
                        "Access token refreshed successfully for '${account.displayName}'",
                        "Token Refreshed"
                    )
                    loadAccounts()
                } else {
                    Messages.showErrorDialog(
                        mainPanel,
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
            Messages.showWarningDialog(mainPanel, "Please select an account", "No Account Selected")
            return
        }

        val account = accountsModel.getAccountAt(selectedRow)
        val result = Messages.showYesNoDialog(
            mainPanel,
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
                    mainPanel,
                    "Successfully re-authenticated!",
                    "Authentication Complete"
                )
            } else {
                // Restore if cancelled? Or leave removed?
                Messages.showWarningDialog(
                    mainPanel,
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
        mainPanel = null
    }

    /**
     * Table model for accounts list
     */
    private inner class AccountsTableModel : AbstractTableModel() {
        private var accounts: List<AccountWithStatus> = emptyList()

        data class AccountWithStatus(
            val account: AzureDevOpsAccount,
            val state: AzureDevOpsAccountManager.AccountAuthState,
            val expiresAt: Long,
            val lastRefreshed: Long
        )

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

        fun getAccountAt(row: Int): AzureDevOpsAccount = accounts[row].account

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
                        AzureDevOpsAccountManager.AccountAuthState.VALID -> java.awt.Color(0, 128, 0)
                        AzureDevOpsAccountManager.AccountAuthState.EXPIRED -> java.awt.Color(255, 140, 0)
                        AzureDevOpsAccountManager.AccountAuthState.REVOKED -> java.awt.Color(200, 0, 0)
                        AzureDevOpsAccountManager.AccountAuthState.UNKNOWN -> java.awt.Color(128, 128, 128)
                    }
                }
            }

            return component
        }
    }
}
