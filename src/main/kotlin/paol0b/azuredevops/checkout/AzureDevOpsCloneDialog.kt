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
 * Enhanced clone dialog for Azure DevOps.
 * Shows account selection, projects/repos tree with icons, and target directory.
 * Supports OAuth and PAT authentication with global credential storage.
 */
class AzureDevOpsCloneDialog(private val project: Project?) : DialogWrapper(project, true) {

    private val accountComboBox = JComboBox<AzureDevOpsAccount>()
    private val loginButton = JButton("Add Account...").apply {
        icon = AllIcons.General.Add
    }
    private val tree: Tree
    private val treeModel: DefaultTreeModel
    private val rootNode: DefaultMutableTreeNode
    private val directoryField = TextFieldWithBrowseButton()
    
    private var selectedRepository: AzureDevOpsRepository? = null
    private var selectedAccount: AzureDevOpsAccount? = null
    private val defaultCloneDir = System.getProperty("user.home") + File.separator + "AzureDevOpsProjects"

    init {
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

        accountComboBox.addActionListener {
            selectedAccount = accountComboBox.selectedItem as? AzureDevOpsAccount
            loadRepositories()
        }

        val fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        directoryField.addBrowseFolderListener(
            TextBrowseFolderListener(fileChooserDescriptor, project)
        )
        
        directoryField.text = defaultCloneDir

        init()
        loadAccounts()
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
                add(loginButton, BorderLayout.EAST)
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

    private fun loadAccounts() {
        val accountManager = AzureDevOpsAccountManager.getInstance()
        val accounts = accountManager.getAccounts()
        
        accountComboBox.removeAllItems()
        accounts.forEach { accountComboBox.addItem(it) }

        if (accounts.isEmpty()) {
            showLoginDialog()
        } else {
            selectedAccount = accounts.firstOrNull()
            loadRepositories()
        }
    }

    private fun showLoginDialog() {
        val loginDialog = AzureDevOpsLoginDialog(project)
        if (loginDialog.showAndGet()) {
            loadAccounts()
        }
    }

    private fun loadRepositories() {
        val account = accountComboBox.selectedItem as? AzureDevOpsAccount ?: return
        selectedAccount = account
        val accountManager = AzureDevOpsAccountManager.getInstance()
        val token = accountManager.getToken(account.id) ?: return

        rootNode.removeAllChildren()
        treeModel.reload()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Loading Azure DevOps Repositories...",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Fetching projects from ${account.displayName}..."
                    indicator.isIndeterminate = true
                    
                    val apiClient = AzureDevOpsCloneApiClient(account.serverUrl, token)
                    val projects = apiClient.getProjects()

                    ApplicationManager.getApplication().invokeLater {
                        if (projects.isEmpty()) {
                            val emptyNode = DefaultMutableTreeNode("No projects found")
                            rootNode.add(emptyNode)
                        } else {
                            projects.forEach { proj ->
                                val projectNode = DefaultMutableTreeNode(proj)
                                rootNode.add(projectNode)
                                
                                // Add loading placeholder
                                val loadingNode = DefaultMutableTreeNode("Loading repositories...")
                                projectNode.add(loadingNode)
                            }
                        }
                        treeModel.reload()
                        
                        // Load repositories for each project
                        projects.forEach { proj ->
                            loadProjectRepositories(account, token, proj)
                        }
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        val errorNode = DefaultMutableTreeNode("Error: ${e.message}")
                        rootNode.add(errorNode)
                        treeModel.reload()
                    }
                }
            }
        })
    }

    private fun loadProjectRepositories(
        account: AzureDevOpsAccount,
        token: String,
        project: AzureDevOpsCloneApiClient.Project
    ) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            this.project,
            "Loading repositories for ${project.name}...",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Fetching repositories from ${project.name}..."
                    
                    val apiClient = AzureDevOpsCloneApiClient(account.serverUrl, token)
                    val repositories = apiClient.getRepositories(project.id)

                    ApplicationManager.getApplication().invokeLater {
                        // Find project node
                        for (i in 0 until rootNode.childCount) {
                            val node = rootNode.getChildAt(i) as DefaultMutableTreeNode
                            val nodeProject = node.userObject as? AzureDevOpsCloneApiClient.Project
                            
                            if (nodeProject?.id == project.id) {
                                node.removeAllChildren()
                                
                                if (repositories.isEmpty()) {
                                    val emptyNode = DefaultMutableTreeNode("No repositories found")
                                    node.add(emptyNode)
                                } else {
                                    repositories.forEach { repo ->
                                        val repoObj = AzureDevOpsRepository(
                                            id = repo.id,
                                            name = repo.name,
                                            projectName = project.name,
                                            remoteUrl = repo.remoteUrl,
                                            webUrl = repo.webUrl
                                        )
                                        node.add(DefaultMutableTreeNode(repoObj))
                                    }
                                }
                                
                                treeModel.reload(node)
                                tree.expandPath(TreePath(node.path))
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Log error but don't show to user - project node will show loading state
                }
            }
        })
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
