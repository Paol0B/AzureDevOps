package paol0b.azuredevops.checkout

import com.intellij.dvcs.ui.CloneDvcsValidationUtils
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vcs.CheckoutProvider
import com.intellij.openapi.vcs.ui.cloneDialog.VcsCloneDialogExtensionComponent
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
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
import javax.swing.tree.TreePath
import javax.swing.tree.TreeSelectionModel

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

    private val defaultCloneDir = System.getProperty("user.home") + File.separator + "IdeaProjects"
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
                // Notify dialog of state change to enable/disable Clone button
                ApplicationManager.getApplication().invokeLater {
                    dialogStateListener.onListItemChanged()
                }
            } else {
                selectedRepository = null
            }
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
            "Select Clone Directory",
            "Choose the directory where the repository will be cloned",
            project,
            fileChooserDescriptor
        )
        directoryField.text = defaultCloneDir
        
        // Listen for directory changes to trigger validation
        directoryField.textField.document.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                ApplicationManager.getApplication().invokeLater {
                    dialogStateListener.onListItemChanged()
                }
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

        ProgressManager.getInstance().run(object : Task.Backgroundable(
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
                        if (token != null) {
                            saveCredentialsToGit(checkoutDir, cloneUrl, token)
                        }

                        ApplicationManager.getApplication().invokeLater {
                            checkoutListener.directoryCheckedOut(checkoutDir, GitVcs.getKey())
                            checkoutListener.checkoutCompleted()
                        }

                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("AzureDevOps.Notifications")
                            .createNotification(
                                "Azure DevOps Clone",
                                "Repository '${repo.name}' cloned successfully to ${checkoutDir.absolutePath}",
                                NotificationType.INFORMATION
                            )
                            .notify(project)
                    } else {
                        val errorMessage = result.errorOutputAsJoinedString
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("AzureDevOps.Notifications")
                            .createNotification(
                                "Azure DevOps Clone Error",
                                "Failed to clone repository: $errorMessage",
                                NotificationType.ERROR
                            )
                            .notify(project)
                    }
                } catch (e: Exception) {
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("AzureDevOps.Notifications")
                        .createNotification(
                            "Azure DevOps Clone Error",
                            "Failed to clone repository: ${e.message}",
                            NotificationType.ERROR
                        )
                        .notify(project)
                }
            }
        })
    }

    override fun doValidateAll(): List<ValidationInfo> {
        val list = mutableListOf<ValidationInfo>()

        // Must have selected a repository
        if (selectedRepository == null) {
            list.add(ValidationInfo("Please select a repository to clone", tree))
            return list  // No point validating further if no repo selected
        }

        val directory = directoryField.text.trim()
        if (directory.isBlank()) {
            list.add(ValidationInfo("Please specify a target directory", directoryField))
        } else {
            val targetDir = File(directory)
            // Directory itself must NOT exist (git clone creates it)
            if (targetDir.exists()) {
                list.add(ValidationInfo("Directory already exists: $directory", directoryField))
            }
            // Parent directory must exist
            val parentDir = targetDir.parentFile
            if (parentDir == null || !parentDir.exists()) {
                list.add(ValidationInfo("Parent directory does not exist", directoryField))
            }
        }
        
        // Validate shallow clone depth if enabled
        if (shallowCloneCheckbox.isSelected) {
            val depth = shallowCloneDepthField.text.toIntOrNull()
            if (depth == null || depth < 1) {
                list.add(ValidationInfo("Please enter a valid number of commits (>= 1)", shallowCloneDepthField))
            }
        }

        return list
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
            showEmptyState("No accounts configured. Click '+' to add an account.")
        } else {
            accountComboBox.selectedIndex = 0
            selectedAccount = accounts.firstOrNull()
            loadRepositoriesForCurrentAccount()
            // Notify dialog to check validation
            ApplicationManager.getApplication().invokeLater {
                dialogStateListener.onListItemChanged()
            }
        }
    }

    private fun loadRepositoriesForCurrentAccount() {
        val account = accountComboBox.selectedItem as? AzureDevOpsAccount ?: return
        selectedAccount = account

        // Check if we have preloaded data
        val data = preloadedData[account.id]
        if (data != null) {
            populateTreeFromData(data)
            return
        }

        // Show loading state
        rootNode.removeAllChildren()
        val loadingNode = DefaultMutableTreeNode("Loading repositories...")
        rootNode.add(loadingNode)
        treeModel.reload()

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
                        populateTreeFromData(projectsData)
                        // Notify dialog after tree is populated
                        dialogStateListener.onListItemChanged()
                    }, ModalityState.any())
                } else {
                    ApplicationManager.getApplication().invokeLater({
                        showEmptyState("Authentication failed. Please re-login.")
                    }, ModalityState.any())
                }
            } catch (e: Exception) {
                logger.error("Failed to load repositories", e)
                ApplicationManager.getApplication().invokeLater({
                    showEmptyState("Error loading repositories: ${e.message}")
                }, ModalityState.any())
            }
        }
    }

    private fun populateTreeFromData(data: ProjectsData) {
        rootNode.removeAllChildren()

        if (data.projects.isEmpty()) {
            val emptyNode = DefaultMutableTreeNode("No projects found for this account")
            rootNode.add(emptyNode)
            treeModel.reload()
            return
        }

        // Sort projects alphabetically
        val sortedProjects = data.projects.sortedBy { it.name.lowercase() }
        
        sortedProjects.forEach { proj ->
            val projectNode = DefaultMutableTreeNode(proj)
            rootNode.add(projectNode)

            val repos = data.repositories[proj.id] ?: emptyList()
            // Sort repos alphabetically
            repos.sortedBy { it.name.lowercase() }.forEach { repo ->
                val repoObj = AzureDevOpsRepository(
                    id = repo.id,
                    name = repo.name,
                    projectName = proj.name,
                    remoteUrl = repo.remoteUrl,
                    webUrl = repo.webUrl
                )
                val repoNode = DefaultMutableTreeNode(repoObj)
                projectNode.add(repoNode)
            }
        }

        treeModel.reload()

        // Expand all projects by default
        for (i in 0 until rootNode.childCount) {
            tree.expandPath(TreePath(arrayOf(rootNode, rootNode.getChildAt(i))))
        }
    }

    private fun showEmptyState(message: String) {
        rootNode.removeAllChildren()
        val emptyNode = DefaultMutableTreeNode(message)
        rootNode.add(emptyNode)
        treeModel.reload()
    }

    private fun filterTree() {
        val searchText = searchField.text.trim().lowercase()

        if (searchText.isEmpty()) {
            val account = selectedAccount ?: return
            val data = preloadedData[account.id] ?: return
            populateTreeFromData(data)
            return
        }

        val account = accountComboBox.selectedItem as? AzureDevOpsAccount ?: return
        val data = preloadedData[account.id] ?: return

        rootNode.removeAllChildren()

        data.projects.sortedBy { it.name.lowercase() }.forEach { proj ->
            val repos = data.repositories[proj.id] ?: emptyList()
            val matchingRepos = repos.filter { repo ->
                repo.name.lowercase().contains(searchText) ||
                        proj.name.lowercase().contains(searchText)
            }.sortedBy { it.name.lowercase() }

            if (matchingRepos.isNotEmpty()) {
                val projectNode = DefaultMutableTreeNode(proj)
                rootNode.add(projectNode)

                matchingRepos.forEach { repo ->
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
        }

        treeModel.reload()

        // Expand all matching projects
        for (i in 0 until rootNode.childCount) {
            tree.expandPath(TreePath(arrayOf(rootNode, rootNode.getChildAt(i))))
        }
    }

    private fun showLoginDialog() {
        val loginDialog = AzureDevOpsLoginDialog(project)
        if (loginDialog.showAndGet()) {
            // Reload accounts after login
            loadAccounts()
        }
    }

    private fun saveCredentialsToGit(repoDir: File, url: String, token: String) {
        try {
            val processBuilder = ProcessBuilder("git", "credential", "approve")
            processBuilder.directory(repoDir)
            processBuilder.redirectErrorStream(true)

            val process = processBuilder.start()

            process.outputStream.bufferedWriter().use { writer ->
                val uri = URI(url)
                writer.write("protocol=${uri.scheme}\n")
                writer.write("host=${uri.host}\n")
                val path = uri.path?.removePrefix("/")?.removeSuffix(".git") ?: ""
                if (path.isNotBlank()) {
                    writer.write("path=$path\n")
                }
                writer.write("username=oauth\n")
                writer.write("password=$token\n")
                writer.write("\n")
                writer.flush()
            }

            process.waitFor()
        } catch (e: Exception) {
            logger.warn("Failed to save credentials to git", e)
        }
    }

    private fun normalizeAzureDevOpsUrl(url: String): String {
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
     * Tree cell renderer with proper Azure DevOps icons
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
                    // Project folder icon
                    icon = AzureDevOpsIcons.Project
                    append(userObject.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                    userObject.description?.let {
                        if (it.isNotBlank() && it.length < 50) {
                            append("  $it", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                        }
                    }
                }
                is AzureDevOpsRepository -> {
                    // Repository icon (git branch)
                    icon = AzureDevOpsIcons.Repository
                    append(userObject.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
                is String -> {
                    icon = when {
                        userObject.startsWith("Error") || userObject.startsWith("Authentication") -> AllIcons.General.Error
                        userObject.contains("Loading") -> AllIcons.Process.Step_1
                        userObject.contains("No ") -> AllIcons.General.Information
                        else -> AllIcons.General.Information
                    }
                    append(userObject, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
            }
        }
    }
}
