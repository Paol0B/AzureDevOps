package paol0b.azuredevops.toolwindow.review.timeline

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.Reviewer
import paol0b.azuredevops.model.ReviewerVote
import paol0b.azuredevops.services.AvatarService
import java.awt.*
import javax.swing.*

/**
 * A horizontal panel that displays all reviewer vote badges in a compact, visual way.
 *
 * ┌──────────────────────────────────────────────────────────────────┐
 * │ Reviewers:  ✅ Paolo (Approved)  ⏳ Davide (Waiting)  ○ Maria   │
 * └──────────────────────────────────────────────────────────────────┘
 */
class VoteBadgePanel(
    private val project: Project
) : JPanel() {

    private val avatarService = AvatarService.getInstance(project)

    private val approvedBg = JBColor(Color(220, 255, 220), Color(30, 60, 30))
    private val approvedFg = JBColor(Color(34, 139, 34), Color(50, 200, 50))

    private val suggestBg = JBColor(Color(255, 245, 220), Color(60, 50, 25))
    private val suggestFg = JBColor(Color(180, 120, 0), Color(255, 180, 50))

    private val waitBg = JBColor(Color(255, 240, 220), Color(60, 45, 25))
    private val waitFg = JBColor(Color(200, 130, 0), Color(255, 165, 0))

    private val rejectBg = JBColor(Color(255, 225, 225), Color(60, 30, 30))
    private val rejectFg = JBColor(Color(200, 40, 40), Color(255, 80, 80))

    private val noVoteFg = JBColor.GRAY

    init {
        layout = FlowLayout(FlowLayout.LEFT, 6, 4)
        isOpaque = false
        border = JBUI.Borders.empty(6, 0, 6, 0)
        alignmentX = Component.LEFT_ALIGNMENT
    }

    /**
     * Update the panel with the current list of reviewer vote summaries.
     */
    fun update(summaries: List<ReviewerVoteSummary>) {
        removeAll()

        add(JBLabel("Reviewers").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = JBColor.GRAY
            border = JBUI.Borders.emptyRight(4)
        })

        if (summaries.isEmpty()) {
            add(JBLabel("No reviewers assigned").apply {
                foreground = JBColor.GRAY
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

        val (icon, bg, fg, label) = when (vote) {
            ReviewerVote.Approved -> Quad(
                AllIcons.RunConfigurations.TestPassed, approvedBg, approvedFg, "✅ $name"
            )
            ReviewerVote.ApprovedWithSuggestions -> Quad(
                AllIcons.General.Information, suggestBg, suggestFg, "🔄 $name"
            )
            ReviewerVote.WaitingForAuthor -> Quad(
                AllIcons.General.Warning, waitBg, waitFg, "⏳ $name"
            )
            ReviewerVote.Rejected -> Quad(
                AllIcons.RunConfigurations.TestFailed, rejectBg, rejectFg, "❌ $name"
            )
            ReviewerVote.NoVote -> Quad(
                AllIcons.Debugger.ThreadSuspended, UIUtil.getPanelBackground(), noVoteFg, "○ $name"
            )
        }

        val panel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            isOpaque = true
            background = bg
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor(Color(200, 200, 200), Color(70, 70, 70)), 1),
                JBUI.Borders.empty(2, 6, 2, 6)
            )
            cursor = Cursor.getDefaultCursor()
            toolTipText = "${vote.getDisplayName()} — $name${if (reviewer.isRequired == true) " (Required)" else ""}"
        }

        // Avatar
        val avatarIcon = avatarService.getAvatar(reviewer.imageUrl, 18) { panel.repaint() }
        panel.add(JBLabel(avatarIcon))

        // Name + vote
        panel.add(JBLabel(label).apply {
            foreground = fg
            font = font.deriveFont(Font.BOLD, 11f)
        })

        return panel
    }

    private data class Quad(val icon: Icon, val bg: Color, val fg: Color, val label: String)
}
