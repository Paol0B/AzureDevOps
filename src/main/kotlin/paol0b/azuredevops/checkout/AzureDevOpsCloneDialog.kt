package paol0b.azuredevops.checkout

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.AzureDevOpsIcons
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.io.File
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

/**
 * Data class for preloaded projects and repositories
 */
data class ProjectsData(
    val projects: List<AzureDevOpsCloneApiClient.Project>,
    val repositories: Map<String, List<AzureDevOpsCloneApiClient.Repository>>
)

/**
 * Enhanced clone dialog for Azure DevOps.
 * Shows account selection, projects/repos tree with icons, and target directory.
 * Supports OAuth and PAT authentication with global credential storage.
 */
class AzureDevOpsCloneDialog private constructor(
    private val project: Project?,
    preloadedData: Map<String, ProjectsData>?
) : DialogWrapper(project, true) {

    private val accountComboBox = JComboBox<AzureDevOpsAccount>()
    private val loginButton = JButton("Add Account...").apply {
        icon = AllIcons.General.Add
    }
    private val removeButton = JButton("Remove").apply {
        icon = AllIcons.General.Remove
        toolTipText = "Remove selected account"
    }
    private val tree: Tree
    private val treeModel: DefaultTreeModel
    private val rootNode: DefaultMutableTreeNode
    private val directoryField = TextFieldWithBrowseButton()
    
    private var selectedRepository: AzureDevOpsRepository? = null
    private var selectedAccount: AzureDevOpsAccount? = null
    private var isLoadingAccounts = false  // Flag to prevent duplicate loads
    private val defaultCloneDir = System.getProperty("user.home") + File.separator + "AzureDevOpsProjects"
    
    // Preloaded data
    private var preloadedData: Map<String, ProjectsData>? = null

    companion object {
        /**
         * Factory method to create dialog with preloaded data.
         * Preloads all projects and repositories for each account before showing the dialog.
         */
        fun create(project: Project?): AzureDevOpsCloneDialog? {
            val accountManager = AzureDevOpsAccountManager.getInstance()
            val accounts = accountManager.getAccounts()
            
            // If no accounts, show login first
            if (accounts.isEmpty()) {
                val loginDialog = AzureDevOpsLoginDialog(project)
                if (!loginDialog.showAndGet()) {
                    return null
                }
                // Reload accounts after login
                val updatedAccounts = accountManager.getAccounts()
                if (updatedAccounts.isEmpty()) {
                    return null
                }
            }
            
            // Preload data for all accounts
            var preloadedData: Map<String, ProjectsData>? = null
            var error: String? = null
            
            ProgressManager.getInstance().run(object : Task.Modal(project, "Loading Azure DevOps Repositories...", true) {
                override fun run(indicator: ProgressIndicator) {
                    val dataMap = mutableMapOf<String, ProjectsData>()
                    val currentAccounts = accountManager.getAccounts()
                    
                    if (currentAccounts.isEmpty()) {
                        return
                    }
                    
                    indicator.isIndeterminate = false
                    val accountCount = currentAccounts.size
                    
                    currentAccounts.forEachIndexed { index, account ->
                        try {
                            indicator.text = "Loading repositories for ${account.displayName}..."
                            indicator.fraction = index.toDouble() / accountCount
                            
                            val token = accountManager.getToken(account.id)
                            if (token != null) {
                                val apiClient = AzureDevOpsCloneApiClient(account.serverUrl, token)
                                val projects = apiClient.getProjects()
                                
                                val repoMap = mutableMapOf<String, List<AzureDevOpsCloneApiClient.Repository>>()
                                projects.forEachIndexed { projIndex, proj ->
                                    indicator.text2 = "Loading ${proj.name}..."
                                    indicator.fraction = (index.toDouble() + (projIndex.toDouble() / projects.size)) / accountCount
                                    
                                    try {
                                        val repos = apiClient.getRepositories(proj.id)
                                        repoMap[proj.id] = repos
                                    } catch (e: Exception) {
                                        // Log and continue
                                        repoMap[proj.id] = emptyList()
                                    }
                                }
                                
                                dataMap[account.id] = ProjectsData(projects, repoMap)
                            }
                        } catch (e: Exception) {
                            error = "Error loading repositories for ${account.displayName}: ${e.message}"
                        }
                    }
                    
                    indicator.fraction = 1.0
                    preloadedData = dataMap
                }
            })
            
            if (error != null) {
                com.intellij.openapi.ui.Messages.showErrorDialog(
                    project,
                    error,
                    "Error Loading Repositories"
                )
            }
            
            return AzureDevOpsCloneDialog(project, preloadedData)
        }
    }

    init {
        this.preloadedData = preloadedData
        title = "Clone from Azure DevOps"
        
        rootNode = DefaultMutableTreeNode("Azure DevOps")
        treeModel = DefaultTreeModel(rootNode)
        tree = Tree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            cellRenderer = RepositoryTreeCellRenderer()
            border = JBUI.Borders.empty(5)
        }

        tree.addTreeSelectionListener {
            val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val userObject = selectedNode?.userObject
            
            if (userObject is AzureDevOpsRepository) {
                selectedRepository = userObject
                
                // Auto-fill directory with repository name
                val targetDir = File(directoryField.text.trim().ifBlank { defaultCloneDir }, userObject.name).absolutePath
                directoryField.text = targetDir
            } else {
                selectedRepository = null
            }
        }

        loginButton.addActionListener {
            showLoginDialog()
        }

        removeButton.addActionListener {
            removeSelectedAccount()
        }

        accountComboBox.addActionListener {
            // Only load repositories if this is a user-triggered change, not programmatic
            if (!isLoadingAccounts) {
                selectedAccount = accountComboBox.selectedItem as? AzureDevOpsAccount
                removeButton.isEnabled = selectedAccount != null
                loadRepositoriesFromPreloadedData()
            }
        }

        val fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        directoryField.addBrowseFolderListener(
            TextBrowseFolderListener(fileChooserDescriptor, project)
        )
        
        directoryField.text = defaultCloneDir

        // Initially disable remove button
        removeButton.isEnabled = false

        init()
        loadAccountsWithPreloadedData()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10))
        
        // Header with Azure DevOps branding
        val headerPanel = JPanel(BorderLayout()).apply {
            val titleLabel = JBLabel("Clone Repository from Azure DevOps").apply {
                font = font.deriveFont(Font.BOLD, 14f)
                icon = AzureDevOpsIcons.Logo
            }
            add(titleLabel, BorderLayout.WEST)
            border = JBUI.Borders.empty(0, 0, 10, 0)
        }
        
        // Account selection panel with improved layout
        val accountPanel = JPanel(BorderLayout(10, 0)).apply {
            val labelPanel = JPanel(BorderLayout()).apply {
                add(JBLabel("Account:").apply {
                    font = font.deriveFont(Font.BOLD)
                }, BorderLayout.WEST)
            }
            
            val comboPanel = JPanel(BorderLayout(5, 0)).apply {
                add(accountComboBox, BorderLayout.CENTER)
                
                val buttonPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.X_AXIS)
                    add(removeButton)
                    add(Box.createHorizontalStrut(5))
                    add(loginButton)
                }
                add(buttonPanel, BorderLayout.EAST)
            }
            
            add(labelPanel, BorderLayout.WEST)
            add(comboPanel, BorderLayout.CENTER)
            border = JBUI.Borders.empty(5, 0, 10, 0)
        }

        // Tree panel with enhanced styling
        val treePanel = JPanel(BorderLayout()).apply {
            val treeLabel = JBLabel("Select a Repository:").apply {
                font = font.deriveFont(Font.BOLD)
                border = JBUI.Borders.empty(0, 0, 5, 0)
            }
            
            val scrollPane = JBScrollPane(tree).apply {
                border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1)
            }
            
            add(treeLabel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            preferredSize = Dimension(650, 400)
        }

        // Directory panel with improved layout
        val directoryPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(
                JBLabel("Directory:").apply { font = font.deriveFont(Font.BOLD) }, 
                directoryField, 
                1, 
                false
            )
            .panel.apply {
                border = JBUI.Borders.empty(10, 0, 0, 0)
            }

        panel.add(headerPanel, BorderLayout.NORTH)
        
        val centerPanel = JPanel(BorderLayout()).apply {
            add(accountPanel, BorderLayout.NORTH)
            add(treePanel, BorderLayout.CENTER)
        }
        panel.add(centerPanel, BorderLayout.CENTER)
        panel.add(directoryPanel, BorderLayout.SOUTH)

        panel.border = JBUI.Borders.empty(10)
        panel.preferredSize = Dimension(750, 600)

        return panel
    }    override fun doValidate(): ValidationInfo? {
        if (selectedRepository == null) {
            return ValidationInfo("Please select a repository to clone", tree)
        }

        val directory = directoryField.text.trim()
        if (directory.isBlank()) {
            return ValidationInfo("Please specify a target directory", directoryField)
        }

        val targetDir = File(directory)
        if (targetDir.exists()) {
            return ValidationInfo("Directory already exists: $directory", directoryField)
        }

        return null
    }

    fun getSelectedRepository(): AzureDevOpsRepository? = selectedRepository
    
    fun getSelectedAccount(): AzureDevOpsAccount? = selectedAccount

    fun getTargetDirectory(): String = directoryField.text.trim()

    private fun loadAccountsWithPreloadedData() {
        val accountManager = AzureDevOpsAccountManager.getInstance()
        val accounts = accountManager.getAccounts()
        
        // Prevent ActionListener from triggering during programmatic update
        isLoadingAccounts = true
        accountComboBox.removeAllItems()
        accounts.forEach { accountComboBox.addItem(it) }
        isLoadingAccounts = false

        if (accounts.isNotEmpty()) {
            accountComboBox.selectedIndex = 0
            selectedAccount = accounts.firstOrNull()
            removeButton.isEnabled = true
            loadRepositoriesFromPreloadedData()
        }
    }

    private fun loadAccounts() {
        val accountManager = AzureDevOpsAccountManager.getInstance()
        val accounts = accountManager.getAccounts()
        
        isLoadingAccounts = true  // Prevent ActionListener from triggering
        accountComboBox.removeAllItems()
        accounts.forEach { accountComboBox.addItem(it) }
        isLoadingAccounts = false

        if (accounts.isEmpty()) {
            showLoginDialog()
        } else {
            selectedAccount = accounts.firstOrNull()
            removeButton.isEnabled = selectedAccount != null
            loadRepositoriesFromPreloadedData()
        }
    }

    private fun showLoginDialog() {
        val loginDialog = AzureDevOpsLoginDialog(project)
        if (loginDialog.showAndGet()) {
            // Reload with new account - need to fetch data
            val accountManager = AzureDevOpsAccountManager.getInstance()
            val newAccount = accountManager.getAccounts().lastOrNull()
            if (newAccount != null) {
                loadNewAccountData(newAccount)
            }
        }
    }
    
    private fun loadNewAccountData(account: AzureDevOpsAccount) {
        ProgressManager.getInstance().run(object : Task.Modal(
            project,
            "Loading Repositories for ${account.displayName}...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    val accountManager = AzureDevOpsAccountManager.getInstance()
                    val token = accountManager.getToken(account.id)
                    
                    if (token != null) {
                        indicator.text = "Fetching projects..."
                        indicator.isIndeterminate = false
                        
                        val apiClient = AzureDevOpsCloneApiClient(account.serverUrl, token)
                        val projects = apiClient.getProjects()
                        
                        val repoMap = mutableMapOf<String, List<AzureDevOpsCloneApiClient.Repository>>()
                        projects.forEachIndexed { index, proj ->
                            indicator.text = "Loading ${proj.name}..."
                            indicator.fraction = index.toDouble() / projects.size
                            
                            try {
                                val repos = apiClient.getRepositories(proj.id)
                                repoMap[proj.id] = repos
                            } catch (e: Exception) {
                                repoMap[proj.id] = emptyList()
                            }
                        }
                        
                        indicator.fraction = 1.0
                        
                        // Update preloaded data
                        val mutableData = preloadedData?.toMutableMap() ?: mutableMapOf()
                        mutableData[account.id] = ProjectsData(projects, repoMap)
                        preloadedData = mutableData
                        
                        ApplicationManager.getApplication().invokeLater {
                            isLoadingAccounts = true
                            accountComboBox.removeAllItems()
                            accountManager.getAccounts().forEach { accountComboBox.addItem(it) }
                            accountComboBox.selectedItem = account
                            isLoadingAccounts = false
                            loadRepositoriesFromPreloadedData()
                        }
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        com.intellij.openapi.ui.Messages.showErrorDialog(
                            project,
                            "Error loading repositories: ${e.message}",
                            "Error"
                        )
                    }
                }
            }
        })
    }

    private fun removeSelectedAccount() {
        val account = accountComboBox.selectedItem as? AzureDevOpsAccount ?: return
        
        val confirmed = com.intellij.openapi.ui.Messages.showYesNoDialog(
            project,
            "Are you sure you want to remove the account for '${account.serverUrl}'?\n\n" +
            "This will delete the stored credentials for this account.",
            "Remove Account",
            "Remove",
            "Cancel",
            com.intellij.openapi.ui.Messages.getWarningIcon()
        )
        
        if (confirmed == com.intellij.openapi.ui.Messages.YES) {
            val accountManager = AzureDevOpsAccountManager.getInstance()
            accountManager.removeAccount(account.id)
            
            // Remove from preloaded data
            val mutableData = preloadedData?.toMutableMap() ?: mutableMapOf()
            mutableData.remove(account.id)
            preloadedData = mutableData
            
            // Clear tree and reload accounts
            rootNode.removeAllChildren()
            treeModel.reload()
            
            loadAccountsWithPreloadedData()
            
            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                "Account removed successfully.",
                "Account Removed"
            )
        }
    }

    private fun loadRepositoriesFromPreloadedData() {
        val account = accountComboBox.selectedItem as? AzureDevOpsAccount ?: return
        selectedAccount = account
        
        rootNode.removeAllChildren()
        
        val data = preloadedData?.get(account.id)
        if (data == null) {
            val errorNode = DefaultMutableTreeNode("No data available for this account.")
            rootNode.add(errorNode)
            treeModel.reload()
            return
        }
        
        if (data.projects.isEmpty()) {
            val emptyNode = DefaultMutableTreeNode("No projects found for this account")
            rootNode.add(emptyNode)
            treeModel.reload()
            return
        }
        
        // Build tree from preloaded data
        data.projects.forEach { proj ->
            val projectNode = DefaultMutableTreeNode(proj)
            rootNode.add(projectNode)
            
            val repos = data.repositories[proj.id] ?: emptyList()
            repos.forEach { repo ->
                val repoObj = AzureDevOpsRepository(
                    id = repo.id,
                    name = repo.name,
                    projectName = proj.name,
                    remoteUrl = repo.remoteUrl,
                    webUrl = repo.webUrl
                )
                projectNode.add(DefaultMutableTreeNode(repoObj))
            }
        }
        
        treeModel.reload()
        
        // Expand first project
        if (rootNode.childCount > 0) {
            tree.expandPath(TreePath(arrayOf(rootNode, rootNode.getChildAt(0))))
        }
    }

    /**
     * Enhanced tree cell renderer with proper Azure DevOps icons
     */
    private class RepositoryTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree,
            value: Any?,
            selected: Boolean,
            expanded: Boolean,
            leaf: Boolean,
            row: Int,
            hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            val userObject = node.userObject

            when (userObject) {
                is AzureDevOpsCloneApiClient.Project -> {
                    icon = AzureDevOpsIcons.Project
                    append(userObject.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    userObject.description?.let {
                        if (it.isNotBlank()) {
                            append(" - $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                        }
                    }
                }
                is AzureDevOpsRepository -> {
                    icon = AzureDevOpsIcons.Repository
                    append(userObject.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append(userObject.projectName, SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                }
                is String -> {
                    icon = if (userObject.startsWith("Error")) {
                        AllIcons.General.Error
                    } else if (userObject.contains("Loading")) {
                        AllIcons.Process.Step_1
                    } else if (userObject.contains("No ")) {
                        AllIcons.General.Information
                    } else {
                        AllIcons.Process.Step_1
                    }
                    append(userObject, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
            }
        }
    }
}
