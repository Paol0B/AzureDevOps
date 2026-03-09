package paol0b.azuredevops.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.model.PullRequestStatus
import paol0b.azuredevops.services.AvatarService
import java.awt.*
import java.awt.geom.Ellipse2D
import javax.swing.*

/**
 * Custom cell renderer for the PR list, replicating the JetBrains GitHub plugin's
 * ReviewListCellRenderer layout.
 *
 * Two-line layout per row:
 *   Line 1: [status dot] [title]  ...  [state badge] [reviewer avatars] [comment count]
 *   Line 2: #number · date · author (· repo if cross-org)
 */
class PullRequestListCellRenderer(
    private val avatarService: AvatarService,
    private val showRepositoryInfo: () -> Boolean
) : ListCellRenderer<PullRequest>, JPanel(BorderLayout()) {

    // ── Components ──
    private val statusDot = JLabel()
    private val titleLabel = JLabel().apply {
        minimumSize = Dimension(0, 0)
    }
    private val stateLabel = StateBadgeLabel()
    private val draftLabel = StateBadgeLabel()
    private val conflictIcon = JLabel()
    private val autoCompleteIcon = JLabel()
    private val reviewersLabel = JLabel()
    private val commentsLabel = JLabel()
    private val infoLabel = JLabel()

    // First line: left side (dot + title) and right side (badges, avatars, comments)
    //
    // Custom firstLinePanel: rightPanel ALWAYS gets its preferred width from the right;
    // leftPanel fills whatever remains (can be 0) — so the title clips, never the badges.
    private val firstLinePanel = object : JPanel(null) {
        private val hgap = JBUIScale.scale(6)
        init { isOpaque = false }
        override fun doLayout() {
            val ins = insets
            val totalW = width - ins.left - ins.right
            val totalH = height - ins.top - ins.bottom
            val rightW = minOf(rightPanel.preferredSize.width, totalW)
            val leftW = maxOf(0, totalW - rightW - hgap)
            leftPanel.setBounds(ins.left, ins.top, leftW, totalH)
            rightPanel.setBounds(ins.left + leftW + hgap, ins.top, rightW, totalH)
        }
        override fun getPreferredSize(): Dimension {
            val l = leftPanel.preferredSize
            val r = rightPanel.preferredSize
            return Dimension(l.width + hgap + r.width, maxOf(l.height, r.height))
        }
    }
    private val leftPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
    }
    // Custom rightPanel: gaps only between *visible* components; size never shrinks below preferred.
    private val rightPanel = object : JPanel(null) {
        private val gap = JBUIScale.scale(4)
        init { isOpaque = false }
        private fun visibleItems() = listOf(stateLabel, draftLabel, autoCompleteIcon, conflictIcon, reviewersLabel, commentsLabel)
            .filter { it.isVisible }
        override fun doLayout() {
            val totalH = height
            var x = 0
            val visible = visibleItems()
            visible.forEachIndexed { i, c ->
                val cPref = c.preferredSize
                val cy = maxOf(0, (totalH - cPref.height) / 2)
                c.setBounds(x, cy, cPref.width, cPref.height)
                x += cPref.width + if (i < visible.size - 1) gap else 0
            }
        }
        override fun getPreferredSize(): Dimension {
            val visible = visibleItems()
            if (visible.isEmpty()) return Dimension(0, 0)
            val w = visible.sumOf { it.preferredSize.width } + (visible.size - 1) * gap
            val h = visible.maxOf { it.preferredSize.height }
            return Dimension(w, h)
        }
        override fun getMinimumSize() = preferredSize
        override fun getMaximumSize() = preferredSize
    }

    init {
        isOpaque = true
        border = JBUI.Borders.empty(6, 10, 6, 10)

        // Info label (second line)
        infoLabel.font = JBFont.small()
        infoLabel.border = JBUI.Borders.emptyLeft(JBUIScale.scale(20))

        // Assemble left panel
        leftPanel.add(statusDot)
        leftPanel.add(Box.createHorizontalStrut(JBUIScale.scale(6)))
        leftPanel.add(titleLabel)

        // Assemble right panel — custom doLayout handles gaps between visible components only
        rightPanel.add(stateLabel)
        rightPanel.add(draftLabel)
        rightPanel.add(autoCompleteIcon)
        rightPanel.add(conflictIcon)
        rightPanel.add(reviewersLabel)
        rightPanel.add(commentsLabel)

        firstLinePanel.add(leftPanel)
        firstLinePanel.add(rightPanel)

        add(firstLinePanel, BorderLayout.CENTER)
        add(infoLabel, BorderLayout.SOUTH)
    }

    override fun getListCellRendererComponent(
        list: JList<out PullRequest>,
        value: PullRequest?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        if (value == null) return this

        // ── Colors ──
        background = if (isSelected) {
            UIUtil.getListSelectionBackground(list.hasFocus())
        } else {
            list.background
        }
        val primaryFg = if (isSelected) UIUtil.getListSelectionForeground(list.hasFocus()) else UIUtil.getListForeground()
        val secondaryFg = if (isSelected) UIUtil.getListSelectionForeground(list.hasFocus()) else JBColor.GRAY

        // ── Status dot ──
        statusDot.icon = StatusDotIcon(getStatusColor(value))

        // ── Title ──
        titleLabel.text = value.title
        titleLabel.foreground = primaryFg
        titleLabel.font = JBUI.Fonts.label().deriveFont(Font.PLAIN)

        // ── State badge ──
        updateStateBadge(value)

        // ── Draft badge ──
        if (value.isDraft == true) {
            draftLabel.isVisible = true
            draftLabel.text = "DRAFT"
            draftLabel.setColors(
                JBColor(Color(255, 165, 0), Color(255, 140, 0)),
                Color.WHITE
            )
        } else {
            draftLabel.isVisible = false
        }

        // ── Auto-complete icon ──
        if (value.hasAutoComplete()) {
            autoCompleteIcon.isVisible = true
            autoCompleteIcon.icon = AllIcons.RunConfigurations.TestPassed
            autoCompleteIcon.toolTipText = "Auto-complete enabled"
        } else {
            autoCompleteIcon.isVisible = false
        }

        // ── Conflict icon ──
        if (value.hasConflicts()) {
            conflictIcon.isVisible = true
            conflictIcon.icon = AllIcons.General.Warning
            conflictIcon.toolTipText = "Merge conflicts"
        } else {
            conflictIcon.isVisible = false
        }

        // ── Reviewer avatars ──
        updateReviewerAvatars(value, list)

        // ── Comment count ──
        // Azure DevOps doesn't have unresolvedReviewThreadsCount directly in PR list,
        // so we show reviewer count as a form of "engagement" indicator
        val reviewerCount = value.reviewers?.size ?: 0
        commentsLabel.icon = AllIcons.General.Balloon
        commentsLabel.text = reviewerCount.toString()
        commentsLabel.foreground = secondaryFg
        commentsLabel.font = JBFont.small()

        // ── Info line (second row) ──
        val authorName = value.createdBy?.displayName ?: "Unknown"
        val dateStr = formatDate(value.creationDate)
        val info = buildString {
            append("#${value.pullRequestId}")
            append(" · created $dateStr")
            append(", by $authorName")
            if (showRepositoryInfo()) {
                val repoName = value.repository?.name
                if (repoName != null) {
                    append(" · $repoName")
                }
            }
        }
        infoLabel.text = info
        infoLabel.foreground = secondaryFg

        return this
    }

    private fun updateStateBadge(pr: PullRequest) {
        when (pr.status) {
            PullRequestStatus.Completed -> {
                stateLabel.isVisible = true
                stateLabel.text = "MERGED"
                stateLabel.setColors(
                    JBColor(Color(111, 66, 193), Color(137, 87, 229)),
                    Color.WHITE
                )
            }
            PullRequestStatus.Abandoned -> {
                stateLabel.isVisible = true
                stateLabel.text = "CLOSED"
                stateLabel.setColors(
                    JBColor(Color(160, 55, 65), Color(200, 35, 51)),
                    Color.WHITE
                )
            }
            PullRequestStatus.Active -> {
                if (pr.isDraft == true) {
                    stateLabel.isVisible = false
                } else {
                    stateLabel.isVisible = false // OPEN is default, no badge shown (like GitHub plugin)
                }
            }
            else -> {
                stateLabel.isVisible = false
            }
        }
    }

    private fun updateReviewerAvatars(pr: PullRequest, list: JList<*>) {
        val reviewers = pr.reviewers
        if (reviewers.isNullOrEmpty()) {
            reviewersLabel.isVisible = false
            reviewersLabel.icon = null
            return
        }

        val icons = reviewers.take(MAX_REVIEWER_ICONS).map { reviewer ->
            avatarService.getAvatar(reviewer.imageUrl, AVATAR_SIZE) {
                list.repaint()
            }
        }

        reviewersLabel.isVisible = true
        reviewersLabel.icon = if (icons.size == 1) {
            icons[0]
        } else {
            OverlaidAvatarIcon(icons)
        }
    }

    private fun getStatusColor(pr: PullRequest): Color {
        return when {
            pr.isDraft == true -> JBColor(Color(255, 165, 0), Color(255, 140, 0))
            pr.status == PullRequestStatus.Active -> JBColor(Color(63, 185, 80), Color(63, 185, 80))
            pr.status == PullRequestStatus.Completed -> JBColor(Color(137, 87, 229), Color(137, 87, 229))
            pr.status == PullRequestStatus.Abandoned -> JBColor(Color(160, 55, 65), Color(200, 35, 51))
            else -> JBColor.GRAY
        }
    }

    private fun formatDate(dateString: String?): String {
        if (dateString == null) return "Unknown"
        return try {
            val parts = dateString.split("T")
            if (parts.isNotEmpty()) parts[0] else dateString
        } catch (_: Exception) {
            dateString
        }
    }

    companion object {
        private const val MAX_REVIEWER_ICONS = 4
        private const val AVATAR_SIZE = 20
    }
}

/**
 * Small colored circle icon used as the status indicator dot.
 */
private class StatusDotIcon(private val color: Color) : Icon {
    private val size = JBUIScale.scale(8)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        val insetY = (c?.height ?: size) / 2 - size / 2
        g2.fill(Ellipse2D.Double(x.toDouble(), (y + insetY - (c?.height ?: 0) / 2 + size).toDouble(), size.toDouble(), size.toDouble()))
        g2.dispose()
    }

    override fun getIconWidth(): Int = size
    override fun getIconHeight(): Int = size
}

/**
 * Overlapping avatar icons, similar to the GitHub plugin's OverlaidOffsetIconsIcon.
 */
private class OverlaidAvatarIcon(private val icons: List<Icon>) : Icon {
    private val overlap = JBUIScale.scale(6)

    override fun getIconWidth(): Int {
        if (icons.isEmpty()) return 0
        val first = icons[0].iconWidth
        return first + (icons.size - 1) * (first - overlap)
    }

    override fun getIconHeight(): Int = if (icons.isEmpty()) 0 else icons[0].iconHeight

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        if (icons.isEmpty()) return
        val step = icons[0].iconWidth - overlap
        for (i in icons.indices) {
            icons[i].paintIcon(c, g, x + i * step, y)
        }
    }
}

/**
 * Rounded colored badge label for state (MERGED, CLOSED, DRAFT).
 * Similar to createTagLabel in the platform collaboration-tools.
 */
private class StateBadgeLabel : JLabel() {
    private var bgColor: Color = JBColor.LIGHT_GRAY
    private val arcSize = JBUIScale.scale(6)

    init {
        isOpaque = false
        font = JBFont.small().deriveFont(Font.BOLD)
        border = JBUI.Borders.empty(1, 6, 1, 6)
        horizontalAlignment = CENTER
    }

    fun setColors(background: Color, foreground: Color) {
        this.bgColor = background
        this.foreground = foreground
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = bgColor
        g2.fillRoundRect(0, 0, width, height, arcSize, arcSize)
        g2.dispose()
        super.paintComponent(g)
    }
}
