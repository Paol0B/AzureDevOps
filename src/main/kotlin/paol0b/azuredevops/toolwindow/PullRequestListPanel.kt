package paol0b.azuredevops.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.TreeUIHelper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.actions.AbandonPullRequestAction
import paol0b.azuredevops.actions.CompletePullRequestAction
import paol0b.azuredevops.actions.SetAutoCompletePullRequestAction
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.model.PullRequestStatus
import paol0b.azuredevops.model.ReviewerVote
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.toolwindow.filters.PullRequestFilterPanel
import paol0b.azuredevops.toolwindow.filters.PullRequestSearchValue
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Dimension
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.TreeSelectionListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Wrapper class to represent a project node in the tree
 */
data class ProjectNode(val name: String, val prCount: Int)

/**
 * Panel that shows the list of Pull Requests with the GitHub-style filter bar.
 */
class PullRequestListPanel(
    private val project: Project,
    private val onSelectionChanged: (PullRequest?) -> Unit
) {

    private val panel: JPanel
    private val tree: Tree
    private val treeModel: DefaultTreeModel
    private val rootNode: DefaultMutableTreeNode
    private val statusLabel: JLabel
    private val filterPanel: PullRequestFilterPanel

    private var lastSelectedPrId: Int? = null
    private var cachedPullRequests: List<PullRequest> = emptyList()
    private var lastLoadedPullRequests: List<PullRequest> = emptyList()
    private var isErrorState: Boolean = false
    private val expandedNodes = mutableSetOf<String>()
    private var currentUserId: String? = null

    // Derived from filter panel state
    private var currentSearchValue = PullRequestSearchValue.DEFAULT

    init {
        rootNode = DefaultMutableTreeNode("Pull Requests")
        treeModel = DefaultTreeModel(rootNode)
        tree = Tree(treeModel).apply {
            isRootVisible = false
            showsRootHandles = true
            cellRenderer = PullRequestCellRenderer()
            border = JBUI.Borders.empty(10, 16)
            rowHeight = 0
            putClientProperty("JTree.lineStyle", "Horizontal")
        }

        TreeUIHelper.getInstance().installTreeSpeedSearch(tree)
        addTreeExpandListener()

        tree.addTreeSelectionListener(TreeSelectionListener {
            val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
            val pr = selectedNode?.userObject as? PullRequest
            lastSelectedPrId = pr?.pullRequestId
            onSelectionChanged(pr)
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
                    val pr = node.userObject as? PullRequest ?: return
                    PullRequestToolWindowFactory.openPrReviewTab(project, pr)
                }
            }
        })

        statusLabel = JLabel("Ready").apply {
            border = JBUI.Borders.empty(8, 12)
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.GRAY
        }

        // New GitHub-style filter panel
        filterPanel = PullRequestFilterPanel(project) { newFilter ->
            onFilterChanged(newFilter)
        }

        val scrollPane = JBScrollPane(tree).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 16
        }

        panel = JPanel(BorderLayout()).apply {
            add(filterPanel.getComponent(), BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
            minimumSize = Dimension(250, 0)
        }
    }

    fun getComponent(): JPanel = panel

    /**
     * Called when the filter panel reports a new filter value.
     */
    private fun onFilterChanged(newValue: PullRequestSearchValue) {
        val statusChanged = currentSearchValue.state != newValue.state
        val orgChanged = currentSearchValue.showAllOrg != newValue.showAllOrg
        currentSearchValue = newValue

        if (statusChanged || orgChanged) {
            // Need to re-fetch from API
            if (orgChanged && !newValue.showAllOrg) {
                expandedNodes.clear()
            }
            refreshPullRequests()
        } else {
            // Only client-side filtering changed
            applyClientFilters()
        }
    }

    fun refreshPullRequests() {
        statusLabel.text = "Loading Pull Requests..."
        statusLabel.icon = AllIcons.Process.Step_1

        val selectedPrId = getSelectedPullRequest()?.pullRequestId ?: lastSelectedPrId
        val apiStatus = currentSearchValue.state?.apiValue ?: "active"
        val showAllOrg = currentSearchValue.showAllOrg

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Loading Pull Requests...", false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val apiClient = AzureDevOpsApiClient.getInstance(project)
                    val pullRequests = if (showAllOrg) {
                        apiClient.getAllOrganizationPullRequests(status = apiStatus, top = 100)
                    } else {
                        apiClient.getPullRequests(status = apiStatus)
                    }
                    val resolvedCurrentUserId = apiClient.getCurrentUserIdCached()

                    ApplicationManager.getApplication().invokeLater {
                        currentUserId = resolvedCurrentUserId
                        if (isErrorState || hasDataChanged(pullRequests)) {
                            cachedPullRequests = pullRequests
                            lastLoadedPullRequests = pullRequests
                            filterPanel.updateAuthorsFromPullRequests(pullRequests)
                            val filtered = applyAllFilters(pullRequests)
                            updateTreeWithPullRequests(filtered, selectedPrId)
                            updateStatusLabel(filtered.size, pullRequests.size)
                        } else {
                            lastLoadedPullRequests = pullRequests
                            filterPanel.updateAuthorsFromPullRequests(pullRequests)
                            val filtered = applyAllFilters(pullRequests)
                            updateTreeWithPullRequests(filtered, selectedPrId)
                            updateStatusLabel(filtered.size, pullRequests.size)
                        }
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        val isConfigError = e.message?.contains("not configured", ignoreCase = true) == true
                        if (isConfigError) {
                            rootNode.removeAllChildren()
                            treeModel.reload()
                            statusLabel.text = "Azure DevOps not configured"
                            statusLabel.icon = AllIcons.General.Warning
                            isErrorState = true
                        } else {
                            updateTreeWithError(e.message ?: "Unknown error")
                            statusLabel.text = "Error loading Pull Requests"
                            statusLabel.icon = AllIcons.General.Error
                        }
                    }
                }
            }
        })
    }

    fun getSelectedPullRequest(): PullRequest? {
        val selectedNode = tree.lastSelectedPathComponent as? DefaultMutableTreeNode
        return selectedNode?.userObject as? PullRequest
    }

    // ---- Client-side filtering ----

    private fun applyClientFilters() {
        if (isErrorState) return
        val selectedPrId = getSelectedPullRequest()?.pullRequestId ?: lastSelectedPrId
        val filtered = applyAllFilters(lastLoadedPullRequests)
        updateTreeWithPullRequests(filtered, selectedPrId)
        updateStatusLabel(filtered.size, lastLoadedPullRequests.size)
    }

    private fun applyAllFilters(pullRequests: List<PullRequest>): List<PullRequest> {
        var result = pullRequests
        val sv = currentSearchValue

        // Text search
        val query = sv.searchQuery
        if (!query.isNullOrBlank()) {
            val normalizedQuery = query.lowercase()
            result = result.filter { pr ->
                pr.title.lowercase().contains(normalizedQuery) ||
                (pr.createdBy?.displayName?.lowercase()?.contains(normalizedQuery) == true) ||
                (pr.createdBy?.uniqueName?.lowercase()?.contains(normalizedQuery) == true)
            }
        }

        // Author filter
        val author = sv.author
        if (author != null) {
            result = if (author.id == "@me") {
                result.filter { pr -> pr.isCreatedByUser(currentUserId) }
            } else {
                result.filter { pr -> pr.createdBy?.id == author.id || pr.createdBy?.displayName == author.displayName }
            }
        }

        // Review filter
        val review = sv.review
        if (review != null) {
            result = result.filter { pr -> matchesReviewFilter(pr, review) }
        }

        // Sort
        result = when (sv.sort) {
            PullRequestSearchValue.Sort.OLDEST -> result.sortedBy { it.pullRequestId }
            PullRequestSearchValue.Sort.RECENTLY_UPDATED -> result // API already returns most recent
            else -> result.sortedByDescending { it.pullRequestId } // NEWEST is default
        }

        return result
    }

    private fun matchesReviewFilter(pr: PullRequest, review: PullRequestSearchValue.ReviewState): Boolean {
        val reviewers = pr.reviewers ?: emptyList()
        return when (review) {
            PullRequestSearchValue.ReviewState.NO_REVIEW -> {
                reviewers.isEmpty() || reviewers.all { it.vote == 0 || it.vote == null }
            }
            PullRequestSearchValue.ReviewState.APPROVED -> {
                reviewers.any { it.vote == 10 || it.vote == 5 }
            }
            PullRequestSearchValue.ReviewState.CHANGES_REQUESTED -> {
                reviewers.any { it.vote == -5 || it.vote == -10 }
            }
            PullRequestSearchValue.ReviewState.REVIEWED_BY_YOU -> {
                reviewers.any { it.id == currentUserId && (it.vote != null && it.vote != 0) }
            }
        }
    }

    // ---- Tree management ----

    private fun updateTreeWithPullRequests(pullRequests: List<PullRequest>, previouslySelectedPrId: Int? = null) {
        rootNode.removeAllChildren()
        isErrorState = false
        val showAllOrg = currentSearchValue.showAllOrg

        if (pullRequests.isEmpty()) {
            rootNode.add(DefaultMutableTreeNode("No Pull Requests"))
        } else {
            if (showAllOrg) {
                val byProject = pullRequests.groupBy { it.repository?.project?.name ?: "Unknown Project" }
                for ((projectName, prsInProject) in byProject.toSortedMap()) {
                    val projectNode = DefaultMutableTreeNode(ProjectNode(projectName, prsInProject.size))
                    prsInProject.forEach { pr -> projectNode.add(DefaultMutableTreeNode(pr)) }
                    rootNode.add(projectNode)
                }
            } else {
                pullRequests.forEach { pr -> rootNode.add(DefaultMutableTreeNode(pr)) }
            }
        }

        treeModel.reload()

        // Expand project nodes
        val isFirstLoad = expandedNodes.isEmpty() && showAllOrg
        for (i in 0 until tree.rowCount) {
            val path = tree.getPathForRow(i) ?: continue
            val node = path.lastPathComponent as? DefaultMutableTreeNode ?: continue
            val userObject = node.userObject
            if (userObject is ProjectNode) {
                if (isFirstLoad) {
                    expandedNodes.add(userObject.name)
                    tree.expandPath(path)
                } else if (expandedNodes.contains(userObject.name)) {
                    tree.expandPath(path)
                }
            }
        }

        if (previouslySelectedPrId != null) {
            SwingUtilities.invokeLater {
                SwingUtilities.invokeLater { restoreSelection(previouslySelectedPrId) }
            }
        }
    }

    private fun addTreeExpandListener() {
        tree.addTreeExpansionListener(object : javax.swing.event.TreeExpansionListener {
            override fun treeExpanded(event: javax.swing.event.TreeExpansionEvent?) {
                event?.path?.lastPathComponent?.let { node ->
                    if (node is DefaultMutableTreeNode && node.userObject is ProjectNode) {
                        expandedNodes.add((node.userObject as ProjectNode).name)
                    }
                }
            }
            override fun treeCollapsed(event: javax.swing.event.TreeExpansionEvent?) {
                event?.path?.lastPathComponent?.let { node ->
                    if (node is DefaultMutableTreeNode && node.userObject is ProjectNode) {
                        expandedNodes.remove((node.userObject as ProjectNode).name)
                    }
                }
            }
        })
    }

    private fun hasDataChanged(newPullRequests: List<PullRequest>): Boolean {
        if (cachedPullRequests.size != newPullRequests.size) return true
        val cachedMap = cachedPullRequests.associateBy { it.pullRequestId }
        for (newPr in newPullRequests) {
            val cachedPr = cachedMap[newPr.pullRequestId] ?: return true
            if (cachedPr.status != newPr.status ||
                cachedPr.title != newPr.title ||
                cachedPr.isDraft != newPr.isDraft ||
                cachedPr.reviewers?.size != newPr.reviewers?.size) return true
            val cachedReviewers = cachedPr.reviewers?.associateBy { it.id } ?: emptyMap()
            for (newReviewer in (newPr.reviewers ?: emptyList())) {
                if (cachedReviewers[newReviewer.id]?.vote != newReviewer.vote) return true
            }
        }
        return false
    }

    private fun restoreSelection(prId: Int) {
        fun searchInNode(parentNode: DefaultMutableTreeNode): Boolean {
            for (i in 0 until parentNode.childCount) {
                val childNode = parentNode.getChildAt(i) as? DefaultMutableTreeNode ?: continue
                if (childNode.userObject is PullRequest && (childNode.userObject as PullRequest).pullRequestId == prId) {
                    val path = javax.swing.tree.TreePath(childNode.path)
                    tree.selectionPath = path
                    tree.scrollPathToVisible(path)
                    return true
                }
                if (searchInNode(childNode)) return true
            }
            return false
        }
        searchInNode(rootNode)
    }

    private fun showContextMenu(e: MouseEvent) {
        val path = tree.getPathForLocation(e.x, e.y) ?: return
        val node = path.lastPathComponent as? DefaultMutableTreeNode ?: return
        val pr = node.userObject as? PullRequest ?: return
        if (!pr.isActive()) return
        tree.selectionPath = path

        val popup = JBPopupMenu()

        popup.add(JMenuItem("Open Review in Tab").apply {
            addActionListener { PullRequestToolWindowFactory.openPrReviewTab(project, pr) }
        })
        popup.addSeparator()
        popup.add(JMenuItem("Enter This Branch").apply {
            addActionListener {
                paol0b.azuredevops.services.PullRequestBranchService.getInstance(project).enterPullRequestBranch(pr)
            }
        })

        val showAbandonPr = pr.isCreatedByUser(currentUserId)
        val showCompletePR = pr.isReadyToComplete()
        val showAutoComplete = !pr.hasAutoComplete() && !pr.isReadyToComplete()
        if (showAbandonPr || showCompletePR || showAutoComplete) popup.addSeparator()

        if (showAbandonPr) {
            popup.add(JMenuItem("Abandon PR...").apply {
                addActionListener {
                    AbandonPullRequestAction(pr, currentUserId) { refreshPullRequests() }.performAbandonPR(project)
                }
            })
        }
        if (showCompletePR) {
            popup.add(JMenuItem("Complete PR...").apply {
                addActionListener {
                    CompletePullRequestAction(pr) { refreshPullRequests() }.performCompletePR(project)
                }
            })
        }
        if (showAutoComplete) {
            popup.add(JMenuItem("Set Auto-Complete...").apply {
                addActionListener {
                    SetAutoCompletePullRequestAction(pr) { refreshPullRequests() }.performSetAutoComplete(project)
                }
            })
        }

        popup.show(tree, e.x, e.y)
    }

    private fun updateTreeWithError(errorMessage: String) {
        isErrorState = true
        rootNode.removeAllChildren()
        rootNode.add(DefaultMutableTreeNode("Error: $errorMessage"))
        treeModel.reload()
    }

    private fun updateStatusLabel(filteredCount: Int, totalCount: Int) {
        statusLabel.icon = AllIcons.General.InspectionsOK
        statusLabel.text = if (filteredCount < totalCount) {
            "Showing $filteredCount of $totalCount Pull Request(s)"
        } else {
            "Loaded $totalCount Pull Request(s)"
        }
    }

    /**
     * Custom renderer for PRs in the tree
     */
    private inner class PullRequestCellRenderer : ColoredTreeCellRenderer() {
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
                is ProjectNode -> {
                    icon = AllIcons.Nodes.Project
                    append(userObject.name, SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_BOLD,
                        JBColor(Color(0, 95, 184), Color(100, 180, 255))
                    ))
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append("${userObject.prCount}", SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_BOLD,
                        JBColor(Color(255, 255, 255), Color(45, 45, 45))
                    ))
                    append(" PR${if (userObject.prCount != 1) "s" else ""}", SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN, JBColor.GRAY
                    ))
                }

                is PullRequest -> {
                    icon = when (userObject.status) {
                        PullRequestStatus.Active -> {
                            if (userObject.isDraft == true) AllIcons.Vcs.Patch_applied
                            else AllIcons.Vcs.Branch
                        }
                        PullRequestStatus.Completed -> AllIcons.RunConfigurations.TestPassed
                        PullRequestStatus.Abandoned -> AllIcons.RunConfigurations.TestFailed
                        else -> AllIcons.Vcs.Branch
                    }

                    val idColor = when (userObject.status) {
                        PullRequestStatus.Active -> SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            JBColor(Color(0, 122, 204), Color(0, 164, 239))
                        )
                        PullRequestStatus.Completed -> SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            JBColor(Color(106, 153, 85), Color(106, 153, 85))
                        )
                        else -> SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES
                    }
                    append("#${userObject.pullRequestId}", idColor)
                    append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)

                    if (userObject.isDraft == true) {
                        append("DRAFT", SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            JBColor(Color(255, 165, 0), Color(255, 140, 0))
                        ))
                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                    if (userObject.hasAutoComplete()) {
                        append("AUTO-COMPLETE", SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            JBColor(Color(106, 153, 85), Color(106, 200, 85))
                        ))
                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                    if (userObject.isReadyToComplete() && !userObject.hasAutoComplete()) {
                        append("\u2713 READY", SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            JBColor(Color(34, 139, 34), Color(50, 205, 50))
                        ))
                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                    if (userObject.hasConflicts()) {
                        append("\u26A0 CONFLICTS", SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_BOLD,
                            JBColor(Color(220, 50, 50), Color(255, 80, 80))
                        ))
                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }

                    val titleAttrs = if (userObject.status == PullRequestStatus.Active)
                        SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES
                    else SimpleTextAttributes.GRAYED_ATTRIBUTES
                    append(userObject.title, titleAttrs)

                    if (currentSearchValue.showAllOrg) {
                        val repoName = userObject.repository?.name ?: "Unknown"
                        append("  ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        append("[$repoName]", SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_ITALIC,
                            JBColor(Color(128, 128, 128), Color(128, 128, 128))
                        ))
                    }

                    append("\n    ", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    append(userObject.getSourceBranchName(), SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN,
                        JBColor(Color(34, 139, 34), Color(50, 205, 50))
                    ))
                    append(" \u2192 ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.GRAY))
                    append(userObject.getTargetBranchName(), SimpleTextAttributes(
                        SimpleTextAttributes.STYLE_PLAIN,
                        JBColor(Color(70, 130, 180), Color(135, 206, 250))
                    ))

                    userObject.createdBy?.displayName?.let { author ->
                        append("  \u2022  ", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                        append(author, SimpleTextAttributes(
                            SimpleTextAttributes.STYLE_ITALIC,
                            JBColor(Color(100, 150, 200), Color(120, 170, 220))
                        ))
                    }
                }

                is String -> {
                    icon = AllIcons.General.Information
                    append(userObject, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
                }
            }
        }
    }
}
