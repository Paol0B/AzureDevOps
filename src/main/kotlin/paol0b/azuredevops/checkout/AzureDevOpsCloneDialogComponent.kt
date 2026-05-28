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
 *
 * Loading strategy: per-account, lightweight project list first, then bounded-parallel
 * per-project repo fetches that fill the tree incrementally. Selected projects are
 * persisted per account via [ProjectSelectionStore].
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

    // Project filter button (multi-select popup)
    private val projectFilterButton = JButton("Projects: —").apply {
        toolTipText = "Pick which Azure DevOps projects should be loaded"
        horizontalAlignment = SwingConstants.LEFT
        margin = JBUI.insets(2, 10)
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

    private val accountStates = mutableMapOf<String, AccountState>()
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
                val repoDir = File(baseCloneDir, userObject.name).absolutePath
                directoryField.text = repoDir
            } else {
                selectedRepository = null
            }
            notifyDialogStateChanged()
        }

        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                currentAccountState()?.let { renderTree(it) }
            }
        })

        addAccountButton.addActionListener {
            showLoginDialog()
        }

        accountComboBox.addActionListener {
            if (!isLoadingAccounts) {
                handleAccountChanged()
            }
        }

        projectFilterButton.addActionListener {
            openProjectFilterPopup()
        }

        // Directory field setup
        val fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        directoryField.addBrowseFolderListener(
            TextBrowseFolderListener(fileChooserDescriptor, project)
        )
        directoryField.text = defaultCloneDir

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

        // === TOP: Account selection + project filter ===
        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(0, 0, 8, 0)

            val accountRow = JPanel(BorderLayout(4, 0)).apply {
                add(accountComboBox, BorderLayout.CENTER)
                add(addAccountButton, BorderLayout.EAST)
                alignmentX = 0f
            }
            add(accountRow)

            val filterRow = JPanel(BorderLayout(4, 0)).apply {
                border = JBUI.Borders.emptyTop(6)
                add(JBLabel("Projects:"), BorderLayout.WEST)
                add(projectFilterButton, BorderLayout.CENTER)
                alignmentX = 0f
            }
            add(filterRow)
        }

        // === MIDDLE: Search + Tree ===
        val centerPanel = JPanel(BorderLayout(0, 8)).apply {
            add(searchField, BorderLayout.NORTH)

            val scrollPane = JBScrollPane(tree).apply {
                border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1)
            }
            add(scrollPane, BorderLayout.CENTER)
        }

        // === BOTTOM: Clone options ===
        val bottomPanel = JPanel(BorderLayout(0, 8)).apply {
            border = JBUI.Borders.empty(8, 0, 0, 0)

            val directoryRow = JPanel(BorderLayout(8, 0)).apply {
                add(JBLabel("Directory:"), BorderLayout.WEST)
                add(directoryField, BorderLayout.CENTER)
            }
            add(directoryRow, BorderLayout.NORTH)

            val shallowRow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                add(shallowCloneCheckbox)
                add(shallowCloneDepthField)
                add(commitsLabel)
            }
            add(shallowRow, BorderLayout.SOUTH)
        }

        panel.add(topPanel, BorderLayout.NORTH)
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
                        val gitTokenManager = GitTokenManager.getInstance()
                        gitTokenManager.registerRepo(checkoutDir.absolutePath, account.id)
                        if (token != null) {
                            gitTokenManager.writeAuthHeader(checkoutDir.absolutePath, token)
                        }

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
            selectedAccount = null
            updateProjectFilterButton(null)
            CloneTreeHelper.showEmptyState(rootNode, treeModel, "No accounts configured. Click '+' to add an account.")
        } else {
            accountComboBox.selectedIndex = 0
            handleAccountChanged()
            notifyDialogStateChanged()
        }
    }

    private fun handleAccountChanged() {
        val account = accountComboBox.selectedItem as? AzureDevOpsAccount ?: return
        selectedAccount = account

        val existing = accountStates[account.id]
        if (existing != null) {
            updateProjectFilterButton(existing)
            renderTree(existing)
            // If projects exist but some selected ones have no repos yet, kick off their fetch.
            if (existing.projectsLoaded) {
                val toLoad = existing.selectedProjectIds.filter {
                    it !in existing.repos && it !in existing.loadingProjectIds
                }
                if (toLoad.isNotEmpty()) loadReposFor(existing, toLoad)
            }
            return
        }

        val token = AzureDevOpsAccountManager.getInstance().getToken(account.id)
        if (token == null) {
            CloneTreeHelper.showEmptyState(rootNode, treeModel, "Authentication failed. Please re-login.")
            updateProjectFilterButton(null)
            return
        }
        val state = AccountState(account, AzureDevOpsCloneApiClient(account.serverUrl, token))
        accountStates[account.id] = state
        updateProjectFilterButton(state)
        loadProjectsThenInitRepos(state)
    }

    private fun loadProjectsThenInitRepos(state: AccountState) {
        CloneTreeHelper.showEmptyState(rootNode, treeModel, "Loading projects…")
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val projects = state.apiClient.getProjects()
                ApplicationManager.getApplication().invokeLater({
                    state.projects = projects
                    state.projectsLoaded = true

                    val knownIds = projects.map { it.id }.toSet()
                    val initialSelection = if (ProjectSelectionStore.isStored(state.account.id)) {
                        ProjectSelectionStore.load(state.account.id).intersect(knownIds)
                    } else {
                        knownIds
                    }
                    state.selectedProjectIds = initialSelection.toMutableSet()

                    if (selectedAccount?.id == state.account.id) {
                        updateProjectFilterButton(state)
                        renderTree(state)
                    }

                    if (state.selectedProjectIds.isNotEmpty()) {
                        loadReposFor(state, state.selectedProjectIds.toList())
                    }
                }, ModalityState.any())
            } catch (e: Exception) {
                logger.error("Failed to load projects for ${state.account.displayName}", e)
                ApplicationManager.getApplication().invokeLater({
                    if (selectedAccount?.id == state.account.id) {
                        CloneTreeHelper.showEmptyState(rootNode, treeModel, "Error loading projects: ${e.message}")
                    }
                }, ModalityState.any())
            }
        }
    }

    private fun loadReposFor(state: AccountState, projectIds: List<String>) {
        val toFetch = projectIds.filter { it !in state.repos && it !in state.loadingProjectIds }
        if (toFetch.isEmpty()) return

        state.loadingProjectIds.addAll(toFetch)
        if (selectedAccount?.id == state.account.id) renderTree(state)

        state.loader.load(
            projectIds = toFetch,
            onLoaded = { projectId, repos ->
                ApplicationManager.getApplication().invokeLater({
                    state.repos[projectId] = repos
                    state.loadingProjectIds.remove(projectId)
                    if (selectedAccount?.id == state.account.id) renderTree(state)
                }, ModalityState.any())
            },
            onFailed = { projectId, _ ->
                ApplicationManager.getApplication().invokeLater({
                    state.repos[projectId] = emptyList()
                    state.loadingProjectIds.remove(projectId)
                    if (selectedAccount?.id == state.account.id) renderTree(state)
                }, ModalityState.any())
            },
            onAllDone = { /* no-op; the tree is updated incrementally */ }
        )
    }

    private fun renderTree(state: AccountState) {
        if (!state.projectsLoaded) return
        CloneTreeHelper.render(
            rootNode = rootNode,
            treeModel = treeModel,
            tree = tree,
            projects = state.projects,
            repos = state.repos,
            selectedProjectIds = state.selectedProjectIds,
            loadingProjectIds = state.loadingProjectIds,
            searchText = searchField.text
        )
        notifyDialogStateChanged()
    }

    private fun updateProjectFilterButton(state: AccountState?) {
        if (state == null || !state.projectsLoaded) {
            projectFilterButton.text = "Loading…"
            projectFilterButton.isEnabled = false
            return
        }
        projectFilterButton.text = ProjectFilterPopup.label(state.selectedProjectIds.size, state.projects.size)
        projectFilterButton.isEnabled = state.projects.isNotEmpty()
    }

    private fun openProjectFilterPopup() {
        val state = currentAccountState() ?: return
        if (!state.projectsLoaded) return

        ProjectFilterPopup.show(
            anchor = projectFilterButton,
            projects = state.projects,
            initiallySelected = state.selectedProjectIds
        ) { newSelection ->
            state.selectedProjectIds = newSelection.toMutableSet()
            ProjectSelectionStore.save(state.account.id, newSelection)
            updateProjectFilterButton(state)
            renderTree(state)

            val toLoad = newSelection.filter { it !in state.repos && it !in state.loadingProjectIds }
            if (toLoad.isNotEmpty()) loadReposFor(state, toLoad)
        }
    }

    private fun currentAccountState(): AccountState? {
        val id = selectedAccount?.id ?: return null
        return accountStates[id]
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

    private fun showLoginDialog() {
        val loginDialog = AzureDevOpsLoginDialog(project)
        if (loginDialog.showAndGet()) {
            loadAccounts()
        }
    }

    /**
     * Per-account snapshot of what's been loaded so far. Lives for the lifetime of the
     * dialog so re-selecting an account keeps its already-fetched repos cached.
     */
    private class AccountState(
        val account: AzureDevOpsAccount,
        val apiClient: AzureDevOpsCloneApiClient
    ) {
        var projects: List<AzureDevOpsCloneApiClient.Project> = emptyList()
        var projectsLoaded: Boolean = false
        val repos: MutableMap<String, List<AzureDevOpsCloneApiClient.Repository>> = mutableMapOf()
        var selectedProjectIds: MutableSet<String> = mutableSetOf()
        val loadingProjectIds: MutableSet<String> = mutableSetOf()
        val loader: RepositoryLoader = RepositoryLoader(apiClient)
    }
}
