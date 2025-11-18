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
import paol0b.azuredevops.AzureDevOpsIcons
import java.awt.BorderLayout
import java.awt.Dimension
import java.io.File
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Main clone dialog for Azure DevOps.
 * Shows account selection, projects/repos tree, and target directory.
 */
class AzureDevOpsCloneDialog(private val project: Project?) : DialogWrapper(project, true) {

    private val accountComboBox = JComboBox<AzureDevOpsAccount>()
    private val loginButton = JButton("Add Account...")
    private val tree: Tree
    private val treeModel: DefaultTreeModel
    private val rootNode: DefaultMutableTreeNode
    private val directoryField = TextFieldWithBrowseButton()
    
    private var selectedRepository: AzureDevOpsRepository? = null
    private val defaultCloneDir = System.getProperty("user.home") + File.separator + "AzureDevOpsProjects"

    init {
        title = "Clone Azure DevOps Repository"
        
        rootNode = DefaultMutableTreeNode("Azure DevOps")
        treeModel = DefaultTreeModel(rootNode)
        tree = Tree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = RepositoryTreeCellRenderer()
            border = JBUI.Borders.empty(5)
        }

        tree.addTreeSelectionListener {
            val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val userObject = selectedNode?.userObject
            
            if (userObject is AzureDevOpsRepository) {
                selectedRepository = userObject
                
                // Auto-fill directory
                val targetDir = File(defaultCloneDir, userObject.name).absolutePath
                directoryField.text = targetDir
            } else {
                selectedRepository = null
            }
        }

        loginButton.addActionListener {
            showLoginDialog()
        }

        accountComboBox.addActionListener {
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
        val panel = JPanel(BorderLayout())
        
        // Account selection panel
        val accountPanel = JPanel(BorderLayout(5, 0)).apply {
            add(JBLabel("Account:"), BorderLayout.WEST)
            add(accountComboBox, BorderLayout.CENTER)
            add(loginButton, BorderLayout.EAST)
            border = JBUI.Borders.empty(0, 0, 10, 0)
        }

        // Tree panel
        val treePanel = JPanel(BorderLayout()).apply {
            add(JBLabel("Select Repository:"), BorderLayout.NORTH)
            add(JBScrollPane(tree), BorderLayout.CENTER)
            border = JBUI.Borders.empty(5, 0)
            preferredSize = Dimension(600, 400)
        }

        // Directory panel
        val directoryPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Directory:"), directoryField, 1, false)
            .panel

        panel.add(accountPanel, BorderLayout.NORTH)
        panel.add(treePanel, BorderLayout.CENTER)
        panel.add(directoryPanel, BorderLayout.SOUTH)
        
        panel.border = JBUI.Borders.empty(10)
        panel.preferredSize = Dimension(700, 550)

        return panel
    }

    override fun doValidate(): ValidationInfo? {
        if (selectedRepository == null) {
            return ValidationInfo("Please select a repository to clone", tree)
        }

        val directory = directoryField.text.trim()
        if (directory.isBlank()) {
            return ValidationInfo("Please specify a target directory", directoryField)
        }

        return null
    }

    fun getSelectedRepository(): AzureDevOpsRepository? = selectedRepository

    fun getTargetDirectory(): String = directoryField.text.trim()

    private fun loadAccounts() {
        val accountManager = AzureDevOpsAccountManager.getInstance()
        val accounts = accountManager.getAccounts()
        
        accountComboBox.removeAllItems()
        accounts.forEach { accountComboBox.addItem(it) }

        if (accounts.isEmpty()) {
            showLoginDialog()
        } else {
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
                    indicator.text = "Fetching projects..."
                    val apiClient = AzureDevOpsCloneApiClient(account.serverUrl, token)
                    val projects = apiClient.getProjects()

                    ApplicationManager.getApplication().invokeLater {
                        projects.forEach { proj ->
                            val projectNode = DefaultMutableTreeNode(proj)
                            rootNode.add(projectNode)
                            
                            // Add loading placeholder
                            projectNode.add(DefaultMutableTreeNode("Loading..."))
                        }
                        treeModel.reload()
                        
                        // Expand and load repositories for each project
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
                    val apiClient = AzureDevOpsCloneApiClient(account.serverUrl, token)
                    val repositories = apiClient.getRepositories(project.id)

                    ApplicationManager.getApplication().invokeLater {
                        // Find project node
                        for (i in 0 until rootNode.childCount) {
                            val node = rootNode.getChildAt(i) as DefaultMutableTreeNode
                            val nodeProject = node.userObject as? AzureDevOpsCloneApiClient.Project
                            
                            if (nodeProject?.id == project.id) {
                                node.removeAllChildren()
                                
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
                                
                                treeModel.reload(node)
                                tree.expandPath(TreePath(node.path))
                                break
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Log error
                }
            }
        })
    }

    /**
     * Custom tree cell renderer with Azure DevOps icons
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
                    icon = AllIcons.Nodes.Folder
                    append(userObject.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    userObject.description?.let {
                        append(" - $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
                is AzureDevOpsRepository -> {
                    icon = AllIcons.Vcs.Vendors.Github  // Using git icon
                    append(userObject.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
                is String -> {
                    icon = if (userObject.startsWith("Error")) {
                        AllIcons.General.Error
                    } else {
                        AllIcons.Process.Step_1
                    }
                    append(userObject, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
            }
        }
    }
}
