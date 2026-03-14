package paol0b.azuredevops.toolwindow.review.timeline

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.ReviewerVote
import paol0b.azuredevops.services.AvatarService
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * Horizontal panel showing reviewer vote badges as rounded pills.
 */
class VoteBadgePanel(
    private val project: Project
) : JPanel() {

    private val avatarService = AvatarService.getInstance(project)

    init {
        layout = FlowLayout(FlowLayout.LEFT, 6, 4)
        isOpaque = false
        border = JBUI.Borders.empty(4, 0, 8, 0)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    fun update(summaries: List<ReviewerVoteSummary>) {
        removeAll()

        add(JBLabel("Reviewers").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = TimelineTheme.SECONDARY_FG
            border = JBUI.Borders.emptyRight(2)
        })

        if (summaries.isEmpty()) {
            add(JBLabel("No reviewers assigned").apply {
                foreground = TimelineTheme.MUTED_FG
                font = font.deriveFont(Font.ITALIC, 11f)
            })
        } else {
            for (summary in summaries) {
                add(createBadge(summary))
            }
        }

        revalidate()
        repaint()
    }

    private fun createBadge(summary: ReviewerVoteSummary): JComponent {
        val reviewer = summary.reviewer
        val vote = summary.vote
        val name = reviewer.displayName ?: "Unknown"

        val (bg, fg, symbol) = when (vote) {
            ReviewerVote.Approved -> Triple(TimelineTheme.APPROVED_BG, TimelineTheme.APPROVED_FG, "\u2713")
            ReviewerVote.ApprovedWithSuggestions -> Triple(TimelineTheme.SUGGEST_BG, TimelineTheme.SUGGEST_FG, "\u2713")
            ReviewerVote.WaitingForAuthor -> Triple(TimelineTheme.WAIT_BG, TimelineTheme.WAIT_FG, "\u26A0")
            ReviewerVote.Rejected -> Triple(TimelineTheme.REJECT_BG, TimelineTheme.REJECT_FG, "\u2717")
            ReviewerVote.NoVote -> Triple(TimelineTheme.NOVOTE_BG, TimelineTheme.NOVOTE_FG, "\u25CB")
        }

        val avatarIcon = avatarService.getAvatar(reviewer.imageUrl, 18) { repaint() }
        val label = "$symbol $name"
        val tooltip = "${vote.getDisplayName()} \u2014 $name${if (reviewer.isRequired == true) " (Required)" else ""}"

        return object : JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)) {
            init {
                isOpaque = false
                border = JBUI.Borders.empty(3, 10, 3, 10)
                toolTipText = tooltip
                add(JBLabel(avatarIcon))
                add(JBLabel(label).apply {
                    foreground = fg
                    font = getFont().deriveFont(Font.BOLD, 11f)
                })
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = height.toFloat()
                g2.color = bg
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc))
                g2.dispose()
                super.paintComponent(g)
            }
        }
    }
}
