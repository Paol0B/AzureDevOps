package paol0b.azuredevops.toolwindow.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.icons.AllIcons
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.PrReviewStateService
import java.awt.BorderLayout
import java.awt.FlowLayout
import javax.swing.*

/**
 * Main PR Review Tool Window
 * Master-detail diff viewer with file tree, diff panel, and comments
 */
class PrReviewToolWindow(
    private val project: Project,
    private val showSelector: Boolean = true
) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(PrReviewToolWindow::class.java)
    private val apiClient = AzureDevOpsApiClient.getInstance(project)
    private val reviewStateService = PrReviewStateService.getInstance(project)
    
    private var currentPullRequest: PullRequest? = null
    private var fileTreePanel: FileTreePanel? = null
    private var diffViewerPanel: DiffViewerPanel? = null
    private var commentsPanel: CommentsPanel? = null

    private var leftToolbarPanel: JPanel? = null
    
    // Auto-refresh timer
    private var refreshTimer: Timer? = null
    private val REFRESH_INTERVAL = 30000 // 30 secondi
    
    // UI Components
    private val prSelectorComboBox = ComboBox<PullRequestItem>()
    private val statusComboBox = ComboBox(arrayOf(
        "Select Vote",
        "✅ Approve",
        "🔄 Approve with Suggestions", 
        "⏳ Wait for Author",
        "❌ Reject"
    ))
    private val refreshButton = JButton("🔄").apply {
        toolTipText = "Refresh Pull Requests"
        preferredSize = java.awt.Dimension(40, 30)
    }
    private val showAllOrgPrsButton = JButton().apply {
        icon = AllIcons.Nodes.Folder
        toolTipText = "Show Pull Requests from all projects in the organization"
        isSelected = false
        preferredSize = java.awt.Dimension(40, 30)
        addActionListener {
            isSelected = !isSelected
            updateCrossOrgButtonState()
            refreshPullRequestsList()
        }
    }
    
    // Right panel containing vote status (will be shown/hidden)
    private lateinit var voteStatusPanel: JPanel
    
    // Flag to track if loading is in progress
    @Volatile
    private var isLoading = false
    
    // Flag to ignore programmatic changes to vote combobox
    private var ignoringVoteChange = false
    
    // Error state placeholder
    private var errorPlaceholderPanel: JPanel? = null
    
    private val mainContentPanel = JPanel(BorderLayout())
    private val placeholderPanel = JPanel(BorderLayout()).apply {
        add(JBLabel("Select a Pull Request to review").apply {
            horizontalAlignment = JBLabel.CENTER
            border = JBUI.Borders.empty(20)
        }, BorderLayout.CENTER)
    }

    init {
        setupUI()
        if (showSelector) {
            loadAvailablePullRequests()
        }
    }

    private fun setupUI() {
        // Top toolbar
        val toolbar = createToolbar()
        add(toolbar, BorderLayout.NORTH)
        
        // Main content (placeholder initially)
        add(placeholderPanel, BorderLayout.CENTER)
    }

    /**
     * Create the top toolbar with PR selector and status dropdown
     */
    private fun createToolbar(): JComponent {
        val toolbar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.customLine(com.intellij.ui.JBColor.border(), 0, 0, 1, 0),
                JBUI.Borders.empty(8)
            )
        }
        
        // Left side: PR selector with refresh button
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
            add(JBLabel("Pull Request:"))
            add(prSelectorComboBox.apply {
                preferredSize = java.awt.Dimension(400, 30)
                renderer = object : DefaultListCellRenderer() {
                    override fun getListCellRendererComponent(
                        list: JList<*>?,
                        value: Any?,
                        index: Int,
                        isSelected: Boolean,
                        cellHasFocus: Boolean
                    ): java.awt.Component {
                        val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                        if (value is PullRequestItem) {
                            text = value.displayText
                        }
                        return component
                    }
                }
                addActionListener {
                    val selected = selectedItem as? PullRequestItem
                    if (selected?.pullRequest != null) {
                        loadPullRequest(selected.pullRequest)
                    } else {
                        // Empty selection - clear everything
                        clearPullRequestView()
                    }
                }
            })
            add(refreshButton.apply {
                addActionListener {
                    refreshPullRequestsList()
                }
            })
        }
        leftToolbarPanel = leftPanel
        
        // Right side: Status dropdown and cross-org button (initially hidden)
        voteStatusPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
            add(showAllOrgPrsButton)
            add(JBLabel("Review Status:"))
            add(statusComboBox.apply {
                preferredSize = java.awt.Dimension(220, 30)
                addActionListener {
                    handleVoteChange()
                }
                toolTipText = "Submit your review: Approve, Approve with Suggestions, Wait for Author, or Reject"
            })
            add(JBLabel("(Draft - Voting Disabled)").apply {
                name = "draftVoteLabel"
                isVisible = false
            })
            isVisible = false // Hidden until a PR is selected
        }
        
        toolbar.add(leftPanel, BorderLayout.WEST)
        toolbar.add(voteStatusPanel, BorderLayout.EAST)
        
        if (!showSelector) {
            leftPanel.isVisible = false
        }
        
        return toolbar
    }

    /**
     * Load available pull requests
     */
    private fun loadAvailablePullRequests() {
        if (!showSelector) return
        if (isLoading) return
        isLoading = true
        
        val showAllOrg = showAllOrgPrsButton.isSelected
        
        // Show loading state
        ApplicationManager.getApplication().invokeLater {
            refreshButton.isEnabled = false
            refreshButton.text = "⏳"
        }
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Carica solo le PR attive (esclude quelle chiuse/completate/abbandonate)
                val prs = if (showAllOrg) {
                    apiClient.getAllOrganizationPullRequests(status = "active", top = 100)
                } else {
                    apiClient.getPullRequests(status = "active", top = 50)
                }
                
                ApplicationManager.getApplication().invokeLater {
                    // Clear any error placeholder
                    clearErrorState()
                    
                    prSelectorComboBox.removeAllItems()
                    prSelectorComboBox.addItem(PullRequestItem(null, "-- Select a PR --"))
                    
                    prs.forEach { pr ->
                        val displayText = if (showAllOrg) {
                            // Include repository info when showing all org PRs
                            val repoName = pr.repository?.name ?: "Unknown"
                            "#${pr.pullRequestId} [$repoName] - ${pr.title} (${pr.createdBy?.displayName ?: "Unknown"})"
                        } else {
                            "#${pr.pullRequestId} - ${pr.title} (${pr.createdBy?.displayName ?: "Unknown"})"
                        }
                        prSelectorComboBox.addItem(PullRequestItem(pr, displayText))
                    }
                    
                    // Auto-select last reviewed PR if available
                    reviewStateService.getCurrentPullRequest()?.let { prId ->
                        for (i in 0 until prSelectorComboBox.itemCount) {
                            val item = prSelectorComboBox.getItemAt(i)
                            if (item.pullRequest?.pullRequestId == prId) {
                                prSelectorComboBox.selectedIndex = i
                                break
                            }
                        }
                    }
                    
                    // Restore refresh button
                    refreshButton.isEnabled = true
                    refreshButton.text = "🔄"
                    isLoading = false
                }
            } catch (e: Exception) {
                logger.error("Failed to load pull requests", e)
                ApplicationManager.getApplication().invokeLater {
                    // Show error state but don't block - allow retry
                    showErrorState("Failed to load pull requests: ${e.message}")
                    
                    // Restore refresh button so user can retry
                    refreshButton.isEnabled = true
                    refreshButton.text = "🔄"
                    isLoading = false
                }
            }
        }
    }
    
    /**
     * Refresh the pull requests list (manual refresh)
     */
    fun refreshPullRequestsList() {
        if (!showSelector) return
        logger.info("Manual refresh of pull requests list")
        loadAvailablePullRequests()
    }
    
    /**
     * Update the visual state of the cross-org button
     */
    private fun updateCrossOrgButtonState() {
        if (showAllOrgPrsButton.isSelected) {
            // Button is enabled - add visual highlight
            showAllOrgPrsButton.toolTipText = "✓ Showing Pull Requests from all organization projects"
        } else {
            // Button is disabled - normal state
            showAllOrgPrsButton.toolTipText = "Show Pull Requests from all projects in the organization"
        }
    }

    fun openPullRequest(pullRequest: PullRequest) {
        loadPullRequest(pullRequest)
    }
    
    /**
     * Show error state in the placeholder area
     */
    private fun showErrorState(message: String) {
        // Remove current placeholder/error panel
        errorPlaceholderPanel?.let { remove(it) }
        if (currentPullRequest == null) {
            remove(placeholderPanel)
        }
        
        // Create error panel with retry button
        errorPlaceholderPanel = JPanel(BorderLayout()).apply {
            val errorPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(20)
                
                add(JBLabel("⚠️ $message").apply {
                    alignmentX = java.awt.Component.CENTER_ALIGNMENT
                    border = JBUI.Borders.emptyBottom(10)
                })
                
                add(JButton("🔄 Retry").apply {
                    alignmentX = java.awt.Component.CENTER_ALIGNMENT
                    addActionListener {
                        refreshPullRequestsList()
                    }
                })
            }
            
            add(errorPanel, BorderLayout.CENTER)
        }
        
        if (currentPullRequest == null) {
            add(errorPlaceholderPanel, BorderLayout.CENTER)
        }
        
        revalidate()
        repaint()
    }
    
    /**
     * Clear error state and restore normal placeholder
     */
    private fun clearErrorState() {
        errorPlaceholderPanel?.let { 
            remove(it)
            errorPlaceholderPanel = null
        }
        
        if (currentPullRequest == null) {
            // Only add placeholder if no PR is selected
            val hasPlaceholder = components.any { it == placeholderPanel }
            if (!hasPlaceholder) {
                add(placeholderPanel, BorderLayout.CENTER)
            }
        }
        
        revalidate()
        repaint()
    }
    
    /**
     * Clear the PR view when no PR is selected (empty selection)
     */
    private fun clearPullRequestView() {
        // Stop auto-refresh
        stopAutoRefresh()
        
        // Clear state
        currentPullRequest = null
        reviewStateService.setCurrentPullRequest(null)
        
        // Hide vote status panel
        voteStatusPanel.isVisible = false
        
        // Reset status dropdown (without triggering action listener)
        ignoringVoteChange = true
        statusComboBox.selectedIndex = 0
        ignoringVoteChange = false
        
        // Clear and dispose panels
        fileTreePanel = null
        diffViewerPanel?.dispose()
        diffViewerPanel = null
        commentsPanel = null
        
        // Remove main content and show placeholder
        remove(mainContentPanel)
        mainContentPanel.removeAll()
        
        // Make sure placeholder is showing
        val hasPlaceholder = components.any { it == placeholderPanel }
        if (!hasPlaceholder) {
            add(placeholderPanel, BorderLayout.CENTER)
        }
        
        revalidate()
        repaint()
        
        logger.info("PR view cleared - empty selection")
    }

    /**
     * Load a specific pull request for review
     */
    fun loadPullRequest(pullRequest: PullRequest) {
        // Verifica che la PR sia attiva
        if (!pullRequest.isActive()) {
            JOptionPane.showMessageDialog(
                this,
                "This Pull Request is ${pullRequest.status.getDisplayName()}.\n" +
                "You can only review active Pull Requests.",
                "PR Not Active",
                JOptionPane.WARNING_MESSAGE
            )
            prSelectorComboBox.selectedIndex = 0 // Torna a "-- Select a PR --"
            return
        }
        
        currentPullRequest = pullRequest
        reviewStateService.setCurrentPullRequest(pullRequest.pullRequestId)
        
        // Show vote status panel
        voteStatusPanel.isVisible = true
        ignoringVoteChange = true
        statusComboBox.selectedIndex = 0 // Reset to "Select Vote"
        ignoringVoteChange = false
        
        // Handle draft PR voting restriction
        if (pullRequest.isDraft == true) {
            statusComboBox.isEnabled = false
            statusComboBox.toolTipText = "Voting is disabled for draft Pull Requests. Publish the PR to enable voting."
            val draftLabel = voteStatusPanel.components.find { it.name == "draftVoteLabel" }
            draftLabel?.isVisible = true
        } else {
            statusComboBox.isEnabled = true
            statusComboBox.toolTipText = "Submit your review: Approve, Approve with Suggestions, Wait for Author, or Reject"
            val draftLabel = voteStatusPanel.components.find { it.name == "draftVoteLabel" }
            draftLabel?.isVisible = false
        }
        
        // Avvia auto-refresh
        startAutoRefresh()
        
        // Clear previous content
        remove(placeholderPanel)
        mainContentPanel.removeAll()
        
        // Show loading
        mainContentPanel.add(JBLabel("Loading PR #${pullRequest.pullRequestId}...").apply {
            horizontalAlignment = JBLabel.CENTER
            border = JBUI.Borders.empty(20)
        }, BorderLayout.CENTER)
        add(mainContentPanel, BorderLayout.CENTER)
        revalidate()
        repaint()
        
        // Load PR data in background
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Use repository info from PR if available (for external PRs)
                val projectName = pullRequest.repository?.project?.name
                val repositoryId = pullRequest.repository?.id
                
                val changes = apiClient.getPullRequestChanges(pullRequest.pullRequestId, projectName, repositoryId)
                
                ApplicationManager.getApplication().invokeLater {
                    setupReviewWorkspace(pullRequest, changes)
                }
            } catch (e: Exception) {
                logger.error("Failed to load PR changes", e)
                ApplicationManager.getApplication().invokeLater {
                    mainContentPanel.removeAll()
                    mainContentPanel.add(JBLabel("❌ Failed to load PR: ${e.message}").apply {
                        horizontalAlignment = JBLabel.CENTER
                        border = JBUI.Borders.empty(20)
                    }, BorderLayout.CENTER)
                    mainContentPanel.revalidate()
                    mainContentPanel.repaint()
                }
            }
        }
    }

    /**
     * Setup the review workspace with file tree, diff viewer, and comments
     */
    private fun setupReviewWorkspace(pullRequest: PullRequest, changes: List<paol0b.azuredevops.model.PullRequestChange>) {
        mainContentPanel.removeAll()
        
        // Create panels
        fileTreePanel = FileTreePanel(project, pullRequest.pullRequestId).apply {
            loadFileChanges(changes)
            
            // Connect file selection to diff viewer
            addFileSelectionListener { change ->
                diffViewerPanel?.loadDiff(change)
            }
        }
        
        // Get external repository info if available (for cross-repo PRs)
        val externalProjectName = pullRequest.repository?.project?.name
        val externalRepositoryId = pullRequest.repository?.id
        
        logger.info("Setting up review workspace: PR #${pullRequest.pullRequestId}, externalProject=$externalProjectName, externalRepo=$externalRepositoryId")
        logger.info("  PR repository info: ${pullRequest.repository}")
        
        diffViewerPanel = DiffViewerPanel(project, pullRequest.pullRequestId, externalProjectName, externalRepositoryId)
        
        commentsPanel = CommentsPanel(project, pullRequest.pullRequestId, externalProjectName, externalRepositoryId)
        
        // Layout: Left (File Tree) | Right (Diff Viewer + Comments)
        val leftRightSplitter = JBSplitter(false, 0.25f).apply {
            firstComponent = fileTreePanel
            
            // Right side: Diff Viewer (top) + Comments (bottom)
            val topBottomSplitter = JBSplitter(true, 0.7f).apply {
                firstComponent = diffViewerPanel
                secondComponent = commentsPanel
            }
            secondComponent = topBottomSplitter
        }
        
        mainContentPanel.add(leftRightSplitter, BorderLayout.CENTER)
        mainContentPanel.revalidate()
        mainContentPanel.repaint()
        
        logger.info("Loaded PR #${pullRequest.pullRequestId} with ${changes.size} file changes")
    }

    /**
     * Handle vote/status change
     */
    private fun handleVoteChange() {
        // Ignore programmatic changes
        if (ignoringVoteChange) return
        
        val selectedIndex = statusComboBox.selectedIndex
        val pr = currentPullRequest ?: return
        
        // Check if PR is a draft - cannot vote on draft PRs
        if (pr.isDraft == true) {
            JOptionPane.showMessageDialog(
                this,
                "You cannot vote on draft Pull Requests.\nPublish the PR first to enable voting.",
                "Draft PR - Voting Disabled",
                JOptionPane.INFORMATION_MESSAGE
            )
            ignoringVoteChange = true
            statusComboBox.selectedIndex = 0
            ignoringVoteChange = false
            return
        }
        
        // Se seleziona "Select Vote" (index 0), non fare nulla (è lo stato di default)
        if (selectedIndex == 0) {
            return
        }
        
        // Map UI selection to Azure DevOps vote values
        // 10 = Approved, 5 = Approved with suggestions, -5 = Waiting for author, -10 = Rejected
        val vote = when (selectedIndex) {
            1 -> 10  // Approve
            2 -> 5   // Approve with Suggestions
            3 -> -5  // Wait for Author
            4 -> -10 // Reject
            else -> return
        }
        
        val voteText = statusComboBox.selectedItem as? String ?: "Unknown"
        
        val confirmed = JOptionPane.showConfirmDialog(
            this,
            "Submit review as: $voteText?",
            "Confirm Review",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )
        
        if (confirmed != JOptionPane.YES_OPTION) {
            ignoringVoteChange = true
            statusComboBox.selectedIndex = 0
            ignoringVoteChange = false
            return
        }
        
        // Submit vote in background
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                apiClient.voteOnPullRequest(pr, vote)
                reviewStateService.savePrVote(pr.pullRequestId, vote)
                
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(
                        this,
                        "Review submitted successfully: $voteText",
                        "Success",
                        JOptionPane.INFORMATION_MESSAGE
                    )
                }
            } catch (e: Exception) {
                logger.error("Failed to submit vote", e)
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(
                        this,
                        "Failed to submit review: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                    ignoringVoteChange = true
                    statusComboBox.selectedIndex = 0
                    ignoringVoteChange = false
                }
            }
        }
    }

    /**
     * Start auto-refresh for the current PR
     */
    private fun startAutoRefresh() {
        // Stop existing timer if any
        stopAutoRefresh()
        
        // Create new timer
        refreshTimer = Timer(REFRESH_INTERVAL) {
            refreshCurrentPullRequest()
        }.apply {
            isRepeats = true
            start()
        }
        
        logger.info("Auto-refresh started for PR #${currentPullRequest?.pullRequestId}")
    }
    
    /**
     * Stop auto-refresh timer
     */
    private fun stopAutoRefresh() {
        refreshTimer?.stop()
        refreshTimer = null
        logger.info("Auto-refresh stopped")
    }
    
    /**
     * Refresh the current pull request data
     */
    private fun refreshCurrentPullRequest() {
        val pr = currentPullRequest ?: return
        
        // Get repository info from current PR (for external PRs)
        val projectName = pr.repository?.project?.name
        val repositoryId = pr.repository?.id
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Ricarica i dati della PR using repository info
                val updatedPr = apiClient.getPullRequest(pr.pullRequestId, projectName, repositoryId)
                
                // Se la PR non è più attiva, ferma il refresh e notifica l'utente
                if (!updatedPr.isActive()) {
                    ApplicationManager.getApplication().invokeLater {
                        stopAutoRefresh()
                        JOptionPane.showMessageDialog(
                            this,
                            "PR #${updatedPr.pullRequestId} is now ${updatedPr.status.getDisplayName()}.\n" +
                            "Auto-refresh has been stopped.",
                            "PR Status Changed",
                            JOptionPane.INFORMATION_MESSAGE
                        )
                        // Torna alla selezione vuota
                        prSelectorComboBox.selectedIndex = 0
                        currentPullRequest = null
                        remove(mainContentPanel)
                        add(placeholderPanel, BorderLayout.CENTER)
                        revalidate()
                        repaint()
                    }
                    return@executeOnPooledThread
                }
                
                // Ricarica i cambiamenti using repository info
                val changes = apiClient.getPullRequestChanges(updatedPr.pullRequestId, projectName, repositoryId)
                
                ApplicationManager.getApplication().invokeLater {
                    // Aggiorna il pannello dei file
                    fileTreePanel?.loadFileChanges(changes)
                    logger.info("Auto-refreshed PR #${updatedPr.pullRequestId}")
                }
            } catch (e: Exception) {
                logger.warn("Failed to auto-refresh PR", e)
            }
        }
    }
    
    /**
     * Cleanup resources
     */
    fun dispose() {
        stopAutoRefresh()
        diffViewerPanel?.dispose()
    }

    /**
     * Wrapper for PR items in combo box
     */
    private data class PullRequestItem(
        val pullRequest: PullRequest?,
        val displayText: String
    )
}
