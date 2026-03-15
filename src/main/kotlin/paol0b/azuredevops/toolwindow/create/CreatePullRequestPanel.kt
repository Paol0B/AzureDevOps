package paol0b.azuredevops.toolwindow.create

import com.intellij.icons.AllIcons
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.vcs.changes.Change
import com.intellij.ui.IdeBorderFactory
import com.intellij.ui.JBColor
import com.intellij.ui.JBSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBOptionButton
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.treeStructure.Tree
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import git4idea.GitCommit
import paol0b.azuredevops.model.GitBranch
import paol0b.azuredevops.model.Identity
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.GitRepositoryService
import paol0b.azuredevops.util.NotificationUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Inline Create Pull Request panel for the PR ToolWindow.
 *
 * Layout follows the JetBrains GitHub plugin pattern:
 *
 * ┌──────────────────────────────┐
 * │  base:main ← head:feature   │  direction selector
 * │  Changes from N commits ▼   │  commit count
 * ├──────────────────────────────┤
 * │  ▸ src/                      │
 * │    M  file.kt                │  changes tree
 * │    A  newFile.kt             │
 * ├══════════════════════════════┤  splitter
 * │  Title  [_______________]    │
 * │  Description                 │
 * │  [                      ]    │
 * │  Reviewers   No reviewers ✎  │
 * ├──────────────────────────────┤
 * │  [Create Pull Request ▼]    │  JBOptionButton with Draft option
 * └──────────────────────────────┘
 */
class CreatePullRequestPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val logger = Logger.getInstance(CreatePullRequestPanel::class.java)
    private val gitService = GitRepositoryService.getInstance(project)
    private val apiClient = AzureDevOpsApiClient.getInstance(project)

    // ── Data ────────────────────────────────────────────────────────────
    private var branches: List<GitBranch> = emptyList()
    private var currentChanges: List<Change> = emptyList()
    private var currentCommits: List<GitCommit> = emptyList()
    private var availableReviewers: List<Identity> = emptyList()
    private val selectedReviewers = mutableListOf<ReviewerEntry>()

    data class ReviewerEntry(val identity: Identity, val required: Boolean)

    /** Guard to suppress [onBranchesChanged] while populating combos. */
    private var isPopulatingCombos = false

    // ── UI Components ───────────────────────────────────────────────────

    // Branch selectors
    private val sourceBranchCombo = ComboBox<GitBranch>()
    private val targetBranchCombo = ComboBox<GitBranch>()
    private val commitCountLabel = JBLabel("Changes from 0 commits")

    // Changes tree
    private val changesTreeRoot = DefaultMutableTreeNode("root")
    private val changesTreeModel = DefaultTreeModel(changesTreeRoot)
    private val changesTree = Tree(changesTreeModel)

    // Title & Description
    private val titleField = JBTextField()
    private val descriptionArea = JBTextArea().apply {
        lineWrap = true
        wrapStyleWord = true
        rows = 6
    }

    // Reviewers
    private val reviewersPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2))
    private val reviewerEditButton = JBLabel("✎").apply {
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        toolTipText = "Edit reviewers"
    }

    // Reusable diff virtual file — same tab is reused for every file click
    private val diffVirtualFile = CreatePrDiffVirtualFile.create()

    // Action buttons
    private lateinit var createButton: JBOptionButton

    // ── Callbacks ────────────────────────────────────────────────────────
    var onPullRequestCreated: (() -> Unit)? = null

    init {
        buildUI()
        loadData()
    }

    private fun buildUI() {
        val topPanel = buildTopPanel()
        val bottomPanel = buildBottomPanel()

        val splitter = JBSplitter(true, 0.45f).apply {
            firstComponent = topPanel
            secondComponent = bottomPanel
            setHonorComponentsMinimumSize(true)
        }

        add(splitter, BorderLayout.CENTER)
    }

    // ====================================================================
    //  Top Panel: branch direction + changes tree
    // ====================================================================

    private fun buildTopPanel(): JComponent {
        val panel = JPanel(BorderLayout()).apply { isOpaque = false }

        // Direction selector
        val directionPanel = buildDirectionPanel()
        panel.add(directionPanel, BorderLayout.NORTH)

        // Changes tree with optional diff preview
        val changesPanel = buildChangesPanel()
        panel.add(changesPanel, BorderLayout.CENTER)

        return panel
    }

    private fun buildDirectionPanel(): JComponent {
        val renderer = object : com.intellij.ui.SimpleListCellRenderer<GitBranch>() {
            override fun customize(
                list: JList<out GitBranch>, value: GitBranch?, index: Int,
                selected: Boolean, hasFocus: Boolean
            ) {
                text = value?.displayName ?: ""
                icon = AllIcons.Vcs.Branch
            }
        }
        sourceBranchCombo.renderer = renderer
        targetBranchCombo.renderer = renderer

        sourceBranchCombo.addActionListener { onBranchesChanged() }
        targetBranchCombo.addActionListener { onBranchesChanged() }

        val branchPanel = JPanel(GridBagLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12)
            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.HORIZONTAL
                insets = Insets(2, 4, 2, 4)
            }

            // source:feature → target:main
            gbc.gridx = 0; gbc.gridy = 0; gbc.weightx = 0.45
            add(sourceBranchCombo, gbc)

            gbc.gridx = 1; gbc.weightx = 0.0
            add(JBLabel(" \u2192 ").apply {  // → arrow
                font = font.deriveFont(Font.BOLD, 14f)
            }, gbc)

            gbc.gridx = 2; gbc.weightx = 0.45
            add(targetBranchCombo, gbc)

            // Commit count
            gbc.gridx = 0; gbc.gridy = 1; gbc.gridwidth = 3; gbc.weightx = 1.0
            commitCountLabel.apply {
                font = JBUI.Fonts.smallFont()
                foreground = UIUtil.getContextHelpForeground()
            }
            add(commitCountLabel, gbc)
        }

        return branchPanel
    }

    private fun buildChangesPanel(): JComponent {
        changesTree.isRootVisible = false
        changesTree.showsRootHandles = true
        changesTree.cellRenderer = ChangeFileTreeCellRenderer()

        // Double-click to open diff in editor tab
        changesTree.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val node = changesTree.lastSelectedPathComponent as? DefaultMutableTreeNode ?: return
                    val data = node.userObject as? ChangeNodeData ?: return
                    if (data.change != null) {
                        openDiffInEditor(data.change)
                    }
                }
            }
        })

        val treeScroll = JBScrollPane(changesTree).apply {
            border = JBUI.Borders.empty()
        }

        return JPanel(BorderLayout()).apply {
            border = IdeBorderFactory.createBorder(com.intellij.ui.SideBorder.TOP)
            add(treeScroll, BorderLayout.CENTER)
        }
    }

    // ====================================================================
    //  Bottom Panel: title, description, reviewers, actions
    // ====================================================================

    private fun buildBottomPanel(): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = IdeBorderFactory.createBorder(com.intellij.ui.SideBorder.TOP)
        }

        // Title + Description
        val textPanel = buildTextPanel()
        panel.add(textPanel, BorderLayout.CENTER)

        // Metadata (reviewers) + Actions
        val footerPanel = JPanel(BorderLayout()).apply { isOpaque = false }

        val metadataPanel = buildMetadataPanel()
        footerPanel.add(metadataPanel, BorderLayout.CENTER)

        val actionsPanel = buildActionsPanel()
        footerPanel.add(actionsPanel, BorderLayout.SOUTH)

        panel.add(footerPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun buildTextPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 4)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 12)
        }

        titleField.emptyText.text = "Title"
        titleField.border = JBUI.Borders.compound(
            JBUI.Borders.customLine(JBColor.border()),
            JBUI.Borders.empty(4, 8)
        )

        // Description label row with "insert commits" icon button
        val descHeaderRow = JPanel(BorderLayout()).apply { isOpaque = false }
        descHeaderRow.add(JBLabel("Description").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont()
        }, BorderLayout.WEST)

        val insertCommitsBtn = JButton(AllIcons.Actions.Commit).apply {
            toolTipText = "Insert commit messages into description"
            preferredSize = Dimension(22, 22)
            isContentAreaFilled = false
            isBorderPainted = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            addActionListener { insertCommitsIntoDescription() }
        }
        descHeaderRow.add(insertCommitsBtn, BorderLayout.EAST)

        val descScroll = JBScrollPane(descriptionArea).apply {
            border = JBUI.Borders.customLine(JBColor.border())
        }

        val topRow = JPanel(BorderLayout(0, 4)).apply {
            isOpaque = false
            add(titleField, BorderLayout.NORTH)
            add(descHeaderRow, BorderLayout.SOUTH)
        }

        panel.add(topRow, BorderLayout.NORTH)
        panel.add(descScroll, BorderLayout.CENTER)

        return panel
    }

    private fun insertCommitsIntoDescription() {
        if (currentCommits.isEmpty()) return

        val sb = StringBuilder()
        sb.append("## Commits\n\n")
        currentCommits.forEach { commit ->
            sb.append("- ${commit.id.toShortString()} - ${commit.subject}\n")
        }

        val existing = descriptionArea.text.trim()
        descriptionArea.text = if (existing.isNotBlank()) {
            "$existing\n\n${sb.toString().trimEnd()}"
        } else {
            sb.toString().trimEnd()
        }
    }

    private fun buildMetadataPanel(): JComponent {
        val panel = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = JBColor.lazy { com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().globalScheme.defaultBackground }
            border = JBUI.Borders.compound(
                IdeBorderFactory.createBorder(com.intellij.ui.SideBorder.TOP or com.intellij.ui.SideBorder.BOTTOM),
                JBUI.Borders.empty(8, 12)
            )
        }

        val reviewersRow = JPanel(BorderLayout(8, 0)).apply { isOpaque = false }

        val reviewersLabel = JBLabel("Reviewers").apply {
            foreground = UIUtil.getContextHelpForeground()
            font = JBUI.Fonts.smallFont()
        }
        reviewersRow.add(reviewersLabel, BorderLayout.WEST)

        reviewersPanel.isOpaque = false
        updateReviewersDisplay()
        reviewersRow.add(reviewersPanel, BorderLayout.CENTER)

        reviewerEditButton.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                showReviewerChooser(e.component)
            }
        })
        reviewersRow.add(reviewerEditButton, BorderLayout.EAST)

        panel.add(reviewersRow, BorderLayout.CENTER)

        return panel
    }

    private fun buildActionsPanel(): JComponent {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, 10)
        }

        val createAction = object : AbstractAction("Create Pull Request") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                doCreatePullRequest(isDraft = false)
            }
        }
        val createDraftAction = object : AbstractAction("Create Draft Pull Request") {
            override fun actionPerformed(e: java.awt.event.ActionEvent?) {
                doCreatePullRequest(isDraft = true)
            }
        }

        createButton = JBOptionButton(createAction, arrayOf(createDraftAction))

        // Make it the primary/default styled button (blue in Darcula & New UI)
        SwingUtilities.invokeLater {
            createButton.rootPane?.defaultButton = createButton
        }

        panel.add(createButton)

        return panel
    }

    // ====================================================================
    //  Data Loading
    // ====================================================================

    private fun loadData() {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, "Loading PR data...", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.text = "Loading branches..."
                indicator.fraction = 0.2
                val loadedBranches = gitService.getAllBranches()
                val currentBranch = gitService.getCurrentBranch()
                val defaultTarget = gitService.getDefaultTargetBranch()

                indicator.fraction = 1.0

                ApplicationManager.getApplication().invokeLater {
                    branches = loadedBranches

                    // Populate combos — suppress onBranchesChanged during bulk add
                    isPopulatingCombos = true
                    try {
                        sourceBranchCombo.removeAllItems()
                        targetBranchCombo.removeAllItems()
                        loadedBranches.forEach {
                            sourceBranchCombo.addItem(it)
                            targetBranchCombo.addItem(it)
                        }

                        // Set defaults
                        currentBranch?.let { curr ->
                            loadedBranches.firstOrNull { it.displayName == curr.displayName }?.let {
                                sourceBranchCombo.selectedItem = it
                            }
                        }
                        defaultTarget?.let { def ->
                            loadedBranches.firstOrNull { it.displayName == def.displayName }?.let {
                                targetBranchCombo.selectedItem = it
                            }
                        }
                    } finally {
                        isPopulatingCombos = false
                    }

                    // Auto-fill title from branch name
                    currentBranch?.let { autoFillTitle(it) }

                    // Now trigger a single load of changes
                    onBranchesChanged()
                }
            }
        })
    }

    private fun autoFillTitle(branch: GitBranch) {
        val name = branch.displayName
        // Convert branch name like "feature/my-cool-thing" to "My cool thing"
        val title = name
            .substringAfterLast('/')
            .replace('-', ' ')
            .replace('_', ' ')
            .replaceFirstChar { it.uppercaseChar() }
        titleField.text = title
    }

    private fun onBranchesChanged() {
        if (isPopulatingCombos) return
        val source = sourceBranchCombo.selectedItem as? GitBranch ?: return
        val target = targetBranchCombo.selectedItem as? GitBranch ?: return

        if (source.name == target.name) {
            commitCountLabel.text = "Source and target branches are the same"
            changesTreeRoot.removeAllChildren()
            changesTreeModel.reload()
            currentChanges = emptyList()
            currentCommits = emptyList()
            return
        }

        commitCountLabel.text = "Loading changes..."
        commitCountLabel.icon = AllIcons.Process.Step_1

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val changes = gitService.getChangesBetweenBranches(source.name, target.name)
                val commits = gitService.getCommitsBetweenBranches(source.name, target.name)

                ApplicationManager.getApplication().invokeLater {
                    currentChanges = changes
                    currentCommits = commits
                    commitCountLabel.icon = null
                    commitCountLabel.text = "Changes from ${commits.size} commit${if (commits.size != 1) "s" else ""}"
                    populateChangesTree(changes)
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    commitCountLabel.icon = AllIcons.General.Error
                    commitCountLabel.text = "Error loading changes: ${e.message}"
                }
            }
        }
    }

    // ====================================================================
    //  Changes Tree
    // ====================================================================

    data class ChangeNodeData(
        val displayName: String,
        val change: Change?,       // null = directory node
        val changeType: ChangeType = ChangeType.MODIFIED
    )

    enum class ChangeType(val badge: String, val color: JBColor?) {
        ADDED("A", JBColor(Color(0x2A7A3B), Color(0x57A65B))),
        DELETED("D", JBColor(Color(0xB22222), Color(0xE06C75))),
        MODIFIED("M", JBColor(Color(0x154360), Color(0x64B5F6))),
        RENAMED("R", JBColor(Color(0x2171B5), Color(0x56B6C2)));
    }

    private fun populateChangesTree(changes: List<Change>) {
        changesTreeRoot.removeAllChildren()

        if (changes.isEmpty()) {
            changesTreeRoot.add(DefaultMutableTreeNode(ChangeNodeData("No changes", null)))
            changesTreeModel.reload()
            return
        }

        // Group by directory
        val byDir = changes.groupBy { change ->
            val path = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path ?: ""
            val dir = path.substringBeforeLast('/', "")
            if (dir.isEmpty()) "/" else dir
        }

        byDir.toSortedMap().forEach { (dir, dirChanges) ->
            val dirNode = DefaultMutableTreeNode(ChangeNodeData(dir, null))
            dirChanges.sortedBy {
                it.afterRevision?.file?.name ?: it.beforeRevision?.file?.name ?: ""
            }.forEach { change ->
                val fileName = change.afterRevision?.file?.name ?: change.beforeRevision?.file?.name ?: "Unknown"
                val type = when {
                    change.beforeRevision == null -> ChangeType.ADDED
                    change.afterRevision == null -> ChangeType.DELETED
                    else -> ChangeType.MODIFIED
                }
                dirNode.add(DefaultMutableTreeNode(ChangeNodeData(fileName, change, type)))
            }
            changesTreeRoot.add(dirNode)
        }

        changesTreeModel.reload()
        // Expand all
        for (i in 0 until changesTree.rowCount) {
            changesTree.expandRow(i)
        }
    }

    // ====================================================================
    //  Diff in Editor Tab (reuses single virtual file)
    // ====================================================================

    private fun openDiffInEditor(change: Change) {
        val source = (sourceBranchCombo.selectedItem as? GitBranch)?.displayName ?: "Changes"
        val target = (targetBranchCombo.selectedItem as? GitBranch)?.displayName ?: "Base"

        val fileName = change.afterRevision?.file?.name ?: change.beforeRevision?.file?.name ?: "file"
        diffVirtualFile.displayFileName = fileName

        val fem = FileEditorManager.getInstance(project)

        // Open (or focus) the single diff tab
        val editors = fem.openFile(diffVirtualFile, true)

        // Find our editor and update its content
        val editor = editors.filterIsInstance<CreatePrDiffFileEditor>().firstOrNull()
        editor?.showChange(change, source, target)
    }

    // ====================================================================
    //  Reviewers
    // ====================================================================

    private fun updateReviewersDisplay() {
        reviewersPanel.removeAll()

        if (selectedReviewers.isEmpty()) {
            reviewersPanel.add(JBLabel("No reviewers").apply {
                foreground = UIUtil.getContextHelpForeground()
            })
        } else {
            selectedReviewers.forEach { entry ->
                val chip = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
                    isOpaque = true
                    background = JBColor(Color(0xE8F0FE), Color(0x2D3748))
                    border = JBUI.Borders.empty(2, 6)

                    add(JBLabel(AllIcons.General.User))
                    add(JBLabel(entry.identity.displayName ?: "Unknown").apply {
                        font = JBUI.Fonts.smallFont()
                    })
                    if (entry.required) {
                        add(JBLabel("required").apply {
                            font = JBUI.Fonts.miniFont()
                            foreground = JBColor(Color(0xB22222), Color(0xE06C75))
                        })
                    }

                    // Remove on click
                    cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                    toolTipText = "Click to remove"
                    addMouseListener(object : MouseAdapter() {
                        override fun mouseClicked(e: MouseEvent) {
                            selectedReviewers.remove(entry)
                            updateReviewersDisplay()
                        }
                    })
                }
                reviewersPanel.add(chip)
            }
        }

        reviewersPanel.revalidate()
        reviewersPanel.repaint()
    }

    private var isReviewersLoading = false

    private fun showReviewerChooser(anchor: Component) {
        // Lazy-load reviewers on first click
        if (availableReviewers.isEmpty() && !isReviewersLoading) {
            isReviewersLoading = true
            ApplicationManager.getApplication().executeOnPooledThread {
                val reviewers = try {
                    apiClient.searchIdentities("")
                } catch (e: Exception) {
                    logger.warn("Failed to load reviewers", e)
                    emptyList()
                }
                ApplicationManager.getApplication().invokeLater {
                    availableReviewers = reviewers
                    isReviewersLoading = false
                    if (reviewers.isNotEmpty()) {
                        showReviewerChooser(anchor)
                    } else {
                        NotificationUtil.warning(project, "No Reviewers", "Could not load available reviewers.")
                    }
                }
            }
            return
        }

        if (availableReviewers.isEmpty()) return

        // Filter out already-added reviewers
        val available = availableReviewers.filter { identity ->
            selectedReviewers.none { it.identity.id == identity.id }
        }

        if (available.isEmpty()) {
            NotificationUtil.info(project, "Reviewers", "All available reviewers have already been added.")
            return
        }

        val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(available)
            .setTitle("Add Reviewer")
            .setRenderer(object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean
                ): Component {
                    val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                    if (value is Identity) {
                        label.text = value.displayName ?: value.uniqueName ?: "Unknown"
                        label.icon = AllIcons.General.User
                    }
                    return label
                }
            })
            .setItemChosenCallback { identity ->
                // Show required/optional sub-popup
                SwingUtilities.invokeLater {
                    showReviewerTypePopup(anchor, identity)
                }
            }
            .createPopup()

        popup.showUnderneathOf(anchor)
    }

    private fun showReviewerTypePopup(anchor: Component, identity: Identity) {
        val items = listOf("Required", "Optional")
        val popup = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items)
            .setTitle(identity.displayName ?: "Reviewer")
            .setItemChosenCallback { choice ->
                val required = choice == "Required"
                selectedReviewers.add(ReviewerEntry(identity, required))
                updateReviewersDisplay()
            }
            .createPopup()
        popup.showUnderneathOf(anchor)
    }

    // ====================================================================
    //  Create Pull Request
    // ====================================================================

    private fun doCreatePullRequest(isDraft: Boolean) {
        val source = sourceBranchCombo.selectedItem as? GitBranch
        val target = targetBranchCombo.selectedItem as? GitBranch
        val title = titleField.text.trim()
        val description = descriptionArea.text.trim()

        // Validation
        if (title.isBlank()) {
            NotificationUtil.warning(project, "Validation Error", "Title is required.")
            titleField.requestFocusInWindow()
            return
        }
        if (source == null || target == null) {
            NotificationUtil.warning(project, "Validation Error", "Select source and target branches.")
            return
        }
        if (source.name == target.name) {
            NotificationUtil.warning(project, "Validation Error", "Source and target branches cannot be the same.")
            return
        }

        createButton.isEnabled = false

        val requiredReviewers = selectedReviewers.filter { it.required }.map { it.identity }
        val optionalReviewers = selectedReviewers.filter { !it.required }.map { it.identity }

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, if (isDraft) "Creating draft pull request..." else "Creating pull request...", false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    // Check existing PR
                    indicator.text = "Checking for existing PRs..."
                    val existing = apiClient.findActivePullRequest(source.name, target.name)
                    if (existing != null) {
                        ApplicationManager.getApplication().invokeLater {
                            createButton.isEnabled = true
                            NotificationUtil.warning(
                                project, "PR Already Exists",
                                "There is already an active PR #${existing.pullRequestId}: ${existing.title}"
                            )
                        }
                        return
                    }

                    // Create PR
                    indicator.text = "Creating pull request..."
                    val response = apiClient.createPullRequest(
                        sourceBranch = source.name,
                        targetBranch = target.name,
                        title = title,
                        description = description,
                        requiredReviewers = requiredReviewers,
                        optionalReviewers = optionalReviewers,
                        isDraft = isDraft
                    )

                    ApplicationManager.getApplication().invokeLater {
                        createButton.isEnabled = true
                        val prType = if (isDraft) "Draft PR" else "PR"
                        NotificationUtil.info(
                            project,
                            "$prType created successfully",
                            "$prType #${response.pullRequestId}: ${response.title}"
                        )
                        onPullRequestCreated?.invoke()
                    }
                } catch (e: Exception) {
                    logger.error("Failed to create pull request", e)
                    ApplicationManager.getApplication().invokeLater {
                        createButton.isEnabled = true
                        NotificationUtil.error(
                            project, "Failed to create Pull Request",
                            e.message ?: "Unknown error"
                        )
                    }
                }
            }
        })
    }

    // ====================================================================
    //  Changes Tree Cell Renderer
    // ====================================================================

    private inner class ChangeFileTreeCellRenderer : ColoredTreeCellRenderer() {
        override fun customizeCellRenderer(
            tree: JTree, value: Any?, selected: Boolean, expanded: Boolean,
            leaf: Boolean, row: Int, hasFocus: Boolean
        ) {
            val node = value as? DefaultMutableTreeNode ?: return
            val data = node.userObject as? ChangeNodeData ?: return

            if (data.change == null) {
                // Directory node
                icon = AllIcons.Nodes.Folder
                append(data.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            } else {
                // File node
                val ft = FileTypeManager.getInstance().getFileTypeByFileName(data.displayName)
                icon = ft.icon

                val ct = data.changeType
                val style = if (ct == ChangeType.DELETED) SimpleTextAttributes.STYLE_STRIKEOUT else SimpleTextAttributes.STYLE_PLAIN
                append(data.displayName, SimpleTextAttributes(style, ct.color))
                append(" ${ct.badge}", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, ct.color))
            }
        }
    }

    override fun dispose() {
        // Nothing to dispose — diffs are opened in editor tabs managed by the platform
    }
}
