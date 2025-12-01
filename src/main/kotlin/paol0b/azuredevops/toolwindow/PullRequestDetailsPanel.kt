package paol0b.azuredevops.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.actions.ReviewPullRequestAction
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.model.PullRequestStatus
import paol0b.azuredevops.services.PullRequestReviewService
import java.awt.*
import javax.swing.*

/**
 * Improved panel showing details of a Pull Request
 * Design inspired by Visual Studio with a more modern layout
 */
class PullRequestDetailsPanel(private val project: Project) {

    private val logger = Logger.getInstance(PullRequestDetailsPanel::class.java)
    private val mainPanel: JPanel
    private val titleLabel: JBLabel
    private val statusBadge: JBLabel
    private val draftBadge: JBLabel
    private val branchPanel: JPanel
    private val authorLabel: JBLabel
    private val createdDateLabel: JBLabel
    private val descriptionArea: JTextArea
    private val reviewersPanel: JPanel
    private val actionButtonsPanel: JPanel
    private var currentPullRequest: PullRequest? = null

    init {
        titleLabel = JBLabel().apply {
            font = font.deriveFont(18f).deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(5, 0)
        }

        statusBadge = JBLabel().apply {
            isOpaque = true
            border = JBUI.Borders.empty(4, 12)
            font = font.deriveFont(Font.BOLD, 11f)
        }
        
        draftBadge = JBLabel("DRAFT").apply {
            isOpaque = true
            background = JBColor(Color(255, 165, 0), Color(255, 140, 0))
            foreground = Color.WHITE
            border = JBUI.Borders.empty(4, 12)
            font = font.deriveFont(Font.BOLD, 11f)
            isVisible = false
        }

        branchPanel = createBranchPanel()
        
        authorLabel = JBLabel().apply {
            icon = AllIcons.General.User
            border = JBUI.Borders.empty(4, 0)
        }
        
        createdDateLabel = JBLabel().apply {
            icon = AllIcons.Vcs.History
            border = JBUI.Borders.empty(4, 0)
        }

        descriptionArea = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = UIUtil.getTextFieldBackground()
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.empty(10)
            font = font.deriveFont(13f)
        }

        reviewersPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8)
            background = UIUtil.getPanelBackground()
        }
        
        actionButtonsPanel = createActionButtonsPanel()

        mainPanel = createLayout()
        showEmptyState()
    }

    fun getComponent(): JPanel = mainPanel

    fun setPullRequest(pullRequest: PullRequest?) {
        if (pullRequest == null) {
            showEmptyState()
            return
        }
        
        currentPullRequest = pullRequest

        // Title with colored ID
        titleLabel.text = "<html><span style='color: #6897BB;'>PR #${pullRequest.pullRequestId}:</span> " +
                "${pullRequest.title}</html>"

        // Status badge with improved colors
        updateStatusBadge(pullRequest.status, pullRequest.isDraft == true)

        // Draft badge
        draftBadge.isVisible = pullRequest.isDraft == true

        // Branch info with improved design
        updateBranchPanel(pullRequest.getSourceBranchName(), pullRequest.getTargetBranchName())

        // Author with improved icon and style
        val authorName = pullRequest.createdBy?.displayName ?: "Unknown"
        authorLabel.text = "<html><b>Created by:</b> <span style='color: #6897BB;'>$authorName</span></html>"

        // Created date with improved formatting
        val createdDate = pullRequest.creationDate ?: "Unknown"
        createdDateLabel.text = "<html><b>Created:</b> ${formatDate(createdDate)}</html>"

        // Description with borders and background
        descriptionArea.text = pullRequest.description?.takeIf { it.isNotBlank() }
            ?: "No description provided for this pull request."

        // Reviewers
        updateReviewers(pullRequest)

        mainPanel.revalidate()
        mainPanel.repaint()
    }

    private fun showEmptyState() {
        titleLabel.text = "<html><span style='color: gray;'>No Pull Request Selected</span></html>"
        statusBadge.isVisible = false
        draftBadge.isVisible = false
        updateBranchPanel("", "")
        authorLabel.text = ""
        createdDateLabel.text = ""
        descriptionArea.text = "Select a pull request from the list to view its details here."
        reviewersPanel.removeAll()
        mainPanel.revalidate()
        mainPanel.repaint()
    }
    
    private fun createBranchPanel(): JPanel {
        return JPanel(FlowLayout(FlowLayout.LEFT, 10, 5)).apply {
            border = JBUI.Borders.empty(5, 0)
        }
    }
    
    private fun updateBranchPanel(sourceBranch: String, targetBranch: String) {
        branchPanel.removeAll()
        
        if (sourceBranch.isNotEmpty()) {
            val sourceLabel = JBLabel(sourceBranch).apply {
                icon = AllIcons.Vcs.BranchNode
                foreground = JBColor(Color(34, 139, 34), Color(50, 200, 50))
                font = font.deriveFont(Font.BOLD)
            }
            branchPanel.add(sourceLabel)
            
            val arrowLabel = JBLabel("→").apply {
                font = font.deriveFont(18f)
                foreground = JBColor.GRAY
            }
            branchPanel.add(arrowLabel)
            
            val targetLabel = JBLabel(targetBranch).apply {
                icon = AllIcons.Vcs.BranchNode
                foreground = JBColor(Color(70, 130, 180), Color(100, 149, 237))
                font = font.deriveFont(Font.BOLD)
            }
            branchPanel.add(targetLabel)
        }
        
        branchPanel.revalidate()
        branchPanel.repaint()
    }

    private fun updateStatusBadge(status: PullRequestStatus, isDraft: Boolean) {
        statusBadge.isVisible = true
        
        when {
            status == PullRequestStatus.Active -> {
                statusBadge.text = "ACTIVE"
                statusBadge.background = JBColor(Color(34, 139, 34), Color(40, 167, 69))
                statusBadge.foreground = Color.WHITE
            }
            status == PullRequestStatus.Completed -> {
                statusBadge.text = "MERGED"
                statusBadge.background = JBColor(Color(111, 66, 193), Color(137, 87, 229))
                statusBadge.foreground = Color.WHITE
            }
            status == PullRequestStatus.Abandoned -> {
                statusBadge.text = "ABANDONED"
                statusBadge.background = JBColor(Color(220, 53, 69), Color(200, 35, 51))
                statusBadge.foreground = Color.WHITE
            }
            else -> {
                statusBadge.text = status.getDisplayName().uppercase()
                statusBadge.background = JBColor.LIGHT_GRAY
                statusBadge.foreground = Color.BLACK
            }
        }
    }

    private fun updateReviewers(pullRequest: PullRequest) {
        reviewersPanel.removeAll()

        val reviewers = pullRequest.reviewers
        if (reviewers.isNullOrEmpty()) {
            val noReviewersLabel = JBLabel("No reviewers assigned").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.ITALIC)
            }
            reviewersPanel.add(noReviewersLabel)
            return
        }

        val headerLabel = JBLabel("Reviewers:").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            border = JBUI.Borders.emptyBottom(5)
        }
        reviewersPanel.add(headerLabel)

        reviewers.forEach { reviewer ->
            val reviewerName = reviewer.displayName ?: "Unknown"
            val voteStatus = reviewer.getVoteStatus()
            val isRequired = if (reviewer.isRequired == true) " (Required)" else ""
            
            val reviewerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 2)).apply {
                background = UIUtil.getPanelBackground()
            }
            
            val icon = when {
                reviewer.vote == 10 -> AllIcons.General.InspectionsOK
                reviewer.vote == -10 -> AllIcons.General.Error
                reviewer.vote == 5 -> AllIcons.General.Information
                else -> AllIcons.General.User
            }
            
            val reviewerLabel = JBLabel("${voteStatus.getDisplayName()} - $reviewerName$isRequired").apply {
                this.icon = icon
                val color = when {
                    reviewer.vote == 10 -> JBColor(Color(34, 139, 34), Color(50, 200, 50))
                    reviewer.vote == -10 -> JBColor(Color(220, 53, 69), Color(200, 35, 51))
                    reviewer.vote == 5 -> JBColor(Color(255, 165, 0), Color(255, 140, 0))
                    else -> JBColor.foreground()
                }
                foreground = color
            }
            
            reviewerPanel.add(reviewerLabel)
            reviewersPanel.add(reviewerPanel)
        }
    }

    private fun formatDate(dateString: String): String {
        return try {
            val parts = dateString.split("T")
            if (parts.size >= 2) {
                val datePart = parts[0]
                val timePart = parts[1].substring(0, 5)
                "$datePart at $timePart"
            } else {
                dateString
            }
        } catch (e: Exception) {
            dateString
        }
    }

    private fun createLayout(): JPanel {
        val scrollPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(10)
        }

        // Header with title and badges
        val headerPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(10)
        }
        
        headerPanel.add(titleLabel)
        
        val badgePanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 5)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
        }
        badgePanel.add(statusBadge)
        badgePanel.add(draftBadge)
        headerPanel.add(badgePanel)
        
        scrollPanel.add(headerPanel)

        // Separator
        scrollPanel.add(Box.createVerticalStrut(5))
        scrollPanel.add(JSeparator())
        scrollPanel.add(Box.createVerticalStrut(10))

        // Branch panel
        branchPanel.alignmentX = Component.LEFT_ALIGNMENT
        scrollPanel.add(branchPanel)
        scrollPanel.add(Box.createVerticalStrut(10))

        // Metadata (author and date)
        val metaPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(10)
        }
        authorLabel.alignmentX = Component.LEFT_ALIGNMENT
        createdDateLabel.alignmentX = Component.LEFT_ALIGNMENT
        metaPanel.add(authorLabel)
        metaPanel.add(Box.createVerticalStrut(5))
        metaPanel.add(createdDateLabel)
        scrollPanel.add(metaPanel)

        // Separator
        scrollPanel.add(Box.createVerticalStrut(5))
        scrollPanel.add(JSeparator())
        scrollPanel.add(Box.createVerticalStrut(10))

        // Description section
        val descLabel = JBLabel("Description:").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(5)
        }
        scrollPanel.add(descLabel)
        
        val descScrollPane = JBScrollPane(descriptionArea).apply {
            preferredSize = Dimension(0, 150)
            minimumSize = Dimension(0, 100)
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.customLine(JBColor.border(), 1)
        }
        scrollPanel.add(descScrollPane)
        scrollPanel.add(Box.createVerticalStrut(15))

        // Reviewers section
        reviewersPanel.alignmentX = Component.LEFT_ALIGNMENT
        scrollPanel.add(reviewersPanel)
        
        // Action buttons
        actionButtonsPanel.alignmentX = Component.LEFT_ALIGNMENT
        scrollPanel.add(Box.createVerticalStrut(15))
        scrollPanel.add(JSeparator())
        scrollPanel.add(Box.createVerticalStrut(10))
        scrollPanel.add(actionButtonsPanel)

        // Wrapper panel with scroll
        val wrapperPanel = JPanel(BorderLayout())
        wrapperPanel.add(JBScrollPane(scrollPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 16
        }, BorderLayout.CENTER)
        
        return wrapperPanel
    }
    
    /**
     * Creates the panel with action buttons (Review, Approve, etc.)
     */
    private fun createActionButtonsPanel(): JPanel {
        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 10, 5)).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(5)
        }
        
        // Review PR button
        val reviewButton = JButton("Review Changes", AllIcons.Actions.Diff).apply {
            toolTipText = "Review all changes in this PR with integrated diff viewer"
            addActionListener {
                logger.info("Review button clicked")
                if (currentPullRequest == null) {
                    logger.warn("No PR selected")
                    return@addActionListener
                }
                
                logger.info("Starting review for PR #${currentPullRequest?.pullRequestId}")
                currentPullRequest?.let { pr ->
                    val reviewService = PullRequestReviewService.getInstance(project)
                    reviewService.startReview(pr)
                }
            }
        }
        panel.add(reviewButton)
        
        // Approve button (placeholder for future implementation)
        val approveButton = JButton("Approve", AllIcons.Actions.Checked).apply {
            toolTipText = "Approve this Pull Request"
            isEnabled = false // TODO: Implement approval
        }
        panel.add(approveButton)
        
        // Request Changes button (placeholder)
        val requestChangesButton = JButton("Request Changes", AllIcons.General.Warning).apply {
            toolTipText = "Request changes for this Pull Request"
            isEnabled = false // TODO: Implement request changes
        }
        panel.add(requestChangesButton)
        
        return panel
    }
}
