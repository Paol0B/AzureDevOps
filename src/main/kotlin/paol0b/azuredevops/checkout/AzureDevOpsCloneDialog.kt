package paol0b.azuredevops.checkout

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.TextBrowseFolderListener
import com.intellij.openapi.ui.TextFieldWithBrowseButton
import com.intellij.openapi.ui.ValidationInfo
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
import javax.swing.tree.TreeSelectionModel

/**
 * Enhanced clone dialog for Azure DevOps (standalone variant).
 *
 * Loading strategy mirrors [AzureDevOpsCloneDialogComponent]: per-account, lightweight
 * project list first, then bounded-parallel per-project repo fetches that fill the tree
 * incrementally. Selected projects are persisted per account via [ProjectSelectionStore].
 */
class AzureDevOpsCloneDialog private constructor(
    private val project: Project?
) : DialogWrapper(project, true) {

    private val logger = Logger.getInstance(AzureDevOpsCloneDialog::class.java)

    private val accountComboBox = ComboBox<AzureDevOpsAccount>()

    private val loginButton = JButton("Add Account...").apply {
        icon = AllIcons.General.Add
    }
    private val removeButton = JButton("Remove").apply {
        icon = AllIcons.General.Remove
        toolTipText = "Remove selected account"
    }
    private val projectFilterField = SelectedProjectsField(
        onClick = { openProjectFilterPopup() },
        onRemove = { projectId -> removeProjectFromSelection(projectId) }
    ).apply {
        toolTipText = "Pick which Azure DevOps projects should be loaded"
        isEnabled = false
    }

    private val tree: Tree
    private val treeModel: DefaultTreeModel
    private val rootNode: DefaultMutableTreeNode
    private val directoryField = TextFieldWithBrowseButton()

    private val searchField = com.intellij.ui.components.JBTextField()
    private var selectedRepository: AzureDevOpsRepository? = null
    private var selectedAccount: AzureDevOpsAccount? = null
    private var isLoadingAccounts = false
    private val defaultCloneDir = System.getProperty("user.home") + File.separator + "source" + File.separator + "repos"
    private var baseCloneDir = defaultCloneDir

    private val accountStates = mutableMapOf<String, AccountState>()

    companion object {
        /**
         * Ensures at least one account exists (prompting the login dialog if not) and then
         * returns a new instance. The dialog opens immediately; project and repo loading
         * happens incrementally in the background after [show].
         */
        fun create(project: Project?): AzureDevOpsCloneDialog? {
            val accountManager = AzureDevOpsAccountManager.getInstance()
            if (accountManager.getAccounts().isEmpty()) {
                val loginDialog = AzureDevOpsLoginDialog(project)
                if (!loginDialog.showAndGet()) return null
                if (accountManager.getAccounts().isEmpty()) return null
            }
            return AzureDevOpsCloneDialog(project)
        }
    }

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
                val targetDir = File(baseCloneDir, userObject.name).absolutePath
                directoryField.text = targetDir
            } else {
                selectedRepository = null
            }
        }

        searchField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = rerenderCurrent()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = rerenderCurrent()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = rerenderCurrent()
        })
        searchField.emptyText.text = "Search repositories..."

        loginButton.addActionListener { showLoginDialog() }
        removeButton.addActionListener { removeSelectedAccount() }

        accountComboBox.addActionListener {
            if (!isLoadingAccounts) {
                handleAccountChanged()
            }
        }

        val fileChooserDescriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor()
        directoryField.addBrowseFolderListener(
            TextBrowseFolderListener(fileChooserDescriptor, project)
        )

        directoryField.textField.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) = updateBaseCloneDir()
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) = updateBaseCloneDir()
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) = updateBaseCloneDir()

            private fun updateBaseCloneDir() {
                val currentPath = directoryField.text.trim()
                if (currentPath.isNotEmpty()) {
                    val currentFile = File(currentPath)
                    if (selectedRepository != null && currentFile.name == selectedRepository?.name) {
                        baseCloneDir = currentFile.parent ?: defaultCloneDir
                    } else {
                        baseCloneDir = currentPath
                    }
                }
            }
        })

        directoryField.text = defaultCloneDir
        removeButton.isEnabled = false

        init()
        loadAccounts()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10))

        val headerPanel = JPanel(BorderLayout()).apply {
            val titleLabel = JBLabel("Clone Repository from Azure DevOps").apply {
                font = font.deriveFont(Font.BOLD, 14f)
                icon = AzureDevOpsIcons.Logo
            }
            add(titleLabel, BorderLayout.WEST)
            border = JBUI.Borders.empty(0, 0, 10, 0)
        }

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

        val projectsRow = JPanel(BorderLayout(10, 0)).apply {
            add(JBLabel("Projects:").apply {
                font = font.deriveFont(Font.BOLD)
            }, BorderLayout.WEST)
            add(projectFilterField, BorderLayout.CENTER)
            border = JBUI.Borders.empty(0, 0, 10, 0)
        }

        val treePanel = JPanel(BorderLayout()).apply {
            val topPanel = JPanel(BorderLayout()).apply {
                val treeLabel = JBLabel("Select a Repository:").apply {
                    font = font.deriveFont(Font.BOLD)
                    border = JBUI.Borders.empty(0, 0, 5, 0)
                }
                add(treeLabel, BorderLayout.NORTH)

                val searchPanel = JPanel(BorderLayout()).apply {
                    searchField.apply {
                        putClientProperty("JTextField.Search.Icon", AllIcons.Actions.Search)
                        putClientProperty("JTextField.Search.CancelAction", Runnable {
                            searchField.text = ""
                        })
                    }
                    add(searchField, BorderLayout.CENTER)
                    border = JBUI.Borders.empty(5, 0)
                }
                add(searchPanel, BorderLayout.SOUTH)
            }

            val scrollPane = JBScrollPane(tree).apply {
                border = JBUI.Borders.customLine(UIUtil.getBoundsColor(), 1)
            }

            add(topPanel, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            preferredSize = Dimension(650, 400)
        }

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
            val topStack = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                accountPanel.alignmentX = 0f
                projectsRow.alignmentX = 0f
                add(accountPanel)
                add(projectsRow)
            }
            add(topStack, BorderLayout.NORTH)
            add(treePanel, BorderLayout.CENTER)
        }
        panel.add(centerPanel, BorderLayout.CENTER)
        panel.add(directoryPanel, BorderLayout.SOUTH)

        panel.border = JBUI.Borders.empty(10)
        panel.preferredSize = Dimension(750, 620)

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

        isLoadingAccounts = true
        accountComboBox.removeAllItems()
        accounts.forEach { accountComboBox.addItem(it) }
        isLoadingAccounts = false

        if (accounts.isEmpty()) {
            selectedAccount = null
            removeButton.isEnabled = false
            updateProjectFilterField(null)
            CloneTreeHelper.showEmptyState(rootNode, treeModel, "No accounts configured.")
            return
        }

        accountComboBox.selectedIndex = 0
        removeButton.isEnabled = true
        handleAccountChanged()
    }

    private fun handleAccountChanged() {
        val account = accountComboBox.selectedItem as? AzureDevOpsAccount ?: return
        selectedAccount = account
        removeButton.isEnabled = true

        val existing = accountStates[account.id]
        if (existing != null) {
            updateProjectFilterField(existing)
            renderTree(existing)
            if (existing.projectsLoaded) {
                val toLoad = effectiveSelection(existing).filter {
                    it !in existing.repos && it !in existing.loadingProjectIds
                }
                if (toLoad.isNotEmpty()) loadReposFor(existing, toLoad)
            }
            return
        }

        val token = AzureDevOpsAccountManager.getInstance().getToken(account.id)
        if (token == null) {
            CloneTreeHelper.showEmptyState(rootNode, treeModel, "Authentication failed. Please re-login.")
            updateProjectFilterField(null)
            return
        }
        val state = AccountState(account, AzureDevOpsCloneApiClient(account.serverUrl, token))
        accountStates[account.id] = state
        updateProjectFilterField(state)
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
                        updateProjectFilterField(state)
                        renderTree(state)
                    }

                    loadReposFor(state, effectiveSelection(state).toList())
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
            onAllDone = { /* tree is updated per project */ }
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
    }

    private fun rerenderCurrent() {
        currentAccountState()?.let { renderTree(it) }
    }

    private fun updateProjectFilterField(state: AccountState?) {
        if (state == null || !state.projectsLoaded) {
            projectFilterField.setSelection(projectsLoaded = false, totalProjects = 0, selected = emptyList())
            projectFilterField.isEnabled = false
            return
        }
        val selected = state.projects.filter { it.id in state.selectedProjectIds }
        projectFilterField.setSelection(
            projectsLoaded = true,
            totalProjects = state.projects.size,
            selected = selected
        )
        projectFilterField.isEnabled = state.projects.isNotEmpty()
    }

    private fun removeProjectFromSelection(projectId: String) {
        val state = currentAccountState() ?: return
        if (!state.projectsLoaded) return
        if (!state.selectedProjectIds.remove(projectId)) return
        ProjectSelectionStore.save(state.account.id, state.selectedProjectIds)
        updateProjectFilterField(state)
        renderTree(state)
    }

    private fun openProjectFilterPopup() {
        val state = currentAccountState() ?: return
        if (!state.projectsLoaded) return

        ProjectFilterPopup.show(
            anchor = projectFilterField,
            projects = state.projects,
            initiallySelected = effectiveSelection(state)
        ) { newSelection ->
            state.selectedProjectIds = newSelection.toMutableSet()
            ProjectSelectionStore.save(state.account.id, newSelection)
            updateProjectFilterField(state)
            renderTree(state)

            val toFetch = effectiveSelection(state).filter {
                it !in state.repos && it !in state.loadingProjectIds
            }
            if (toFetch.isNotEmpty()) loadReposFor(state, toFetch)
        }
    }

    /** Project ids that should currently be visible: the explicit selection, or every
     *  project when the explicit selection is empty (= "no filter"). */
    private fun effectiveSelection(state: AccountState): Set<String> =
        if (state.selectedProjectIds.isEmpty()) state.projects.map { it.id }.toSet()
        else state.selectedProjectIds.toSet()

    private fun currentAccountState(): AccountState? {
        val id = selectedAccount?.id ?: return null
        return accountStates[id]
    }

    private fun showLoginDialog() {
        val loginDialog = AzureDevOpsLoginDialog(project)
        if (loginDialog.showAndGet()) {
            loadAccounts()
        }
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
            accountStates.remove(account.id)
            ProjectSelectionStore.clear(account.id)

            rootNode.removeAllChildren()
            treeModel.reload()

            loadAccounts()

            com.intellij.openapi.ui.Messages.showInfoMessage(
                project,
                "Account removed successfully.",
                "Account Removed"
            )
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
