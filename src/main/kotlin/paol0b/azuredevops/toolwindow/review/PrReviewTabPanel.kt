package paol0b.azuredevops.toolwindow.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.*
import paol0b.azuredevops.services.AvatarService
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.PrReviewStateService
import paol0b.azuredevops.services.PrReviewTabService
import java.awt.*
import javax.swing.*

/**
 * Main PR Review Tab Panel — shown inside the "Azure DevOps PRs" tool window
 * when the user double-clicks on a Pull Request.
 *
 * Layout (top-to-bottom):
 *   - Header: PR title, branch info, commit count, View Timeline button
 *   - File tree with filters (All / Reviewed / Unreviewed) and reviewed checkboxes
 *   - Policy checks status
 *   - Reviewers with avatars and vote status
 *   - Vote dropdown at bottom
 */
class PrReviewTabPanel(
    private val project: Project,
    private val pullRequest: PullRequest
) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(PrReviewTabPanel::class.java)
    private val apiClient = AzureDevOpsApiClient.getInstance(project)
    private val avatarService = AvatarService.getInstance(project)
    private val reviewStateService = PrReviewStateService.getInstance(project)

    // Panels
    private var fileTreePanel: FileTreePanel? = null
    private val reviewersContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
    }
    private val policyChecksContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
    }

    // File tree filter
    private val fileFilterCombo = ComboBox(arrayOf("All Files", "Reviewed Files", "Unreviewed Files")).apply {
        selectedIndex = 0
        preferredSize = Dimension(160, 28)
    }

    // Vote dropdown
    private val voteComboBox = ComboBox(arrayOf(
        "Select Vote",
        "✅ Approve",
        "🔄 Approve with Suggestions",
        "⏳ Wait for Author",
        "❌ Reject"
    )).apply {
        preferredSize = Dimension(250, 32)
    }

    // State
    private var commitCount = 0
    private var ignoringVoteChange = false
    private var refreshTimer: Timer? = null
    private val REFRESH_INTERVAL = 30000
    private var activeCommentThreads: List<CommentThread> = emptyList()

    init {
        background = UIUtil.getPanelBackground()
        setupUI()
        loadData()
        startAutoRefresh()
    }

    private fun setupUI() {
        val scrollContent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(0)
        }

        // === HEADER SECTION ===
        scrollContent.add(createHeaderPanel())
        scrollContent.add(createSeparator())

        // === FILE TREE SECTION ===
        scrollContent.add(createFileTreeSection())

        // === POLICY CHECKS SECTION ===
        scrollContent.add(createSeparator())
        scrollContent.add(createPolicyChecksSection())

        // === REVIEWERS SECTION ===
        scrollContent.add(createSeparator())
        scrollContent.add(createReviewersSection())

        // Glue - Place before vote to allow reviewers to expand
        scrollContent.add(Box.createVerticalGlue())

        // === VOTE SECTION ===
        scrollContent.add(createSeparator())
        scrollContent.add(createVoteSection())

        val scrollPane = JBScrollPane(scrollContent).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 16
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }

        add(scrollPane, BorderLayout.CENTER)
    }

    // ========================
    //  HEADER
    // ========================

    private fun createHeaderPanel(): JPanel {
        val header = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(12, 14, 8, 14)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // PR Title
        val titleLabel = JBLabel("<html><b style='font-size:13px;'>${escapeHtml(pullRequest.title)}</b></html>").apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(6)
        }
        header.add(titleLabel)

        // Branch info + commit count
        val branchPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            background = UIUtil.getPanelBackground()
            alignmentX = Component.LEFT_ALIGNMENT
        }
        branchPanel.add(JBLabel(pullRequest.getSourceBranchName()).apply {
            icon = AllIcons.Vcs.BranchNode
            foreground = JBColor(Color(34, 139, 34), Color(50, 200, 50))
            font = font.deriveFont(Font.BOLD, 12f)
        })
        branchPanel.add(JBLabel("→").apply {
            font = font.deriveFont(Font.BOLD, 16f)
            foreground = JBColor.GRAY
        })
        branchPanel.add(JBLabel(pullRequest.getTargetBranchName()).apply {
            icon = AllIcons.Vcs.BranchNode
            foreground = JBColor(Color(70, 130, 180), Color(100, 149, 237))
            font = font.deriveFont(Font.BOLD, 12f)
        })
        branchPanel.add(Box.createHorizontalStrut(12))
        branchPanel.add(JBLabel("Changes from").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(11f)
        })
        val commitCountLabel = JBLabel("... commits").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.BOLD, 11f)
            name = "commitCountLabel"
        }
        branchPanel.add(commitCountLabel)
        header.add(branchPanel)

        // Status badges + View Timeline button
        val actionsPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            background = UIUtil.getPanelBackground()
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // Status badges
        if (pullRequest.isDraft == true) {
            actionsPanel.add(createBadge("DRAFT", JBColor(Color(255, 165, 0), Color(255, 140, 0))))
        }
        if (pullRequest.hasAutoComplete()) {
            actionsPanel.add(createBadge("AUTO-COMPLETE", JBColor(Color(106, 153, 85), Color(106, 200, 85))))
        }
        if (pullRequest.hasConflicts()) {
            actionsPanel.add(createBadge("⚠ CONFLICTS", JBColor(Color(220, 50, 50), Color(255, 80, 80))))
        }

        // View Timeline button
        val timelineButton = JButton("View Timeline").apply {
            icon = AllIcons.Vcs.History
            toolTipText = "Open the PR timeline showing all comments, votes, and events"
            addActionListener { openTimeline() }
        }
        actionsPanel.add(timelineButton)

        header.add(actionsPanel)

        return header
    }

    // ========================
    //  FILE TREE
    // ========================

    private fun createFileTreeSection(): JPanel {
        val section = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4, 14, 4, 14)
            alignmentX = Component.LEFT_ALIGNMENT
            // Make the file tree take up available space
            preferredSize = Dimension(0, 300)
            minimumSize = Dimension(0, 150)
        }

        // Filter toolbar
        val filterBar = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            background = UIUtil.getPanelBackground()
        }
        filterBar.add(fileFilterCombo)

        fileFilterCombo.addActionListener {
            applyFileFilter()
        }

        section.add(filterBar, BorderLayout.NORTH)

        // Create file tree panel
        fileTreePanel = FileTreePanel(project, pullRequest.pullRequestId).apply {
            addFileSelectionListener { change ->
                openDiffForFile(change)
            }
        }
        section.add(fileTreePanel!!, BorderLayout.CENTER)

        return section
    }

    // ========================
    //  POLICY CHECKS
    // ========================

    private fun createPolicyChecksSection(): JPanel {
        val section = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(2, 14, 2, 14)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 80)
        }

        section.add(policyChecksContainer, BorderLayout.CENTER)

        // Initial loading state
        policyChecksContainer.add(JBLabel("Loading checks...").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(11f)
        })

        return section
    }

    // ========================
    //  REVIEWERS
    // ========================

    private fun createReviewersSection(): JPanel {
        val section = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4, 14, 4, 14)
            alignmentX = Component.LEFT_ALIGNMENT
            // Allow section to expand vertically to fill available space
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
            preferredSize = Dimension(Int.MAX_VALUE, 150)
        }

        section.add(reviewersContainer, BorderLayout.CENTER)
        
        // Allow reviewers container to expand
        reviewersContainer.apply {
            maximumSize = Dimension(Int.MAX_VALUE, Int.MAX_VALUE)
        }
        
        updateReviewersUI(pullRequest.reviewers ?: emptyList())

        return section
    }

    // ========================
    //  VOTE
    // ========================

    private fun createVoteSection(): JPanel {
        val section = JPanel(FlowLayout(FlowLayout.LEFT, 8, 8)).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4, 14, 12, 14)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        if (pullRequest.isDraft == true) {
            voteComboBox.isEnabled = false
            voteComboBox.toolTipText = "Voting disabled for draft PRs"
            section.add(JBLabel("Draft — voting disabled").apply {
                foreground = JBColor(Color(255, 165, 0), Color(255, 140, 0))
                font = font.deriveFont(Font.ITALIC, 11f)
            })
        }

        voteComboBox.addActionListener { handleVoteChange() }
        section.add(voteComboBox)

        return section
    }

    // ========================
    //  DATA LOADING
    // ========================

    private fun loadData() {
        // Preload reviewer avatars
        avatarService.preloadAvatars(pullRequest.reviewers?.map { it.imageUrl } ?: emptyList())

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Load file changes
                val projectName = pullRequest.repository?.project?.name
                val repositoryId = pullRequest.repository?.id
                val changes = apiClient.getPullRequestChanges(pullRequest.pullRequestId, projectName, repositoryId)

                // Load commit count
                val commits = apiClient.getPullRequestCommits(pullRequest.pullRequestId, projectName, repositoryId)
                commitCount = commits.size

                // Load comment threads (for file badges)
                val threads = apiClient.getCommentThreads(pullRequest.pullRequestId, projectName, repositoryId)
                // Filter active (non-resolved) comment threads
                activeCommentThreads = threads.filter { 
                    it.isDeleted != true && it.isActive()
                }

                // Load policy evaluations
                val policies = apiClient.getPolicyEvaluations(pullRequest.pullRequestId, projectName, repositoryId)

                ApplicationManager.getApplication().invokeLater {
                    // Update file tree
                    fileTreePanel?.loadFileChanges(changes)
                    fileTreePanel?.updateCommentCounts(threads)

                    // Update commit count label
                    findComponentByName(this, "commitCountLabel")?.let {
                        (it as? JBLabel)?.text = "$commitCount commits"
                    }

                    // Update policy checks
                    updatePolicyChecksUI(policies, pullRequest, activeCommentThreads)

                    // Refresh reviewers with avatars potentially loaded
                    updateReviewersUI(pullRequest.reviewers ?: emptyList())
                }
            } catch (e: Exception) {
                logger.error("Failed to load PR data", e)
            }
        }
    }

    private fun updatePolicyChecksUI(policies: List<PolicyEvaluation>, pr: PullRequest, threads: List<CommentThread> = emptyList()) {
        policyChecksContainer.removeAll()
        policyChecksContainer.layout = FlowLayout(FlowLayout.LEFT, 12, 2)

        // Check for active comments that need to be resolved
        val hasActiveComments = threads.isNotEmpty()
        if (hasActiveComments) {
            policyChecksContainer.add(createCompactCheckItem("💬 Comments must be resolved", false, isBuild = false))
        }

        if (policies.isEmpty()) {
            // Fallback: show compact info from PR data
            val hasConflicts = pr.hasConflicts()
            if (hasConflicts) {
                policyChecksContainer.add(createCompactCheckItem("⚠ Conflicts", false))
            }
            // Show merge status compactly
            val statusText = if (pr.mergeStatus == "succeeded") "✓ All checks passed" else "⚠ Checks pending"
            policyChecksContainer.add(createCompactCheckItem(statusText, pr.mergeStatus == "succeeded"))
        } else {
            // Show policies compactly - prioritize build status
            val buildPolicies = policies.filter { it.getDisplayName().contains("build", ignoreCase = true) }
            val otherPolicies = policies.filter { !it.getDisplayName().contains("build", ignoreCase = true) }
            
            // Show build policies first with emphasis
            buildPolicies.forEach { policy ->
                val name = policy.getDisplayName()
                val approved = policy.isApproved()
                val running = policy.isRunning()
                
                val displayName = when {
                    running -> "⏳ ${name}"
                    approved -> "✓ ${name}"
                    else -> "✗ ${name}"
                }
                
                policyChecksContainer.add(createCompactCheckItem(displayName, approved, isBuild = true))
            }
            
            // Then show other policies
            otherPolicies.forEach { policy ->
                val name = policy.getDisplayName()
                val approved = policy.isApproved()
                val running = policy.isRunning()
                
                val displayName = when {
                    running -> "⏳ ${name}"
                    approved -> "✓ ${name}"
                    else -> "✗ ${name}"
                }
                
                policyChecksContainer.add(createCompactCheckItem(displayName, approved, isBuild = false))
            }
        }

        policyChecksContainer.revalidate()
        policyChecksContainer.repaint()
    }

    private fun createCheckItem(text: String, passed: Boolean): JComponent {
        return JBLabel(text).apply {
            icon = if (passed) AllIcons.RunConfigurations.TestPassed else AllIcons.RunConfigurations.TestFailed
            foreground = if (passed) JBColor(Color(34, 139, 34), Color(50, 200, 50))
                else JBColor(Color(220, 53, 69), Color(200, 35, 51))
            font = font.deriveFont(11f)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(2, 0)
        }
    }
    
    private fun createCompactCheckItem(text: String, passed: Boolean, isBuild: Boolean = false): JComponent {
        return JBLabel(text).apply {
            foreground = if (passed) JBColor(Color(34, 139, 34), Color(50, 200, 50))
                else JBColor(Color(220, 53, 69), Color(200, 35, 51))
            font = if (isBuild) font.deriveFont(Font.BOLD, 11f) else font.deriveFont(11f)
            border = JBUI.Borders.empty(1, 6, 1, 6)
            
            // Add subtle background for build items
            if (isBuild) {
                isOpaque = true
                background = if (passed) 
                    JBColor(Color(230, 255, 230), Color(30, 60, 30))
                    else JBColor(Color(255, 230, 230), Color(60, 30, 30))
            }
        }
    }

    private fun updateReviewersUI(reviewers: List<Reviewer>) {
        reviewersContainer.removeAll()

        if (reviewers.isEmpty()) {
            reviewersContainer.add(JBLabel("No reviewers assigned").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.ITALIC, 11f)
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.empty(2, 0)
            })
        } else {
            reviewers.forEach { reviewer ->
                reviewersContainer.add(createReviewerRow(reviewer))
            }
        }

        reviewersContainer.revalidate()
        reviewersContainer.repaint()
    }

    private fun createReviewerRow(reviewer: Reviewer): JComponent {
        val row = JPanel(FlowLayout(FlowLayout.LEFT, 8, 3)).apply {
            background = UIUtil.getPanelBackground()
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 36)
        }

        // Avatar
        val avatarIcon = avatarService.getAvatar(reviewer.imageUrl, 24) {
            // Repaint when avatar is loaded
            row.repaint()
            reviewersContainer.repaint()
        }
        val avatarLabel = JBLabel(avatarIcon)
        row.add(avatarLabel)

        // Name
        val name = reviewer.displayName ?: "Unknown"
        val isRequired = reviewer.isRequired == true
        val nameText = if (isRequired) "$name (Required)" else name
        row.add(JBLabel(nameText).apply {
            font = font.deriveFont(12f)
        })

        // Vote icon + text
        val voteStatus = reviewer.getVoteStatus()
        val voteIcon = when (voteStatus) {
            ReviewerVote.Approved -> AllIcons.RunConfigurations.TestPassed
            ReviewerVote.ApprovedWithSuggestions -> AllIcons.General.Information
            ReviewerVote.WaitingForAuthor -> AllIcons.General.Warning
            ReviewerVote.Rejected -> AllIcons.RunConfigurations.TestFailed
            ReviewerVote.NoVote -> AllIcons.Debugger.ThreadSuspended
        }
        val voteColor = when (voteStatus) {
            ReviewerVote.Approved -> JBColor(Color(34, 139, 34), Color(50, 200, 50))
            ReviewerVote.ApprovedWithSuggestions -> JBColor(Color(255, 165, 0), Color(255, 140, 0))
            ReviewerVote.WaitingForAuthor -> JBColor(Color(255, 165, 0), Color(255, 140, 0))
            ReviewerVote.Rejected -> JBColor(Color(220, 53, 69), Color(200, 35, 51))
            ReviewerVote.NoVote -> JBColor.GRAY
        }
        row.add(JBLabel(voteStatus.getDisplayName()).apply {
            icon = voteIcon
            foreground = voteColor
            font = font.deriveFont(11f)
        })

        return row
    }

    // ========================
    //  ACTIONS
    // ========================

    private fun openTimeline() {
        PrReviewTabService.getInstance(project).openTimelineTab(pullRequest)
    }

    private fun openDiffForFile(change: PullRequestChange) {
        PrReviewTabService.getInstance(project).openDiffTab(pullRequest, change)
    }

    private fun applyFileFilter() {
        val filterMode = when (fileFilterCombo.selectedIndex) {
            1 -> FileTreePanel.FilterMode.REVIEWED
            2 -> FileTreePanel.FilterMode.UNREVIEWED
            else -> FileTreePanel.FilterMode.ALL
        }
        fileTreePanel?.setFilterMode(filterMode)
    }

    private fun handleVoteChange() {
        if (ignoringVoteChange) return
        val selectedIndex = voteComboBox.selectedIndex
        if (selectedIndex == 0) return

        if (pullRequest.isDraft == true) {
            ignoringVoteChange = true
            voteComboBox.selectedIndex = 0
            ignoringVoteChange = false
            return
        }

        val vote = when (selectedIndex) {
            1 -> 10
            2 -> 5
            3 -> -5
            4 -> -10
            else -> return
        }

        val voteText = voteComboBox.selectedItem as? String ?: "Unknown"
        val confirmed = JOptionPane.showConfirmDialog(
            this,
            "Submit review as: $voteText?",
            "Confirm Review",
            JOptionPane.YES_NO_OPTION,
            JOptionPane.QUESTION_MESSAGE
        )

        if (confirmed != JOptionPane.YES_OPTION) {
            ignoringVoteChange = true
            voteComboBox.selectedIndex = 0
            ignoringVoteChange = false
            return
        }

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                apiClient.voteOnPullRequest(pullRequest, vote)
                reviewStateService.savePrVote(pullRequest.pullRequestId, vote)
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(this, "Review submitted: $voteText", "Success", JOptionPane.INFORMATION_MESSAGE)
                }
            } catch (e: Exception) {
                logger.error("Failed to submit vote", e)
                ApplicationManager.getApplication().invokeLater {
                    JOptionPane.showMessageDialog(this, "Failed: ${e.message}", "Error", JOptionPane.ERROR_MESSAGE)
                    ignoringVoteChange = true
                    voteComboBox.selectedIndex = 0
                    ignoringVoteChange = false
                }
            }
        }
    }

    // ========================
    //  AUTO-REFRESH
    // ========================

    private fun startAutoRefresh() {
        refreshTimer = Timer(REFRESH_INTERVAL) { refreshData() }.apply {
            isRepeats = true
            start()
        }
    }

    private fun refreshData() {
        val projectName = pullRequest.repository?.project?.name
        val repositoryId = pullRequest.repository?.id

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val updatedPr = apiClient.getPullRequest(pullRequest.pullRequestId, projectName, repositoryId)
                val threads = apiClient.getCommentThreads(pullRequest.pullRequestId, projectName, repositoryId)
                val policies = apiClient.getPolicyEvaluations(pullRequest.pullRequestId, projectName, repositoryId)
                
                // Filter active (non-resolved) comment threads
                activeCommentThreads = threads.filter { 
                    it.isDeleted != true && it.isActive()
                }

                ApplicationManager.getApplication().invokeLater {
                    updateReviewersUI(updatedPr.reviewers ?: emptyList())
                    fileTreePanel?.updateCommentCounts(threads)
                    updatePolicyChecksUI(policies, updatedPr, activeCommentThreads)
                }
            } catch (e: Exception) {
                logger.warn("Auto-refresh failed: ${e.message}")
            }
        }
    }

    fun dispose() {
        refreshTimer?.stop()
        refreshTimer = null
    }

    // ========================
    //  UTILITY
    // ========================

    private fun createBadge(text: String, color: JBColor): JComponent {
        return JBLabel(text).apply {
            isOpaque = true
            background = color
            foreground = Color.WHITE
            border = JBUI.Borders.empty(3, 10, 3, 10)
            font = font.deriveFont(Font.BOLD, 10f)
        }
    }

    private fun createSeparator(): JComponent {
        return JSeparator().apply {
            maximumSize = Dimension(Int.MAX_VALUE, 1)
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    private fun findComponentByName(container: Container, name: String): Component? {
        for (component in container.components) {
            if (component.name == name) return component
            if (component is Container) {
                findComponentByName(component, name)?.let { return it }
            }
        }
        return null
    }
}
