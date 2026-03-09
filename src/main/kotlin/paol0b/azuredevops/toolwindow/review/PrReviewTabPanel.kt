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
import com.intellij.ui.scale.JBUIScale
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
            border = JBUI.Borders.empty(4, 14, 4, 14)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 34)
            minimumSize = Dimension(0, 34)
        }

        section.add(policyChecksContainer, BorderLayout.CENTER)

        // Initial loading state
        policyChecksContainer.layout = FlowLayout(FlowLayout.LEFT, 4, 0)
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
                val policies = apiClient.getPolicyEvaluations(
                    pullRequest.pullRequestId, projectName,
                    pullRequest.repository?.project?.id
                )

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
        policyChecksContainer.layout = FlowLayout(FlowLayout.LEFT, 4, 0)

        // Build the full list of check items (icon, short label, rich tooltip)
        data class CheckItem(val icon: Icon, val label: String, val tooltip: String, val passed: Boolean)
        val items = mutableListOf<CheckItem>()

        // Active comments check
        if (threads.isNotEmpty()) {
            val count = threads.count { it.isDeleted != true && it.isActive() }
            val tooltip = buildString {
                append("<html><b>Comments must be resolved</b><br>")
                append("<span style='color:orange;'>$count active comment thread(s) still open.</span><br>")
                append("Resolve all threads before merging.</html>")
            }
            items += CheckItem(AllIcons.General.Balloon, "$count", tooltip, false)
        }

        if (policies.isEmpty()) {
            val hasConflicts = pr.hasConflicts()
            if (hasConflicts) {
                items += CheckItem(
                    AllIcons.General.Warning, "",
                    "<html><b>Merge conflicts</b><br>This PR has conflicts that must be resolved before merging.</html>",
                    false
                )
            }
            val allPassed = pr.mergeStatus == "succeeded"
            items += CheckItem(
                if (allPassed) AllIcons.RunConfigurations.TestPassed else AllIcons.General.Warning,
                if (allPassed) "All checks" else "Pending",
                if (allPassed) "<html><b>All checks passed</b></html>"
                else "<html><b>Checks pending or not available.</b><br>Merge status: ${pr.mergeStatus ?: "unknown"}</html>",
                allPassed
            )
        } else {
            // Build status checks: builds first, then blocking, then others
            val sorted = policies.sortedWith(
                compareByDescending<PolicyEvaluation> { it.getDisplayName().contains("build", ignoreCase = true) }
                    .thenByDescending { it.configuration?.isBlocking == true }
            )

            sorted.forEach { policy ->
                val name = policy.getDisplayName()
                val approved = policy.isApproved()
                val running = policy.isRunning()
                val blocking = policy.configuration?.isBlocking == true
                val settings = policy.configuration?.settings

                val icon = when {
                    approved -> AllIcons.RunConfigurations.TestPassed
                    else -> if (blocking) AllIcons.RunConfigurations.TestFailed else AllIcons.General.Warning
                }

                // Short label: only for build policies (show build name if available)
                val shortLabel = when {
                    name.contains("build", ignoreCase = true) ->
                        policy.context?.buildDefinitionName?.take(12) ?: "Build"
                    else -> ""
                }

                // Rich tooltip
                val statusWord = when {
                    running -> "<span style='color:#e8a838;'>Running / Queued</span>"
                    approved -> "<span style='color:#3fb950;'>Passed</span>"
                    else -> if (blocking)
                        "<span style='color:#f85149;'>Failed (blocking)</span>"
                    else
                        "<span style='color:#e8a838;'>Failed (non-blocking)</span>"
                }
                val tooltip = buildString {
                    append("<html><b>${escapeHtml(name)}</b><br>")
                    append("Status: $statusWord<br>")
                    if (blocking) append("<i>Blocking — must pass before merge</i><br>")
                    if (settings?.minimumApproverCount != null)
                        append("Minimum approvers: ${settings.minimumApproverCount}<br>")
                    if (settings?.buildDefinitionId != null)
                        append("Build definition ID: ${settings.buildDefinitionId}<br>")
                    if (policy.context?.buildDefinitionName != null)
                        append("Build: ${escapeHtml(policy.context.buildDefinitionName)}<br>")
                    if (settings?.resetOnSourcePush == true)
                        append("Resets on push<br>")
                    append("</html>")
                }

                items += CheckItem(icon, shortLabel, tooltip, approved)
            }
        }

        // Render all items as compact pills
        items.forEach { item ->
            policyChecksContainer.add(createPolicyPill(item.icon, item.label, item.tooltip, item.passed))
        }

        policyChecksContainer.revalidate()
        policyChecksContainer.repaint()
    }

    /**
     * A compact icon pill:
     *   • coloured icon on the left
     *   • optional short text on the right (only for build entries)
     *   • full rich HTML tooltip on mouse-over
     *   • subtle rounded background matching the current theme
     */
    private fun createPolicyPill(icon: Icon, label: String, tooltip: String, passed: Boolean): JComponent {
        val pill = JPanel(FlowLayout(FlowLayout.LEFT, 2, 0)).apply {
            isOpaque = true
            background = when {
                passed -> JBColor(Color(183, 235, 201), Color(25, 55, 35))
                else   -> JBColor(Color(255, 215, 215), Color(60, 25, 25))
            }
            border = JBUI.Borders.empty(2, 5, 2, 5)
            toolTipText = tooltip
            cursor = Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR)
        }

        val iconLabel = JBLabel(icon).apply { toolTipText = tooltip }
        pill.add(iconLabel)

        if (label.isNotBlank()) {
            val textLabel = JBLabel(label).apply {
                font = JBUI.Fonts.smallFont()
                foreground = if (passed)
                    JBColor(Color(20, 100, 40), Color(90, 210, 120))
                else
                    JBColor(Color(160, 40, 40), Color(220, 100, 100))
                toolTipText = tooltip
            }
            pill.add(textLabel)
        }

        // Make the whole pill a rounded shape
        return object : JPanel(BorderLayout()) {
            init {
                isOpaque = false
                add(pill)
                toolTipText = tooltip
                // Enable HTML tooltips with longer display time
                ToolTipManager.sharedInstance().let { mgr ->
                    pill.addMouseListener(object : java.awt.event.MouseAdapter() {
                        override fun mouseEntered(e: java.awt.event.MouseEvent) {
                            mgr.initialDelay = 300
                            mgr.dismissDelay = 15000
                        }
                    })
                }
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = pill.background
                g2.fillRoundRect(0, 0, width, height, JBUIScale.scale(8), JBUIScale.scale(8))
                g2.dispose()
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
                val policies = apiClient.getPolicyEvaluations(
                    pullRequest.pullRequestId, projectName,
                    pullRequest.repository?.project?.id
                )
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
