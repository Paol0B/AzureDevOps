package paol0b.azuredevops.toolwindow.list

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleColoredComponent
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.model.PullRequestStatus
import paol0b.azuredevops.model.Reviewer
import paol0b.azuredevops.services.AvatarService
import java.awt.*
import java.awt.geom.Ellipse2D
import javax.swing.*

/**
 * GitHub-style list cell renderer for Pull Requests.
 * Inspired by JetBrains's GHPRListComponentFactory / ReviewListItemPresentation.
 *
 * Layout:
 * ┌──────────────────────────────────────────────────────────────┐
 * │ [StateIcon]  Title of the Pull Request               #1234  │
 * │              DRAFT  AUTO-COMPLETE  ⚠CONFLICTS               │
 * │              feature/branch → main                          │
 * │ [Avatar]     Created by Author • 2h ago   [R1][R2] [repo]  │
 * └──────────────────────────────────────────────────────────────┘
 */
class PullRequestListRenderer(
    private val avatarService: AvatarService,
    private val isOrgMode: () -> Boolean
) : JPanel(BorderLayout(JBUI.scale(8), 0)), ListCellRenderer<PullRequest> {

    // ── Left column (state icon) ──
    private val stateIconLabel = JLabel().apply {
        verticalAlignment = SwingConstants.TOP
        border = JBUI.Borders.empty(JBUI.scale(2), 0, 0, 0)
        preferredSize = Dimension(JBUI.scale(20), JBUI.scale(20))
    }

    // ── Center column ──
    private val centerPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    // Row 1: Title
    private val titleRow = JPanel(BorderLayout()).apply { isOpaque = false }
    private val titleComponent = SimpleColoredComponent().apply {
        isOpaque = false
        font = UIUtil.getLabelFont().deriveFont(Font.BOLD)
    }
    private val numberLabel = JLabel().apply {
        foreground = JBColor.GRAY
        font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, UIUtil.getFontSize(UIUtil.FontSize.NORMAL))
    }

    // Row 2: Badges
    private val badgeRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
    }

    // Row 3: Branch info
    private val branchComponent = SimpleColoredComponent().apply {
        isOpaque = false
        font = UIUtil.getLabelFont().deriveFont(UIUtil.getFontSize(UIUtil.FontSize.SMALL))
    }

    // Row 4: Author + meta + reviewers
    private val metaRow = JPanel(BorderLayout(JBUI.scale(4), 0)).apply { isOpaque = false }
    private val authorMetaPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { isOpaque = false }
    private val reviewersAndRepoPanel = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(2), 0)).apply { isOpaque = false }

    // Avatar label (for author)
    private val authorAvatarLabel = JLabel().apply {
        verticalAlignment = SwingConstants.CENTER
    }

    init {
        isOpaque = true
        border = JBUI.Borders.empty(JBUI.scale(8), JBUI.scale(12), JBUI.scale(8), JBUI.scale(12))

        // Left: state icon
        add(stateIconLabel, BorderLayout.WEST)

        // Center: content
        titleRow.add(titleComponent, BorderLayout.CENTER)
        titleRow.add(numberLabel, BorderLayout.EAST)
        titleRow.alignmentX = Component.LEFT_ALIGNMENT
        centerPanel.add(titleRow)

        badgeRow.alignmentX = Component.LEFT_ALIGNMENT
        centerPanel.add(badgeRow)

        branchComponent.alignmentX = Component.LEFT_ALIGNMENT
        centerPanel.add(Box.createVerticalStrut(JBUI.scale(2)))
        centerPanel.add(branchComponent)

        metaRow.add(authorMetaPanel, BorderLayout.CENTER)
        metaRow.add(reviewersAndRepoPanel, BorderLayout.EAST)
        metaRow.alignmentX = Component.LEFT_ALIGNMENT
        centerPanel.add(Box.createVerticalStrut(JBUI.scale(2)))
        centerPanel.add(metaRow)

        add(centerPanel, BorderLayout.CENTER)
    }

    override fun getListCellRendererComponent(
        list: JList<out PullRequest>,
        value: PullRequest?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        if (value == null) return this

        // Background
        background = if (isSelected) {
            UIUtil.getListSelectionBackground(cellHasFocus)
        } else {
            UIUtil.getListBackground()
        }
        val textColor = if (isSelected) UIUtil.getListSelectionForeground(cellHasFocus) else UIUtil.getListForeground()

        // ── State Icon ──
        stateIconLabel.icon = getStateIcon(value)

        // ── Title ──
        titleComponent.clear()
        val titleAttrs = if (value.status == PullRequestStatus.Active) {
            SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, textColor)
        } else {
            SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, JBColor.GRAY)
        }
        titleComponent.append(value.title, titleAttrs)

        // ── PR Number ──
        numberLabel.text = "  #${value.pullRequestId}"
        numberLabel.foreground = if (isSelected) textColor else JBColor(Color(104, 151, 187), Color(104, 151, 187))

        // ── Badges ──
        badgeRow.removeAll()
        if (value.isDraft == true) {
            badgeRow.add(createBadge("DRAFT", BADGE_DRAFT_BG, BADGE_DRAFT_FG))
        }
        if (value.hasAutoComplete()) {
            badgeRow.add(createBadge("AUTO-COMPLETE", BADGE_AUTOCOMPLETE_BG, Color.WHITE))
        }
        if (value.isReadyToComplete() && !value.hasAutoComplete()) {
            badgeRow.add(createBadge("✓ READY", BADGE_READY_BG, Color.WHITE))
        }
        if (value.hasConflicts()) {
            badgeRow.add(createBadge("⚠ CONFLICTS", BADGE_CONFLICT_BG, Color.WHITE))
        }
        // Labels
        value.labels?.filter { it.active != false }?.forEach { label ->
            label.name?.let { name ->
                badgeRow.add(createBadge(name, BADGE_LABEL_BG, BADGE_LABEL_FG))
            }
        }
        badgeRow.isVisible = badgeRow.componentCount > 0

        // ── Branch info ──
        branchComponent.clear()
        branchComponent.icon = AllIcons.Vcs.BranchNode
        branchComponent.iconTextGap = JBUI.scale(4)
        branchComponent.append(
            value.getSourceBranchName(),
            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, BRANCH_SOURCE_COLOR)
        )
        branchComponent.append(" → ", SimpleTextAttributes.GRAYED_ATTRIBUTES)
        branchComponent.append(
            value.getTargetBranchName(),
            SimpleTextAttributes(SimpleTextAttributes.STYLE_PLAIN, BRANCH_TARGET_COLOR)
        )

        // ── Author + Meta ──
        authorMetaPanel.removeAll()

        // Author avatar
        val avatarIcon = avatarService.getAvatar(value.createdBy?.imageUrl, JBUI.scale(16)) {
            list.repaint()
        }
        authorAvatarLabel.icon = avatarIcon
        authorMetaPanel.add(authorAvatarLabel)

        // Author name
        val authorName = value.createdBy?.displayName ?: "Unknown"
        authorMetaPanel.add(createMetaLabel(authorName, if (isSelected) textColor else AUTHOR_COLOR))

        // Dot separator
        authorMetaPanel.add(createMetaLabel("•", JBColor.GRAY))

        // Relative time
        val timeAgo = PullRequestFilterUtil.formatRelativeTime(value.creationDate)
        authorMetaPanel.add(createMetaLabel(timeAgo, JBColor.GRAY))

        // ── Reviewers + Repo ──
        reviewersAndRepoPanel.removeAll()

        // Reviewer avatars with vote status border color
        value.reviewers?.take(MAX_REVIEWER_AVATARS)?.forEach { reviewer ->
            reviewersAndRepoPanel.add(createReviewerAvatar(reviewer, list))
        }
        val remainingReviewers = (value.reviewers?.size ?: 0) - MAX_REVIEWER_AVATARS
        if (remainingReviewers > 0) {
            reviewersAndRepoPanel.add(createMetaLabel("+$remainingReviewers", JBColor.GRAY))
        }

        // Repo name in org mode
        if (isOrgMode()) {
            val repoName = value.repository?.name ?: ""
            val projectName = value.repository?.project?.name ?: ""
            if (repoName.isNotEmpty()) {
                reviewersAndRepoPanel.add(Box.createHorizontalStrut(JBUI.scale(6)))
                reviewersAndRepoPanel.add(createRepoBadge("$projectName/$repoName"))
            }
        }

        return this
    }

    // ── Helper Methods ──

    private fun getStateIcon(pr: PullRequest): Icon {
        return when {
            pr.isDraft == true -> AllIcons.Vcs.Patch_applied
            pr.status == PullRequestStatus.Active -> {
                if (pr.hasConflicts()) AllIcons.General.Warning
                else AllIcons.Vcs.Branch
            }
            pr.status == PullRequestStatus.Completed -> AllIcons.RunConfigurations.TestPassed
            pr.status == PullRequestStatus.Abandoned -> AllIcons.RunConfigurations.TestFailed
            else -> AllIcons.Vcs.Branch
        }
    }

    private fun createBadge(text: String, bgColor: Color, fgColor: Color): JLabel {
        return object : JLabel(text) {
            init {
                isOpaque = false
                foreground = fgColor
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, JBUI.scaleFontSize(9f).toFloat())
                border = JBUI.Borders.empty(JBUI.scale(1), JBUI.scale(6), JBUI.scale(1), JBUI.scale(6))
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = bgColor
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
                g2.dispose()
                super.paintComponent(g)
            }
        }
    }

    private fun createMetaLabel(text: String, color: Color): JLabel {
        return JLabel(text).apply {
            foreground = color
            font = UIUtil.getLabelFont().deriveFont(UIUtil.getFontSize(UIUtil.FontSize.SMALL))
        }
    }

    private fun createRepoBadge(text: String): JLabel {
        return object : JLabel(text) {
            init {
                isOpaque = false
                foreground = JBColor(Color(100, 100, 100), Color(160, 160, 160))
                font = UIUtil.getLabelFont().deriveFont(Font.ITALIC, JBUI.scaleFontSize(10f).toFloat())
                border = JBUI.Borders.empty(JBUI.scale(1), JBUI.scale(4), JBUI.scale(1), JBUI.scale(4))
                icon = AllIcons.Vcs.BranchNode
                iconTextGap = JBUI.scale(2)
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = JBColor(Color(240, 240, 240), Color(60, 60, 60))
                g2.fillRoundRect(0, 0, width, height, JBUI.scale(4), JBUI.scale(4))
                g2.dispose()
                super.paintComponent(g)
            }
        }
    }

    /**
     * Creates a reviewer avatar label with a colored border indicating vote status.
     * Green = Approved, Red = Rejected, Orange = Waiting/Suggestions, Gray = No vote
     */
    private fun createReviewerAvatar(reviewer: Reviewer, list: JList<*>): JComponent {
        val borderColor = getVoteBorderColor(reviewer.vote)
        val icon = avatarService.getAvatar(reviewer.imageUrl, JBUI.scale(20)) {
            list.repaint()
        }
        return object : JLabel(icon) {
            init {
                toolTipText = "${reviewer.displayName ?: "Unknown"} (${reviewer.getVoteStatus().getDisplayName()})"
                preferredSize = Dimension(JBUI.scale(24), JBUI.scale(24))
                horizontalAlignment = SwingConstants.CENTER
                verticalAlignment = SwingConstants.CENTER
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                // Draw colored border circle
                val inset = JBUI.scale(1)
                val size = minOf(width, height) - inset * 2
                g2.color = borderColor
                g2.fillOval(inset, inset, size, size)

                // Clip to circle for the avatar
                val innerInset = JBUI.scale(3)
                val innerSize = size - (innerInset - inset) * 2
                val clip = Ellipse2D.Float(innerInset.toFloat(), innerInset.toFloat(), innerSize.toFloat(), innerSize.toFloat())
                g2.clip = clip

                // Draw the icon centered
                val iconObj = this.icon
                if (iconObj != null) {
                    val x = (width - iconObj.iconWidth) / 2
                    val y = (height - iconObj.iconHeight) / 2
                    iconObj.paintIcon(this, g2, x, y)
                }

                g2.dispose()
            }
        }
    }

    private fun getVoteBorderColor(vote: Int?): Color = when (vote) {
        10 -> JBColor(Color(34, 139, 34), Color(50, 200, 50))       // Approved
        5 -> JBColor(Color(255, 165, 0), Color(255, 140, 0))         // Approved with suggestions
        -5 -> JBColor(Color(255, 165, 0), Color(255, 140, 0))        // Waiting for author
        -10 -> JBColor(Color(220, 50, 50), Color(255, 80, 80))       // Rejected
        else -> JBColor(Color(180, 180, 180), Color(100, 100, 100))   // No vote
    }

    companion object {
        private const val MAX_REVIEWER_AVATARS = 5

        // Badge colors
        private val BADGE_DRAFT_BG = JBColor(Color(255, 165, 0), Color(180, 120, 0))
        private val BADGE_DRAFT_FG = Color.WHITE
        private val BADGE_AUTOCOMPLETE_BG = JBColor(Color(106, 153, 85), Color(80, 130, 60))
        private val BADGE_READY_BG = JBColor(Color(34, 139, 34), Color(40, 167, 69))
        private val BADGE_CONFLICT_BG = JBColor(Color(220, 53, 69), Color(200, 35, 51))
        private val BADGE_LABEL_BG = JBColor(Color(230, 230, 250), Color(60, 60, 80))
        private val BADGE_LABEL_FG = JBColor(Color(80, 80, 120), Color(180, 180, 220))

        // Text colors
        private val BRANCH_SOURCE_COLOR = JBColor(Color(34, 139, 34), Color(50, 205, 50))
        private val BRANCH_TARGET_COLOR = JBColor(Color(70, 130, 180), Color(135, 206, 250))
        private val AUTHOR_COLOR = JBColor(Color(100, 150, 200), Color(120, 170, 220))
    }
}
