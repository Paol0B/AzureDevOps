package paol0b.azuredevops.checkout

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vcs.CheckoutProvider
import com.intellij.openapi.vcs.ui.cloneDialog.VcsCloneDialogExtensionComponent
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import git4idea.GitVcs
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import paol0b.azuredevops.AzureDevOpsIcons
import paol0b.azuredevops.services.GitTokenManager
import paol0b.azuredevops.util.NotificationUtil
import java.awt.BorderLayout
import java.awt.FlowLayout
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreeSelectionModel

/**
 * Normalizes an Azure DevOps remote URL by fully decoding it then re-encoding
 * each path segment so that special characters (e.g. spaces) are handled
 * consistently by git.
 */
fun normalizeAzureDevOpsUrl(url: String): String {
    return try {
        var decodedUrl = url
        var previousUrl: String

        do {
            previousUrl = decodedUrl
            decodedUrl = URLDecoder.decode(previousUrl, StandardCharsets.UTF_8)
        } while (decodedUrl != previousUrl)

        val uri = URI(decodedUrl)
        val scheme = uri.scheme ?: "https"
        val host = uri.host ?: return url

        val path = uri.path ?: return url

        val segments = path.split("/").filter { it.isNotEmpty() }
        val encodedPath = segments.joinToString("/") { segment ->
            URLEncoder.encode(segment, StandardCharsets.UTF_8)
                .replace("+", "%20")
        }

        "$scheme://$host/$encodedPath"
    } catch (e: Exception) {
        url
    }
}

/**
 * Main component for Azure DevOps in the Clone Repository dialog.
 * Matches GitHub/GitLab style: account at top, tree in middle, clone options at bottom.
 */
class AzureDevOpsCloneDialogComponent(private val project: Project) : VcsCloneDialogExtensionComponent() {

    private val logger = Logger.getInstance(AzureDevOpsCloneDialogComponent::class.java)

    // Account selection
    private val accountComboBox = JComboBox<AzureDevOpsAccount>()
    private val addAccountButton = JButton(AllIcons.General.Add).apply {
        toolTipText = "Add Account"
        isBorderPainted = false
        isContentAreaFilled = false
    }

    // Search and tree
    private val searchField = SearchTextField(false)
    private val tree: Tree
    private val treeModel: DefaultTreeModel
    private val rootNode: DefaultMutableTreeNode

    // Clone options (bottom)
    private val directoryField = TextFieldWithBrowseButton()
    private val shallowCloneCheckbox = JCheckBox("Shallow clone with a history truncated to")
    private val shallowCloneDepthField = JTextField("1", 5)
    private val commitsLabel = JBLabel("commits")

    private var preloadedData: MutableMap<String, ProjectsData> = mutableMapOf()
    private var selectedRepository: AzureDevOpsRepository? = null
    private var selectedAccount: AzureDevOpsAccount? = null
    private var isLoadingAccounts = false

    private val defaultCloneDir = com.intellij.ide.impl.ProjectUtil.getBaseDir()
    private var baseCloneDir = defaultCloneDir

    private val mainPanel: JPanel

    init {
        // Setup account combo box with custom renderer
        accountComboBox.renderer = object : ColoredListCellRenderer<AzureDevOpsAccount>() {
            override fun customizeCellRenderer(
                list: JList<out AzureDevOpsAccount>,
                value: AzureDevOpsAccount?,
                index: Int,
                selected: Boolean,
                hasFocus: Boolean
            ) {
                if (value != null) {
                    icon = AzureDevOpsIcons.Logo
                    append(value.displayName)
                }
            }
        }

        // Setup tree
        rootNode = DefaultMutableTreeNode("Azure DevOps")
        treeModel = DefaultTreeModel(rootNode)
        tree = Tree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            selectionModel.selectionMode = TreeSelectionModel.SINGLE_TREE_SELECTION
            cellRenderer = RepositoryTreeCellRenderer()
            border = JBUI.Borders.empty(4)
        }

        tree.addTreeSelectionListener {
            val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val userObject = selectedNode?.userObject

            if (userObject is AzureDevOpsRepository) {
                selectedRepository = userObject
                // Update directory with repo name
                val repoDir = File(baseCloneDir, userObject.name).absolutePath
                directoryField.text = repoDir
            } else {
                selectedRepository = null
            }
            // Notify dialog of state change to enable/disable Clone button
            notifyDialogStateChanged()
        }

        // Search field listener
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                filterTree()
            }
        })

        addAccountButton.addActionListener {
            showLoginDialog()
        }

        accountComboBox.addActionListener {
            if (!isLoadingAccounts) {
                selectedAccount = accountComboBox.selectedItem as? AzureDevOpsAccount
                loadRepositoriesForCurrentAccount()
            }
        }

        // Directory field setup
        val fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        directoryField.addBrowseFolderListener(
            TextBrowseFolderListener(fileChooserDescriptor, project)
        )
        directoryField.text = defaultCloneDir

        // Listen for directory changes to trigger validation
        directoryField.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                notifyDialogStateChanged()
            }
        })

        // Shallow clone setup
        shallowCloneDepthField.isEnabled = false
        shallowCloneCheckbox.addActionListener {
            shallowCloneDepthField.isEnabled = shallowCloneCheckbox.isSelected
        }

        mainPanel = createMainPanel()
    }

    private fun createMainPanel(): JPanel {
        val panel = JPanel(BorderLayout())

        // === TOP: Account selection with logo ===
        val accountPanel = JPanel(BorderLayout(8, 0)).apply {
            border = JBUI.Borders.empty(0, 0, 8, 0)

            // Account combo + add button
            val accountRow = JPanel(BorderLayout(4, 0)).apply {
                add(accountComboBox, BorderLayout.CENTER)
                add(addAccountButton, BorderLayout.EAST)
            }
            add(accountRow, BorderLayout.CENTER)
        }

        // === MIDDLE: Search + Tree ===
        val centerPanel = JPanel(BorderLayout(0, 8)).apply {
            // Search at top
            add(searchField, BorderLayout.NORTH)

            // Tree in scroll pane
            val scrollPane = JBScrollPane(tree).apply {
                border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1)
            }
            add(scrollPane, BorderLayout.CENTER)
        }

        // === BOTTOM: Clone options ===
        val bottomPanel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8, 0, 0, 0)

            // Directory row
            val directoryRow = JPanel(BorderLayout(8, 0)).apply {
                add(JBLabel("Directory:"), BorderLayout.WEST)
                add(directoryField, BorderLayout.CENTER)
            }
            add(directoryRow, BorderLayout.NORTH)

            // Shallow clone row
            val shallowRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(shallowCloneCheckbox)
                add(shallowCloneDepthField)
                add(commitsLabel)
            }
            add(shallowRow, BorderLayout.SOUTH)
        }

        panel.add(accountPanel, BorderLayout.NORTH)
        panel.add(centerPanel, BorderLayout.CENTER)
        panel.add(bottomPanel, BorderLayout.SOUTH)

        panel.border = JBUI.Borders.empty(8)

        return panel
    }

    override fun getView(): JComponent = mainPanel

    override fun doClone(checkoutListener: CheckoutProvider.Listener) {
        val repo = selectedRepository ?: return
        val account = selectedAccount ?: return
        val targetDirectory = directoryField.text.trim()

        val cloneUrl = normalizeAzureDevOpsUrl(repo.remoteUrl)
        val token = AzureDevOpsAccountManager.getInstance().getToken(account.id)

        val isShallowClone = shallowCloneCheckbox.isSelected
        val shallowDepth = shallowCloneDepthField.text.toIntOrNull() ?: 1

        ProgressManager.getInstance().run(object : Task.Modal(
            project,
            "Cloning ${repo.name} from Azure DevOps...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Cloning repository from Azure DevOps..."
                    indicator.text2 = cloneUrl
                    indicator.isIndeterminate = false
                    indicator.fraction = 0.0

                    val checkoutDir = File(targetDirectory)
                    checkoutDir.parentFile?.mkdirs()

                    val handler = GitLineHandler(project, checkoutDir.parentFile, GitCommand.CLONE)

                    if (token != null) {
                        logger.info("Configuring git http.extraHeader with token")
                        val authHeader = "Authorization: Basic " + java.util.Base64.getEncoder()
                            .encodeToString(":$token".toByteArray(StandardCharsets.UTF_8))
                        handler.addParameters("-c", "http.extraHeader=$authHeader")
                    }

                    handler.addParameters("--progress")

                    // Add shallow clone option if enabled
                    if (isShallowClone) {
                        handler.addParameters("--depth", shallowDepth.toString())
                    }

                    handler.addParameters(cloneUrl)
                    handler.addParameters(checkoutDir.name)

                    handler.addLineListener { line, _ ->
                        indicator.text2 = line
                        val progressMatch = Regex("""(\d+)%""").find(line)
                        if (progressMatch != null) {
                            val progress = progressMatch.groupValues[1].toIntOrNull()
                            if (progress != null) {
                                indicator.fraction = progress / 100.0
                            }
                        }
                    }

                    indicator.fraction = 0.1
                    val result = Git.getInstance().runCommand(handler)
                    indicator.fraction = 1.0

                    if (result.success()) {
                        // Persist auth token directly in the repo's local git config so that
                        // all subsequent git operations (pull, push, fetch) are authenticated
                        // without relying on the OS credential store, and so the token can be
                        // refreshed automatically when it expires.
                        val gitTokenManager = GitTokenManager.getInstance()
                        gitTokenManager.registerRepo(checkoutDir.absolutePath, account.id)
                        if (token != null) {
                            gitTokenManager.writeAuthHeader(checkoutDir.absolutePath, token)
                        }

                        // Refresh VFS so IntelliJ picks up the new directory, then notify the
                        // framework listener directly from the background thread — this is the
                        // same pattern used by GitCheckoutProvider.doClone.  The listener
                        // implementations internally dispatch close() onto the EDT.
                        VfsUtil.markDirtyAndRefresh(false, true, true, checkoutDir.parentFile)
                        checkoutListener.directoryCheckedOut(checkoutDir, GitVcs.getKey())
                        checkoutListener.checkoutCompleted()

                        NotificationUtil.info(project, "Azure DevOps Clone",
                            "Repository '${repo.name}' cloned successfully to ${checkoutDir.absolutePath}")
                    } else {
                        val errorMessage = result.errorOutputAsJoinedString
                        NotificationUtil.error(project, "Azure DevOps Clone Error",
                            "Failed to clone repository: $errorMessage")
                    }
                } catch (e: Exception) {
                    NotificationUtil.error(project, "Azure DevOps Clone Error",
                        "Failed to clone repository: ${e.message}")
                }
            }
        })
    }

    override fun doValidateAll(): List<ValidationInfo> {
        return emptyList()
    }

    override fun onComponentSelected() {
        loadAccounts()
    }

    fun getDirectory(): String = directoryField.text.trim()

    private fun loadAccounts() {
        val accountManager = AzureDevOpsAccountManager.getInstance()
        val accounts = accountManager.getAccounts()

        isLoadingAccounts = true
        accountComboBox.removeAllItems()
        accounts.forEach { accountComboBox.addItem(it) }
        isLoadingAccounts = false

        if (accounts.isEmpty()) {
            CloneTreeHelper.showEmptyState(rootNode, treeModel, "No accounts configured. Click '+' to add an account.")
        } else {
            accountComboBox.selectedIndex = 0
            selectedAccount = accounts.firstOrNull()
            loadRepositoriesForCurrentAccount()
            // Notify dialog to check validation
            notifyDialogStateChanged()
        }
    }

    private fun loadRepositoriesForCurrentAccount() {
        val account = accountComboBox.selectedItem as? AzureDevOpsAccount ?: return
        selectedAccount = account

        // Check if we have preloaded data
        val data = preloadedData[account.id]
        if (data != null) {
            CloneTreeHelper.populateTree(rootNode, treeModel, tree, data)
            notifyDialogStateChanged()
            return
        }

        // Show loading state
        CloneTreeHelper.showEmptyState(rootNode, treeModel, "Loading repositories...")

        // Load in background
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val accountManager = AzureDevOpsAccountManager.getInstance()
                val token = accountManager.getToken(account.id)

                if (token != null) {
                    val apiClient = AzureDevOpsCloneApiClient(account.serverUrl, token)
                    val projects = apiClient.getProjects()

                    val repoMap = mutableMapOf<String, List<AzureDevOpsCloneApiClient.Repository>>()
                    projects.forEach { proj ->
                        try {
                            val repos = apiClient.getRepositories(proj.id)
                            repoMap[proj.id] = repos
                        } catch (e: Exception) {
                            logger.warn("Failed to load repos for project ${proj.name}", e)
                            repoMap[proj.id] = emptyList()
                        }
                    }

                    val projectsData = ProjectsData(projects, repoMap)
                    preloadedData[account.id] = projectsData

                    ApplicationManager.getApplication().invokeLater({
                        CloneTreeHelper.populateTree(rootNode, treeModel, tree, projectsData)
                        // Notify dialog after tree is populated
                        notifyDialogStateChanged()
                    }, ModalityState.any())
                } else {
                    ApplicationManager.getApplication().invokeLater({
                        CloneTreeHelper.showEmptyState(rootNode, treeModel, "Authentication failed. Please re-login.")
                        notifyDialogStateChanged()
                    }, ModalityState.any())
                }
            } catch (e: Exception) {
                logger.error("Failed to load repositories", e)
                ApplicationManager.getApplication().invokeLater({
                    CloneTreeHelper.showEmptyState(rootNode, treeModel, "Error loading repositories: ${e.message}")
                    notifyDialogStateChanged()
                }, ModalityState.any())
            }
        }
    }

    private fun notifyDialogStateChanged() {
        val app = ApplicationManager.getApplication()
        val isEnabled = selectedRepository != null
        if (app.isDispatchThread) {
            dialogStateListener.onOkActionEnabled(isEnabled)
            dialogStateListener.onListItemChanged()
        } else {
            app.invokeLater {
                dialogStateListener.onOkActionEnabled(isEnabled)
                dialogStateListener.onListItemChanged()
            }
        }
    }

    private fun filterTree() {
        val account = accountComboBox.selectedItem as? AzureDevOpsAccount ?: return
        val data = preloadedData[account.id] ?: return
        CloneTreeHelper.filterTree(rootNode, treeModel, tree, data, searchField.text)
    }

    private fun showLoginDialog() {
        val loginDialog = AzureDevOpsLoginDialog(project)
        if (loginDialog.showAndGet()) {
            // Reload accounts after login
            loadAccounts()
        }
    }
}
