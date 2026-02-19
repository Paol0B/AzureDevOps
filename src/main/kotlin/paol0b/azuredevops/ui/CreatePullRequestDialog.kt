package paol0b.azuredevops.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ui.ChangesBrowserNode
import com.intellij.openapi.vcs.changes.ui.ChangesTree
import com.intellij.ui.JBColor
import com.intellij.ui.components.*
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import git4idea.GitCommit
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepository
import paol0b.azuredevops.model.GitBranch
import paol0b.azuredevops.model.Identity
import paol0b.azuredevops.services.AvatarService
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.GitRepositoryService
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.Ellipse2D
import javax.swing.*
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel

/**
 * Dialog data loaded before showing the dialog
 */
data class DialogData(
    val branches: List<GitBranch>,
    val currentBranch: GitBranch?,
    val defaultTarget: GitBranch?,
    val availableReviewers: List<Identity>,
    val initialChanges: List<Change>,
    val initialCommits: List<GitCommit>
)

/**
 * Dialog to create a Pull Request on Azure DevOps
 */
class CreatePullRequestDialog private constructor(
    private val project: Project,
    private val gitService: GitRepositoryService,
    private val dialogData: DialogData
) : DialogWrapper(project) {

    private val logger = Logger.getInstance(CreatePullRequestDialog::class.java)
    private val avatarService = AvatarService.getInstance(project)
    private val sourceBranchCombo: ComboBox<GitBranch>
    private val targetBranchCombo: ComboBox<GitBranch>
    private val titleField = JBTextField()
    private val descriptionArea = JBTextArea()
    private val changesTree: JTree
    private val commitsListModel = DefaultListModel<String>()
    private val commitsList = JList(commitsListModel)
    
    // Reviewer fields
    private val reviewerComboModel = DefaultComboBoxModel<Identity>()
    private val reviewerCombo: ComboBox<Identity>
    private val reviewerListPanel = JPanel() // Panel for added reviewers
    private val requiredReviewers = mutableListOf<Identity>()
    private val optionalReviewers = mutableListOf<Identity>()
    private val apiClient = AzureDevOpsApiClient.getInstance(project)

    companion object {
        /**
         * Factory method to create the dialog with pre-loaded data
         */
        fun create(project: Project, gitService: GitRepositoryService): CreatePullRequestDialog? {
            var dialogData: DialogData? = null
            var error: String? = null
            
            // Load all data with progress indicator
            ProgressManager.getInstance().run(object : Task.Modal(project, "Loading Pull Request Data...", true) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.text = "Loading branches..."
                        indicator.fraction = 0.1
                        val branches = gitService.getAllBranches()
                        val currentBranch = gitService.getCurrentBranch()
                        val defaultTarget = gitService.getDefaultTargetBranch()
                        
                        if (branches.isEmpty()) {
                            error = "No Git branches found in the repository."
                            return
                        }
                        
                        indicator.text = "Loading reviewers..."
                        indicator.fraction = 0.3
                        val apiClient = AzureDevOpsApiClient.getInstance(project)
                        val reviewers = try {
                            apiClient.searchIdentities("")
                        } catch (e: Exception) {
                            Logger.getInstance(CreatePullRequestDialog::class.java)
                                .warn("Failed to load reviewers, continuing without them", e)
                            emptyList()
                        }
                        
                        indicator.text = "Loading changes..."
                        indicator.fraction = 0.6
                        val changes = if (currentBranch != null && defaultTarget != null) {
                            try {
                                gitService.getChangesBetweenBranches(currentBranch.name, defaultTarget.name)
                            } catch (e: Exception) {
                                Logger.getInstance(CreatePullRequestDialog::class.java)
                                    .warn("Failed to load initial changes", e)
                                emptyList()
                            }
                        } else {
                            emptyList()
                        }
                        
                        indicator.text = "Loading commits..."
                        indicator.fraction = 0.8
                        val commits = if (currentBranch != null && defaultTarget != null) {
                            try {
                                gitService.getCommitsBetweenBranches(currentBranch.name, defaultTarget.name)
                            } catch (e: Exception) {
                                Logger.getInstance(CreatePullRequestDialog::class.java)
                                    .warn("Failed to load initial commits", e)
                                emptyList()
                            }
                        } else {
                            emptyList()
                        }
                        
                        indicator.fraction = 1.0
                        
                        dialogData = DialogData(
                            branches = branches,
                            currentBranch = currentBranch,
                            defaultTarget = defaultTarget,
                            availableReviewers = reviewers,
                            initialChanges = changes,
                            initialCommits = commits
                        )
                    } catch (e: Exception) {
                        error = "Error loading data: ${e.message}"
                        Logger.getInstance(CreatePullRequestDialog::class.java).error("Failed to load dialog data", e)
                    }
                }
            })
            
            if (error != null) {
                Messages.showErrorDialog(project, error, "Error")
                return null
            }
            
            return dialogData?.let { CreatePullRequestDialog(project, gitService, it) }
        }
    }

    init {
        title = "Create Azure DevOps Pull Request"
        
        // Setup combo boxes with pre-loaded data
        sourceBranchCombo = ComboBox(dialogData.branches.toTypedArray())
        targetBranchCombo = ComboBox(dialogData.branches.toTypedArray())

        // Set default values
        dialogData.currentBranch?.let { current ->
            sourceBranchCombo.selectedItem = dialogData.branches.firstOrNull { it.displayName == current.displayName }
        }

        dialogData.defaultTarget?.let { target ->
            targetBranchCombo.selectedItem = dialogData.branches.firstOrNull { it.displayName == target.displayName }
        }

        // Set renderer to show only displayName
        sourceBranchCombo.renderer = BranchListCellRenderer()
        targetBranchCombo.renderer = BranchListCellRenderer()

        // Description area setup
        descriptionArea.lineWrap = true
        descriptionArea.wrapStyleWord = true
        descriptionArea.rows = 12

        // Setup changes tree with initial data
        val rootNode = DefaultMutableTreeNode("Changes")
        changesTree = JTree(DefaultTreeModel(rootNode))
        changesTree.isRootVisible = false
        changesTree.showsRootHandles = true
        
        // Populate initial changes
        populateChangesTree(rootNode, dialogData.initialChanges)

        // Setup commits list with initial data
        commitsList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        populateCommitsList(dialogData.initialCommits)
        
        // Setup reviewer panels
        reviewerListPanel.layout = BoxLayout(reviewerListPanel, BoxLayout.Y_AXIS)
        reviewerListPanel.border = JBUI.Borders.empty(5)
        
        // Setup reviewer combo with pre-loaded reviewers
        val comboRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(
                list: JList<*>?,
                value: Any?,
                index: Int,
                isSelected: Boolean,
                cellHasFocus: Boolean
            ): Component {
                val label = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
                when (value) {
                    is Identity -> {
                        label.text = value.displayName ?: value.uniqueName ?: "Unknown User"
                    }
                    null -> {
                        label.text = "Select a user to add as reviewer..."
                    }
                }
                return label
            }
        }
        
        // Initialize combo with reviewers already loaded
        reviewerCombo = ComboBox(reviewerComboModel).apply {
            renderer = comboRenderer
            isEditable = false
        }
        
        // Populate reviewers
        if (dialogData.availableReviewers.isEmpty()) {
            logger.warn("No reviewers available")
        } else {
            logger.info("Populating ${dialogData.availableReviewers.size} reviewers into combo")
            dialogData.availableReviewers.forEach { identity ->
                reviewerComboModel.addElement(identity)
            }
            logger.info("Combo populated with ${reviewerComboModel.size} items")
        }

        // Load changes and commits when branches change
        sourceBranchCombo.addActionListener {
            loadChangesAndCommits()
        }
        targetBranchCombo.addActionListener {
            loadChangesAndCommits()
        }

        init()
    }

    private fun populateChangesTree(rootNode: DefaultMutableTreeNode, changes: List<Change>) {
        rootNode.removeAllChildren()

        if (changes.isEmpty()) {
            rootNode.add(DefaultMutableTreeNode("No changes"))
        } else {
            // Group changes by directory
            val changesByDir = changes.groupBy { change ->
                val path = change.afterRevision?.file?.path ?: change.beforeRevision?.file?.path ?: "Unknown"
                path.substringBeforeLast('/', "")
            }

            changesByDir.forEach { (dir, dirChanges) ->
                val dirNode = DefaultMutableTreeNode(if (dir.isEmpty()) "/" else dir)
                dirChanges.forEach { change ->
                    val fileName = change.afterRevision?.file?.name ?: change.beforeRevision?.file?.name ?: "Unknown"
                    val changeType = when {
                        change.beforeRevision == null -> "[A] " // Added
                        change.afterRevision == null -> "[D] " // Deleted
                        else -> "[M] " // Modified
                    }
                    dirNode.add(DefaultMutableTreeNode("$changeType$fileName"))
                }
                rootNode.add(dirNode)
            }
        }

        val model = changesTree.model as DefaultTreeModel
        model.reload()
        // Expand all nodes
        for (i in 0 until changesTree.rowCount) {
            changesTree.expandRow(i)
        }
    }
    
    private fun populateCommitsList(commits: List<GitCommit>) {
        commitsListModel.clear()

        if (commits.isEmpty()) {
            commitsListModel.addElement("No commits")
        } else {
            commits.forEach { commit ->
                commitsListModel.addElement("${commit.id.toShortString()} - ${commit.subject}")
            }
        }
    }

    private fun loadChangesAndCommits() {
        val sourceBranch = sourceBranchCombo.selectedItem as? GitBranch
        val targetBranch = targetBranchCombo.selectedItem as? GitBranch

        if (sourceBranch == null || targetBranch == null) {
            return
        }

        // Run Git operations in background thread
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Load changes
                val changes = gitService.getChangesBetweenBranches(sourceBranch.name, targetBranch.name)
                
                // Load commits
                val commits = gitService.getCommitsBetweenBranches(sourceBranch.name, targetBranch.name)

                // Update UI on EDT
                ApplicationManager.getApplication().invokeLater {
                    val rootNode = (changesTree.model as DefaultTreeModel).root as DefaultMutableTreeNode
                    populateChangesTree(rootNode, changes)
                    populateCommitsList(commits)
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    val rootNode = (changesTree.model as DefaultTreeModel).root as DefaultMutableTreeNode
                    rootNode.removeAllChildren()
                    rootNode.add(DefaultMutableTreeNode("Error: ${e.message}"))
                    (changesTree.model as DefaultTreeModel).reload()
                    
                    commitsListModel.clear()
                    commitsListModel.addElement("Error: ${e.message}")
                }
            }
        }
    }
    
    /**
     * Adds a reviewer to the list
     */
    private fun addReviewer(identity: Identity, required: Boolean) {
        // Check if already added
        val alreadyAdded = (requiredReviewers + optionalReviewers).any { it.id == identity.id }
        if (alreadyAdded) {
            Messages.showWarningDialog(
                project,
                "${identity.displayName} is already added as a reviewer.",
                "Reviewer Already Added"
            )
            return
        }
        
        if (required) {
            requiredReviewers.add(identity)
        } else {
            optionalReviewers.add(identity)
        }
        
        // Add the reviewer to the list
        val reviewerPanel = createReviewerPanel(identity, required)
        reviewerListPanel.add(reviewerPanel)
        
        reviewerListPanel.revalidate()
        reviewerListPanel.repaint()
        
        // Reset combo selection
        reviewerCombo.selectedIndex = -1
    }
    
    /**
     * Creates a panel for an added reviewer with circular avatar
     */
    private fun createReviewerPanel(identity: Identity, required: Boolean): JPanel {
        val panel = JPanel(BorderLayout(JBUI.scale(10), 0)).apply {
            name = "added_reviewer_${identity.id}"
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(JBUI.scale(8))
            )
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(50))
        }
        
        // Left: Avatar (circular)
        val avatarPanel = object : JPanel() {
            private var avatarIcon: Icon? = null
            
            init {
                preferredSize = Dimension(JBUI.scale(36), JBUI.scale(36))
                isOpaque = false
                // Load avatar asynchronously
                avatarIcon = avatarService.getAvatar(identity.imageUrl, JBUI.scale(36)) {
                    repaint()
                }
            }
            
            override fun paintComponent(g: Graphics) {
                super.paintComponent(g)
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                
                // Draw circular border (light gray)
                g2.color = JBColor(Color(200, 200, 200), Color(80, 80, 80))
                g2.stroke = BasicStroke(JBUI.scale(1f))
                g2.drawOval(0, 0, width - JBUI.scale(1), height - JBUI.scale(1))
                
                // Draw avatar if available
                avatarIcon?.let { icon ->
                    val x = (width - icon.iconWidth) / 2
                    val y = (height - icon.iconHeight) / 2
                    
                    // Clip to circle
                    val clip = Ellipse2D.Float(JBUI.scale(1f), JBUI.scale(1f), 
                        (width - JBUI.scale(2)).toFloat(), (height - JBUI.scale(2)).toFloat())
                    g2.clip = clip
                    icon.paintIcon(this, g2, x, y)
                }
                g2.dispose()
            }
        }
        
        // Center: Name + Badge
        val centerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentY = Component.CENTER_ALIGNMENT
        }
        
        val nameLabel = JLabel(identity.displayName ?: "Unknown").apply {
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD, UIUtil.getFontSize(UIUtil.FontSize.NORMAL))
            alignmentX = Component.LEFT_ALIGNMENT
        }
        centerPanel.add(nameLabel)
        
        val badge = JLabel(if (required) "REQUIRED" else "OPTIONAL").apply {
            isOpaque = true
            background = if (required) JBColor(Color(220, 53, 69), Color(200, 35, 51)) 
                         else JBColor(Color(108, 117, 125), Color(90, 98, 104))
            foreground = Color.WHITE
            border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(6), JBUI.scale(2), JBUI.scale(6))
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(9f).toFloat())
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(18))
        }
        centerPanel.add(Box.createVerticalStrut(JBUI.scale(2)))
        centerPanel.add(badge)
        
        panel.add(avatarPanel, BorderLayout.WEST)
        panel.add(centerPanel, BorderLayout.CENTER)
        
        // Right: Remove button
        val removeBtn = JButton(AllIcons.Actions.Close).apply {
            toolTipText = "Remove reviewer"
            preferredSize = Dimension(JBUI.scale(28), JBUI.scale(28))
            isContentAreaFilled = false
            isBorderPainted = false
            addActionListener {
                removeReviewer(identity, required)
                reviewerListPanel.remove(panel)
                reviewerListPanel.revalidate()
                reviewerListPanel.repaint()
            }
        }
        panel.add(removeBtn, BorderLayout.EAST)
        
        return panel
    }
    
    /**
     * Removes a reviewer from the list
     */
    private fun removeReviewer(identity: Identity, required: Boolean) {
        if (required) {
            requiredReviewers.remove(identity)
        } else {
            optionalReviewers.remove(identity)
        }
    }

    override fun createCenterPanel(): JComponent {
        val descriptionScroll = JScrollPane(descriptionArea)

        val insertCommitsBtn = JButton("Insert Commits", AllIcons.Actions.Commit).apply {
            toolTipText = "Insert commit messages into description"
            addActionListener { insertCommitsIntoDescription() }
        }

        val descriptionItemPanel = JPanel(BorderLayout(0, 5)).apply {
            border = JBUI.Borders.empty(0, 0, 5, 0)
            val topBar = JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply {
                add(insertCommitsBtn)
            }
            add(topBar, BorderLayout.NORTH)
            add(descriptionScroll, BorderLayout.CENTER)
        }

        // Details Panel - using BorderLayout for better layout control
        val detailsPanel = JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(10)
            
            // Top section: source, target, title
            val topSection = FormBuilder.createFormBuilder()
                .addLabeledComponent(JBLabel("Source Branch:"), sourceBranchCombo, 1, false)
                .addTooltip("The branch to start the Pull Request from")
                .addLabeledComponent(JBLabel("Target Branch:"), targetBranchCombo, 1, false)
                .addTooltip("The destination branch (usually main or master)")
                .addSeparator()
                .addLabeledComponent(JBLabel("Title:"), titleField, 1, false)
                .addTooltip("Pull Request title (required)")
                .panel
            
            add(topSection, BorderLayout.NORTH)
            
            // Bottom section: description (fills remaining space)
            val descriptionContainer = JPanel(BorderLayout()).apply {
                add(JBLabel("Description:"), BorderLayout.NORTH)
                add(descriptionItemPanel, BorderLayout.CENTER)
            }
            add(descriptionContainer, BorderLayout.CENTER)
        }

        // Changes Panel
        val changesPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(JBLabel("Files changed between branches:"), BorderLayout.NORTH)
            add(JScrollPane(changesTree), BorderLayout.CENTER)
        }

        // Commits Panel
        val commitsPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
            add(JBLabel("Commits to be included:"), BorderLayout.NORTH)
            add(JScrollPane(commitsList), BorderLayout.CENTER)
        }
        
        // Reviewers Panel
        val reviewersPanel = JPanel(BorderLayout(0, JBUI.scale(12))).apply {
            border = JBUI.Borders.empty(JBUI.scale(10))
        }
        
        val reviewersTopPanel = JPanel(BorderLayout()).apply {
            val infoLabel = JBLabel("<html><b>Add Reviewers</b><br>" +
                    "<small>Select users and add them as required or optional reviewers</small></html>")
            add(infoLabel, BorderLayout.NORTH)
        }
        
        val selectionPanel = JPanel(BorderLayout(0, JBUI.scale(5))).apply {
            border = JBUI.Borders.empty(JBUI.scale(5), 0)
        }
        
        val comboPanel = JPanel(BorderLayout(JBUI.scale(5), 0))
        comboPanel.add(JBLabel("Select User:"), BorderLayout.WEST)
        comboPanel.add(reviewerCombo, BorderLayout.CENTER)
        
        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(5), 0))
        val addRequiredButton = JButton("Add as Required").apply {
            addActionListener {
                val selected = reviewerCombo.selectedItem as? Identity
                if (selected != null) {
                    addReviewer(selected, required = true)
                }
            }
        }
        val addOptionalButton = JButton("Add as Optional").apply {
            addActionListener {
                val selected = reviewerCombo.selectedItem as? Identity
                if (selected != null) {
                    addReviewer(selected, required = false)
                }
            }
        }
        buttonsPanel.add(addRequiredButton)
        buttonsPanel.add(addOptionalButton)
        
        selectionPanel.add(comboPanel, BorderLayout.NORTH)
        selectionPanel.add(buttonsPanel, BorderLayout.CENTER)
        
        // Added reviewers section
        val addedReviewersLabel = JBLabel("<html><b>Added Reviewers:</b> <span style='color:gray;'>(${requiredReviewers.size + optionalReviewers.size})</span></html>").apply {
            border = JBUI.Borders.empty(JBUI.scale(5), 0)
        }
        
        val addedReviewersContainer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(JBUI.scale(5))
            )
            add(addedReviewersLabel, BorderLayout.NORTH)
            add(reviewerListPanel, BorderLayout.CENTER)
        }
        
        val reviewersScrollPane = JBScrollPane(addedReviewersContainer).apply {
            preferredSize = Dimension(500, JBUI.scale(200))
            border = null
        }
        
        reviewersPanel.add(reviewersTopPanel, BorderLayout.NORTH)
        reviewersPanel.add(selectionPanel, BorderLayout.CENTER)
        reviewersPanel.add(reviewersScrollPane, BorderLayout.SOUTH)

        // Tabbed Pane
        val tabbedPane = JBTabbedPane().apply {
            addTab("Details", detailsPanel)
            addTab("Changes", changesPanel)
            addTab("Commits", commitsPanel)
            addTab("Reviewers", reviewersPanel)
            preferredSize = Dimension(600, 550)
        }

        return tabbedPane
    }

    override fun doValidate(): ValidationInfo? {
        if (titleField.text.isBlank()) {
            return ValidationInfo("Title is required", titleField)
        }

        val sourceBranch = sourceBranchCombo.selectedItem as? GitBranch
        val targetBranch = targetBranchCombo.selectedItem as? GitBranch

        if (sourceBranch == null) {
            return ValidationInfo("Select a source branch", sourceBranchCombo)
        }

        if (targetBranch == null) {
            return ValidationInfo("Select a target branch", targetBranchCombo)
        }

        if (sourceBranch.name == targetBranch.name) {
            return ValidationInfo("Source and target branch cannot be the same", sourceBranchCombo)
        }

        return null
    }

    /**
     * Inserts the current commits list into the description area in a formatted way
     */
    private fun insertCommitsIntoDescription() {
        if (commitsListModel.isEmpty
            || commitsListModel.getElementAt(0) == "No commits"
            || commitsListModel.getElementAt(0).startsWith("Error:")) {
            return
        }

        val sb = StringBuilder()
        sb.append("## Commits\n\n")
        for (i in 0 until commitsListModel.size()) {
            sb.append("- ${commitsListModel.getElementAt(i)}\n")
        }

        val existing = descriptionArea.text.trim()
        descriptionArea.text = if (existing.isNotBlank()) {
            "$existing\n\n${sb.toString().trimEnd()}"
        } else {
            sb.toString().trimEnd()
        }
    }

    /**
     * Gets the selected source branch
     */
    fun getSourceBranch(): GitBranch? = sourceBranchCombo.selectedItem as? GitBranch

    /**
     * Gets the selected target branch
     */
    fun getTargetBranch(): GitBranch? = targetBranchCombo.selectedItem as? GitBranch

    /**
     * Gets the PR title
     */
    fun getPrTitle(): String = titleField.text.trim()

    /**
     * Gets the PR description
     */
    fun getDescription(): String = descriptionArea.text.trim()
    
    /**
     * Gets the list of required reviewers
     */
    fun getRequiredReviewers(): List<Identity> = requiredReviewers.toList()
    
    /**
     * Gets the list of optional reviewers
     */
    fun getOptionalReviewers(): List<Identity> = optionalReviewers.toList()

    /**
     * Custom renderer to show only the displayName of branches
     */
    private class BranchListCellRenderer : com.intellij.ui.SimpleListCellRenderer<GitBranch>() {
        override fun customize(
            list: javax.swing.JList<out GitBranch>,
            value: GitBranch?,
            index: Int,
            selected: Boolean,
            hasFocus: Boolean
        ) {
            text = value?.displayName ?: ""
        }
    }
}
