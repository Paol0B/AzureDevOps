package paol0b.azuredevops.toolwindow.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
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
    private val project: Project
) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(PrReviewToolWindow::class.java)
    private val apiClient = AzureDevOpsApiClient.getInstance(project)
    private val reviewStateService = PrReviewStateService.getInstance(project)
    
    private var currentPullRequest: PullRequest? = null
    private var fileTreePanel: FileTreePanel? = null
    private var diffViewerPanel: DiffViewerPanel? = null
    private var commentsPanel: CommentsPanel? = null
    
    // UI Components
    private val prSelectorComboBox = ComboBox<PullRequestItem>()
    private val statusComboBox = ComboBox(arrayOf(
        "Select Vote",
        "✅ Approve",
        "🔄 Approve with Suggestions", 
        "⏳ Wait for Author",
        "❌ Reject"
    ))
    
    private val mainContentPanel = JPanel(BorderLayout())
    private val placeholderPanel = JPanel(BorderLayout()).apply {
        add(JBLabel("Select a Pull Request to review").apply {
            horizontalAlignment = JBLabel.CENTER
            border = JBUI.Borders.empty(20)
        }, BorderLayout.CENTER)
    }

    init {
        setupUI()
        loadAvailablePullRequests()
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
        
        // Left side: PR selector
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
                    selected?.pullRequest?.let { loadPullRequest(it) }
                }
            })
        }
        
        // Right side: Status dropdown
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
            add(JBLabel("Review Status:"))
            add(statusComboBox.apply {
                preferredSize = java.awt.Dimension(220, 30)
                addActionListener {
                    handleVoteChange()
                }
            })
        }
        
        toolbar.add(leftPanel, BorderLayout.WEST)
        toolbar.add(rightPanel, BorderLayout.EAST)
        
        return toolbar
    }

    /**
     * Load available pull requests
     */
    private fun loadAvailablePullRequests() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val prs = apiClient.getPullRequests(status = "active", top = 50)
                
                ApplicationManager.getApplication().invokeLater {
                    prSelectorComboBox.removeAllItems()
                    prSelectorComboBox.addItem(PullRequestItem(null, "-- Select a PR --"))
                    
                    prs.forEach { pr ->
                        prSelectorComboBox.addItem(PullRequestItem(pr, 
                            "#${pr.pullRequestId} - ${pr.title} (${pr.createdBy?.displayName ?: "Unknown"})"))
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
                }
            } catch (e: Exception) {
                logger.error("Failed to load pull requests", e)
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(
                        this,
                        "Failed to load pull requests: ${e.message}",
                        "Error",
                        JOptionPane.ERROR_MESSAGE
                    )
                }
            }
        }
    }

    /**
     * Load a specific pull request for review
     */
    private fun loadPullRequest(pullRequest: PullRequest) {
        currentPullRequest = pullRequest
        reviewStateService.setCurrentPullRequest(pullRequest.pullRequestId)
        
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
                val changes = apiClient.getPullRequestChanges(pullRequest.pullRequestId)
                
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
        
        diffViewerPanel = DiffViewerPanel(project, pullRequest.pullRequestId)
        
        commentsPanel = CommentsPanel(project, pullRequest.pullRequestId)
        
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
        val selectedIndex = statusComboBox.selectedIndex
        if (selectedIndex <= 0) return // "Select Vote" option
        
        val pr = currentPullRequest ?: return
        
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
            statusComboBox.selectedIndex = 0
            return
        }
        
        // Submit vote in background
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                apiClient.voteOnPullRequest(pr.pullRequestId, vote)
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
                    statusComboBox.selectedIndex = 0
                }
            }
        }
    }

    /**
     * Cleanup resources
     */
    fun dispose() {
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
