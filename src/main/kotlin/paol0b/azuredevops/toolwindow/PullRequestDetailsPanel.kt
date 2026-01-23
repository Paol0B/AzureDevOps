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
    private var currentPullRequest: PullRequest? = null

    init {
        titleLabel = JBLabel().apply {
            font = font.deriveFont(Font.BOLD, 16f)
            border = JBUI.Borders.empty(0, 0, 8, 0)
        }

        statusBadge = JBLabel().apply {
            isOpaque = true
            border = JBUI.Borders.empty(5, 14, 5, 14)
            font = font.deriveFont(Font.BOLD, 10f)
        }
        
        draftBadge = JBLabel("DRAFT").apply {
            isOpaque = true
            background = JBColor(Color(255, 165, 0), Color(255, 140, 0))
            foreground = Color.WHITE
            border = JBUI.Borders.empty(5, 14, 5, 14)
            font = font.deriveFont(Font.BOLD, 10f)
            isVisible = false
        }

        branchPanel = createBranchPanel()
        
        authorLabel = JBLabel().apply {
            icon = AllIcons.General.User
            border = JBUI.Borders.empty(2, 0)
            font = font.deriveFont(12f)
        }
        
        createdDateLabel = JBLabel().apply {
            icon = AllIcons.Actions.Commit
            border = JBUI.Borders.empty(2, 0)
            font = font.deriveFont(12f)
        }

        descriptionArea = JTextArea().apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = UIUtil.getPanelBackground()
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.empty(12)
            font = font.deriveFont(13f)
        }

        reviewersPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(0)
            background = UIUtil.getPanelBackground()
        }

        mainPanel = createLayout()
        showEmptyState()
    }

    fun getComponent(): JPanel = mainPanel

    fun setPullRequest(pullRequest: PullRequest?) {
        if (pullRequest == null) {
            showEmptyState()
            return
        }
        
        // Avoid unnecessary updates if PR hasn't changed (prevents visual jitter during polling)
        if (currentPullRequest?.pullRequestId == pullRequest.pullRequestId) {
            // Check if any display-relevant property has changed
            val hasStatusChanged = currentPullRequest?.status != pullRequest.status
            val hasTitleChanged = currentPullRequest?.title != pullRequest.title
            val hasDraftChanged = currentPullRequest?.isDraft != pullRequest.isDraft
            val hasDescriptionChanged = currentPullRequest?.description != pullRequest.description
            val hasReviewersCountChanged = currentPullRequest?.reviewers?.size != pullRequest.reviewers?.size
            
            // Check for reviewer vote changes
            val hasReviewerVoteChanges = if (currentPullRequest?.reviewers != null && pullRequest.reviewers != null) {
                val oldVotes = currentPullRequest?.reviewers?.associateBy({ it.id }, { it.vote }) ?: emptyMap()
                pullRequest.reviewers?.any { reviewer -> 
                    oldVotes[reviewer.id] != reviewer.vote 
                } ?: false
            } else {
                false
            }
            
            // Skip update if nothing has changed
            if (!hasStatusChanged && !hasTitleChanged && !hasDraftChanged && 
                !hasDescriptionChanged && !hasReviewersCountChanged && !hasReviewerVoteChanges) {
                return // Skip update to prevent visual jitter
            }
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
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 40)
        }
    }
    
    private fun updateBranchPanel(sourceBranch: String, targetBranch: String) {
        branchPanel.removeAll()
        
        if (sourceBranch.isNotEmpty()) {
            val sourceLabel = JBLabel(sourceBranch).apply {
                icon = AllIcons.Vcs.BranchNode
                foreground = JBColor(Color(34, 139, 34), Color(50, 200, 50))
                font = font.deriveFont(Font.BOLD, 13f)
            }
            branchPanel.add(sourceLabel)
            
            val arrowLabel = JBLabel("→").apply {
                font = font.deriveFont(Font.BOLD, 18f)
                foreground = JBColor.GRAY
            }
            branchPanel.add(arrowLabel)
            
            val targetLabel = JBLabel(targetBranch).apply {
                icon = AllIcons.Vcs.BranchNode
                foreground = JBColor(Color(70, 130, 180), Color(100, 149, 237))
                font = font.deriveFont(Font.BOLD, 13f)
            }
            branchPanel.add(targetLabel)
        }
        
        // Avoid revalidate/repaint during polling if possible
        if (branchPanel.isVisible) {
            branchPanel.revalidate()
            branchPanel.repaint()
        }
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
        // Store current scroll position to maintain it
        val currentReviewersCount = reviewersPanel.componentCount
        
        reviewersPanel.removeAll()
        reviewersPanel.layout = BoxLayout(reviewersPanel, BoxLayout.Y_AXIS)

        val reviewers = pullRequest.reviewers
        if (reviewers.isNullOrEmpty()) {
            val noReviewersLabel = JBLabel("No reviewers assigned").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.ITALIC, 12f)
                border = JBUI.Borders.empty(4, 0)
                alignmentX = Component.LEFT_ALIGNMENT
            }
            reviewersPanel.add(noReviewersLabel)
            reviewersPanel.revalidate()
            reviewersPanel.repaint()
            return
        }

        val headerLabel = JBLabel("Reviewers:").apply {
            font = font.deriveFont(Font.BOLD, 13f)
            border = JBUI.Borders.emptyBottom(10)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        reviewersPanel.add(headerLabel)

        reviewers.forEach { reviewer ->
            val reviewerName = reviewer.displayName ?: "Unknown"
            val voteStatus = reviewer.getVoteStatus()
            val isRequired = if (reviewer.isRequired == true) " • Required" else ""
            
            val reviewerPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                background = UIUtil.getPanelBackground()
                alignmentX = Component.LEFT_ALIGNMENT
                maximumSize = Dimension(Int.MAX_VALUE, 35)
            }
            
            val icon = when {
                reviewer.vote == 10 -> AllIcons.RunConfigurations.TestPassed
                reviewer.vote == -10 -> AllIcons.RunConfigurations.TestFailed
                reviewer.vote == 5 -> AllIcons.General.Information
                else -> AllIcons.General.User
            }
            
            val reviewerLabel = JBLabel("$reviewerName$isRequired").apply {
                this.icon = icon
                val color = when {
                    reviewer.vote == 10 -> JBColor(Color(34, 139, 34), Color(50, 200, 50))
                    reviewer.vote == -10 -> JBColor(Color(220, 53, 69), Color(200, 35, 51))
                    reviewer.vote == 5 -> JBColor(Color(255, 165, 0), Color(255, 140, 0))
                    else -> JBColor.foreground()
                }
                foreground = color
                font = font.deriveFont(12f)
            }
            
            val statusLabel = JBLabel(" (${voteStatus.getDisplayName()})").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(Font.ITALIC, 11f)
            }
            
            reviewerPanel.add(reviewerLabel)
            reviewerPanel.add(statusLabel)
            reviewersPanel.add(reviewerPanel)
        }
        
        // Only revalidate and repaint if the number of reviewers changed
        if (currentReviewersCount != reviewersPanel.componentCount) {
            reviewersPanel.revalidate()
            reviewersPanel.repaint()
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
        val mainContainer = JPanel(BorderLayout()).apply {
            minimumSize = Dimension(300, 0)
            preferredSize = Dimension(400, 0)
        }
        
        val scrollPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(16, 16, 16, 16)
            background = UIUtil.getPanelBackground()
            // Fixed alignment to prevent movement
            alignmentX = Component.LEFT_ALIGNMENT
        }

        // HEADER CARD - Modern card design for title and badges
        val headerCard = createCard().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 100)
            
            titleLabel.alignmentX = Component.LEFT_ALIGNMENT
            add(titleLabel)
            add(Box.createVerticalStrut(10))
            
            val badgePanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                alignmentX = Component.LEFT_ALIGNMENT
                background = UIUtil.getPanelBackground()
            }
            badgePanel.add(statusBadge)
            badgePanel.add(draftBadge)
            add(badgePanel)
        }
        scrollPanel.add(headerCard)
        scrollPanel.add(Box.createVerticalStrut(12))

        // BRANCH CARD - Modern card for branch information
        val branchCard = createCard().apply {
            layout = BorderLayout()
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 60)
            add(branchPanel, BorderLayout.CENTER)
        }
        scrollPanel.add(branchCard)
        scrollPanel.add(Box.createVerticalStrut(12))

        // METADATA CARD - Author and date in a card
        val metaCard = createCard().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 70)
            
            authorLabel.alignmentX = Component.LEFT_ALIGNMENT
            add(authorLabel)
            add(Box.createVerticalStrut(8))
            createdDateLabel.alignmentX = Component.LEFT_ALIGNMENT
            add(createdDateLabel)
        }
        scrollPanel.add(metaCard)
        scrollPanel.add(Box.createVerticalStrut(12))

        // DESCRIPTION CARD - Card with description
        val descCard = createCard().apply {
            layout = BorderLayout()
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 180)
            
            val descLabel = JBLabel("Description:").apply {
                font = font.deriveFont(Font.BOLD, 13f)
                border = JBUI.Borders.emptyBottom(10)
            }
            add(descLabel, BorderLayout.NORTH)
            
            val descScrollPane = JBScrollPane(descriptionArea).apply {
                preferredSize = Dimension(0, 130)
                minimumSize = Dimension(0, 130)
                maximumSize = Dimension(Int.MAX_VALUE, 130)
                border = JBUI.Borders.customLine(JBColor.border(), 1)
                verticalScrollBar.unitIncrement = 16
            }
            add(descScrollPane, BorderLayout.CENTER)
        }
        scrollPanel.add(descCard)
        scrollPanel.add(Box.createVerticalStrut(12))

        // REVIEWERS CARD
        val reviewersCard = createCard().apply {
            layout = BorderLayout()
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 200)
            add(reviewersPanel, BorderLayout.CENTER)
        }
        scrollPanel.add(reviewersCard)
        scrollPanel.add(Box.createVerticalStrut(12))
        
        // ACTION BUTTONS CARD
        val actionsCard = createCard().apply {
            layout = BorderLayout()
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 90)
        }
        scrollPanel.add(actionsCard)

        // Vertical glue to push content to top
        scrollPanel.add(Box.createVerticalGlue())

        // Wrapper with scroll
        mainContainer.add(JBScrollPane(scrollPanel).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 16
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }, BorderLayout.CENTER)
        
        return mainContainer
    }
    
    /**
     * Creates a modern card panel with rounded border and stable layout
     */
    private fun createCard(): JPanel {
        return JBPanel<JBPanel<*>>().apply {
            border = BorderFactory.createCompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(12, 14)
            )
            background = UIUtil.getPanelBackground()
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }
}
