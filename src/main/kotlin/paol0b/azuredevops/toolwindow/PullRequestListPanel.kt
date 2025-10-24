package paol0b.azuredevops.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.model.PullRequestStatus
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.BorderLayout
import java.awt.Font
import javax.swing.*
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Pannello che mostra la lista delle Pull Request con UI migliorata
 */
class PullRequestListPanel(
    private val project: Project,
    private val onSelectionChanged: (PullRequest?) -> Unit
) {

    private val panel: JPanel
    private val tree: Tree
    private val treeModel: DefaultTreeModel
    private val rootNode: DefaultMutableTreeNode
    private var currentFilter = "active"
    private val statusLabel: JLabel

    init {
        rootNode = DefaultMutableTreeNode("Pull Requests")
        treeModel = DefaultTreeModel(rootNode)
        tree = Tree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = PullRequestCellRenderer()
            border = JBUI.Borders.empty(5)
        }

        // Setup UI helper per il tree
        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)

        // Listener per la selezione
        tree.addTreeSelectionListener(TreeSelectionListener {
            val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val pr = selectedNode?.userObject as? PullRequest
            onSelectionChanged(pr)
        })
        
        // Status label in fondo
        statusLabel = JLabel("Ready").apply {
            border = JBUI.Borders.empty(5, 10)
            font = font.deriveFont(Font.PLAIN, 11f)
        }

        panel = JPanel(BorderLayout()).apply {
            add(JBScrollPane(tree), BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
        }
    }

    fun getComponent(): JPanel = panel

    fun refreshPullRequests() {
        statusLabel.text = "Loading Pull Requests..."
        statusLabel.icon = AllIcons.Process.Step_1
        
        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Loading Pull Requests...",
            false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val apiClient = AzureDevOpsApiClient.getInstance(project)
                    val pullRequests = apiClient.getPullRequests(status = currentFilter)

                    ApplicationManager.getApplication().invokeLater {
                        updateTreeWithPullRequests(pullRequests)
                        statusLabel.text = "Loaded ${pullRequests.size} Pull Request(s)"
                        statusLabel.icon = AllIcons.General.InspectionsOK
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        updateTreeWithError(e.message ?: "Unknown error")
                        statusLabel.text = "Error loading Pull Requests"
                        statusLabel.icon = AllIcons.General.Error
                    }
                }
            }
        })
    }

    fun setFilterStatus(status: String) {
        currentFilter = status
        refreshPullRequests()
    }

    fun getSelectedPullRequest(): PullRequest? {
        val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        return selectedNode?.userObject as? PullRequest
    }

    private fun updateTreeWithPullRequests(pullRequests: List<PullRequest>) {
        rootNode.removeAllChildren()

        if (pullRequests.isEmpty()) {
            val emptyNode = DefaultMutableTreeNode("No Pull Requests found")
            rootNode.add(emptyNode)
        } else {
            // Raggruppa per stato
            val active = pullRequests.filter { it.status == PullRequestStatus.Active }
            val completed = pullRequests.filter { it.status == PullRequestStatus.Completed }
            val abandoned = pullRequests.filter { it.status == PullRequestStatus.Abandoned }

            if (active.isNotEmpty()) {
                val activeFolder = DefaultMutableTreeNode("Active (${active.size})")
                active.forEach { pr ->
                    activeFolder.add(DefaultMutableTreeNode(pr))
                }
                rootNode.add(activeFolder)
            }

            if (completed.isNotEmpty() && currentFilter == "all") {
                val completedFolder = DefaultMutableTreeNode("Completed (${completed.size})")
                completed.forEach { pr ->
                    completedFolder.add(DefaultMutableTreeNode(pr))
                }
                rootNode.add(completedFolder)
            }

            if (abandoned.isNotEmpty() && currentFilter == "all") {
                val abandonedFolder = DefaultMutableTreeNode("Abandoned (${abandoned.size})")
                abandoned.forEach { pr ->
                    abandonedFolder.add(DefaultMutableTreeNode(pr))
                }
                rootNode.add(abandonedFolder)
            }
        }

        treeModel.reload()
        
        // Espandi tutti i nodi
        for (i in 0 until tree.rowCount) {
            tree.expandRow(i)
        }
    }

    private fun updateTreeWithError(errorMessage: String) {
        rootNode.removeAllChildren()
        val errorNode = DefaultMutableTreeNode("Error: $errorMessage")
        rootNode.add(errorNode)
        treeModel.reload()
    }

    /**
     * Renderer custom per le PR nel tree con design migliorato
     */
    private class PullRequestCellRenderer : ColoredTreeCellRenderer() {
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
                is PullRequest -> {
                    // Icona e colore in base allo stato
                    icon = when (userObject.status) {
                        PullRequestStatus.Active -> {
                            if (userObject.isDraft == true) AllIcons.Vcs.Patch_applied 
                            else AllIcons.Vcs.Branch
                        }
                        PullRequestStatus.Completed -> AllIcons.Process.ProgressPauseSmall
                        PullRequestStatus.Abandoned -> AllIcons.Process.Stop
                        else -> AllIcons.Vcs.Branch
                    }

                    // ID PR con colore
                    val idColor = when (userObject.status) {
                        PullRequestStatus.Active -> SimpleTextAttributes.LINK_BOLD_ATTRIBUTES
                        PullRequestStatus.Completed -> SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES
                        else -> SimpleTextAttributes.GRAY_ATTRIBUTES
                    }
                    append("PR #${userObject.pullRequestId} ", idColor)
                    
                    // Draft badge
                    if (userObject.isDraft == true) {
                        append("[DRAFT] ", SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            java.awt.Color(255, 165, 0)
                        ))
                    }

                    // Titolo PR
                    val titleAttrs = if (userObject.status == PullRequestStatus.Active) {
                        SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    } else {
                        SimpleTextAttributes.GRAYED_ATTRIBUTES
                    }
                    append(userObject.title, titleAttrs)

                    // Branch info su nuova linea visuale
                    append("  ")
                    append("${userObject.getSourceBranchName()}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    append(" → ", SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN,
                        java.awt.Color(120, 120, 120)
                    ))
                    append("${userObject.getTargetBranchName()}", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    
                    // Info autore
                    userObject.createdBy?.displayName?.let { author ->
                        append("  •  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                        append("by $author", SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_ITALIC,
                            java.awt.Color(100, 150, 200)
                        ))
                    }
                }
                is String -> {
                    // Nodi folder con icone e stili migliorati
                    when {
                        userObject.startsWith("Active") -> {
                            icon = AllIcons.Nodes.Folder
                            append(userObject, SimpleTextAttributes(
                                SimpleTextAttributes.STYLE_BOLD,
                                java.awt.Color(34, 139, 34)
                            ))
                        }
                        userObject.startsWith("Completed") -> {
                            icon = AllIcons.Nodes.Folder
                            append(userObject, SimpleTextAttributes(
                                SimpleTextAttributes.STYLE_BOLD,
                                java.awt.Color(100, 100, 100)
                            ))
                        }
                        userObject.startsWith("Abandoned") -> {
                            icon = AllIcons.Nodes.Folder
                            append(userObject, SimpleTextAttributes(
                                SimpleTextAttributes.STYLE_BOLD,
                                java.awt.Color(178, 34, 34)
                            ))
                        }
                        else -> {
                            append(userObject, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                        }
                    }
                }
            }
        }
    }
}
