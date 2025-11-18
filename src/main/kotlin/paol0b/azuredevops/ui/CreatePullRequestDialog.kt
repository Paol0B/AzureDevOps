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
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.GitRepositoryService
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.net.URL
import javax.imageio.ImageIO
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
 * Dialog per creare una Pull Request su Azure DevOps
 */
class CreatePullRequestDialog private constructor(
    private val project: Project,
    private val gitService: GitRepositoryService,
    private val dialogData: DialogData
) : DialogWrapper(project) {

    private val logger = Logger.getInstance(CreatePullRequestDialog::class.java)
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
                            error = "Nessun branch Git trovato nel repository."
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

        // Imposta i valori di default
        dialogData.currentBranch?.let { current ->
            sourceBranchCombo.selectedItem = dialogData.branches.firstOrNull { it.displayName == current.displayName }
        }

        dialogData.defaultTarget?.let { target ->
            targetBranchCombo.selectedItem = dialogData.branches.firstOrNull { it.displayName == target.displayName }
        }

        // Imposta il renderer per mostrare solo il displayName
        sourceBranchCombo.renderer = BranchListCellRenderer()
        targetBranchCombo.renderer = BranchListCellRenderer()

        // Descrizione area setup
        descriptionArea.lineWrap = true
        descriptionArea.wrapStyleWord = true
        descriptionArea.rows = 8

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
     * Aggiunge un reviewer alla lista
     */
    private fun addReviewer(identity: Identity, required: Boolean) {
        // Verifica se già aggiunto
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
        
        // Aggiungi il reviewer alla lista
        val reviewerPanel = createReviewerPanel(identity, required)
        reviewerListPanel.add(reviewerPanel)
        
        reviewerListPanel.revalidate()
        reviewerListPanel.repaint()
        
        // Reset combo selection
        reviewerCombo.selectedIndex = -1
    }
    
    /**
     * Crea un panel per un reviewer aggiunto
     */
    private fun createReviewerPanel(identity: Identity, required: Boolean): JPanel {
        val panel = JPanel(BorderLayout(5, 0)).apply {
            name = "added_reviewer_${identity.id}"
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(5)
            )
            maximumSize = Dimension(Int.MAX_VALUE, 45)
        }
        
        // Avatar + Nome + Badge
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
        
        val avatarLabel = JLabel()
        avatarLabel.preferredSize = Dimension(28, 28)
        loadAvatar(identity.imageUrl, avatarLabel)
        leftPanel.add(avatarLabel)
        
        val nameLabel = JLabel(identity.displayName ?: "Unknown")
        leftPanel.add(nameLabel)
        
        val badge = JLabel(if (required) "REQUIRED" else "OPTIONAL").apply {
            isOpaque = true
            background = if (required) JBColor(Color(220, 53, 69), Color(200, 35, 51)) 
                         else JBColor(Color(108, 117, 125), Color(90, 98, 104))
            foreground = Color.WHITE
            border = JBUI.Borders.empty(2, 8)
            font = font.deriveFont(Font.BOLD, 10f)
        }
        leftPanel.add(badge)
        
        panel.add(leftPanel, BorderLayout.CENTER)
        
        // Pulsante rimuovi
        val removeBtn = JButton(AllIcons.Actions.Close).apply {
            toolTipText = "Remove reviewer"
            preferredSize = Dimension(24, 24)
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
     * Rimuove un reviewer dalla lista
     */
    private fun removeReviewer(identity: Identity, required: Boolean) {
        if (required) {
            requiredReviewers.remove(identity)
        } else {
            optionalReviewers.remove(identity)
        }
    }
    
    /**
     * Carica l'avatar di un utente in modo asincrono
     */
    private fun loadAvatar(imageUrl: String?, targetLabel: JLabel) {
        if (imageUrl.isNullOrBlank()) {
            targetLabel.icon = AllIcons.General.User
            return
        }
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val url = URL(imageUrl)
                val image = ImageIO.read(url)
                val scaledImage = image.getScaledInstance(
                    targetLabel.preferredSize.width,
                    targetLabel.preferredSize.height,
                    Image.SCALE_SMOOTH
                )
                
                ApplicationManager.getApplication().invokeLater {
                    targetLabel.icon = ImageIcon(scaledImage)
                }
            } catch (e: Exception) {
                logger.debug("Failed to load avatar", e)
                ApplicationManager.getApplication().invokeLater {
                    targetLabel.icon = AllIcons.General.User
                }
            }
        }
    }

    override fun createCenterPanel(): JComponent {
        val descriptionScroll = JScrollPane(descriptionArea).apply {
            preferredSize = Dimension(500, 150)
        }

        // Details Panel
        val detailsPanel = FormBuilder.createFormBuilder()
            .addLabeledComponent(JBLabel("Source Branch:"), sourceBranchCombo, 1, false)
            .addTooltip("Il branch da cui partire per la Pull Request")
            .addLabeledComponent(JBLabel("Target Branch:"), targetBranchCombo, 1, false)
            .addTooltip("Il branch di destinazione (solitamente main o master)")
            .addSeparator()
            .addLabeledComponent(JBLabel("Title:"), titleField, 1, false)
            .addTooltip("Titolo della Pull Request (obbligatorio)")
            .addLabeledComponent(JBLabel("Description:"), descriptionScroll, 1, true)
            .addTooltip("Descrizione opzionale della Pull Request")
            .addComponentFillVertically(JPanel(), 0)
            .panel

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
        val reviewersPanel = JPanel(BorderLayout(0, 10)).apply {
            border = JBUI.Borders.empty(10)
        }
        
        val reviewersTopPanel = JPanel(BorderLayout()).apply {
            val infoLabel = JBLabel("<html><b>Add Reviewers</b><br>" +
                    "<small>Select users and add them as required or optional reviewers</small></html>")
            add(infoLabel, BorderLayout.NORTH)
        }
        
        val selectionPanel = JPanel(BorderLayout(0, 5)).apply {
            border = JBUI.Borders.empty(5, 0)
        }
        
        val comboPanel = JPanel(BorderLayout(5, 0))
        comboPanel.add(JBLabel("Select User:"), BorderLayout.WEST)
        comboPanel.add(reviewerCombo, BorderLayout.CENTER)
        
        val buttonsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0))
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
        
        // Sezione reviewer aggiunti
        val addedReviewersContainer = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(5)
            )
            add(JBLabel("<html><b>Added Reviewers:</b></html>"), BorderLayout.NORTH)
            add(reviewerListPanel, BorderLayout.CENTER)
        }
        
        val reviewersScrollPane = JBScrollPane(addedReviewersContainer).apply {
            preferredSize = Dimension(500, 200)
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
            preferredSize = Dimension(600, 400)
        }

        return tabbedPane
    }

    override fun doValidate(): ValidationInfo? {
        if (titleField.text.isBlank()) {
            return ValidationInfo("Il titolo è obbligatorio", titleField)
        }

        val sourceBranch = sourceBranchCombo.selectedItem as? GitBranch
        val targetBranch = targetBranchCombo.selectedItem as? GitBranch

        if (sourceBranch == null) {
            return ValidationInfo("Seleziona un branch di origine", sourceBranchCombo)
        }

        if (targetBranch == null) {
            return ValidationInfo("Seleziona un branch di destinazione", targetBranchCombo)
        }

        if (sourceBranch.name == targetBranch.name) {
            return ValidationInfo("Il branch di origine e destinazione non possono essere uguali", sourceBranchCombo)
        }

        return null
    }

    /**
     * Ottiene il branch di origine selezionato
     */
    fun getSourceBranch(): GitBranch? = sourceBranchCombo.selectedItem as? GitBranch

    /**
     * Ottiene il branch di destinazione selezionato
     */
    fun getTargetBranch(): GitBranch? = targetBranchCombo.selectedItem as? GitBranch

    /**
     * Ottiene il titolo della PR
     */
    fun getPrTitle(): String = titleField.text.trim()

    /**
     * Ottiene la descrizione della PR
     */
    fun getDescription(): String = descriptionArea.text.trim()
    
    /**
     * Ottiene la lista di reviewer required
     */
    fun getRequiredReviewers(): List<Identity> = requiredReviewers.toList()
    
    /**
     * Ottiene la lista di reviewer optional
     */
    fun getOptionalReviewers(): List<Identity> = optionalReviewers.toList()

    /**
     * Custom renderer per visualizzare solo il displayName dei branch
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
