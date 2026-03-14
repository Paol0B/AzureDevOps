package paol0b.azuredevops.toolwindow.review.timeline

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

/**
 * Shared design tokens for the PR timeline.
 * Uses Zinc palette for neutrals, semantic colors for status.
 */
object TimelineTheme {

    // ── Surfaces ────────────────────────────────────────────────────
    val CARD_BG: Color get() = JBColor(Color(0xFFFFFF), Color(0x1E1F22))
    val CARD_BORDER: Color get() = JBColor(Color(0xC8CCD0), Color(0x393B40))
    val CARD_SHADOW: Color get() = JBColor(Color(0, 0, 0, 25), Color(0, 0, 0, 40))
    val SURFACE: Color get() = JBColor(Color(0xEEEFF1), Color(0x1A1B1E))

    // ── Timeline connector ──────────────────────────────────────────
    val TIMELINE_LINE: Color get() = JBColor(Color(0xD4D4D8), Color(0x3F3F46))
    val TIMELINE_DOT: Color get() = JBColor(Color(0xA1A1AA), Color(0x71717A))

    // ── Typography ──────────────────────────────────────────────────
    val PRIMARY_FG: Color get() = JBColor(Color(0x18181B), Color(0xFAFAFA))
    val SECONDARY_FG: Color get() = JBColor(Color(0x71717A), Color(0xA1A1AA))
    val MUTED_FG: Color get() = JBColor(Color(0xA1A1AA), Color(0x71717A))
    val LINK_FG: Color get() = JBColor(Color(0x2563EB), Color(0x60A5FA))

    // ── Reply accent ────────────────────────────────────────────────
    val REPLY_BG: Color get() = JBColor(Color(0xF4F4F5), Color(0x27272A))
    val REPLY_BAR: Color get() = JBColor(Color(0x3B82F6), Color(0x60A5FA))

    // ── Status badges ───────────────────────────────────────────────
    val ACTIVE_BG: Color get() = JBColor(Color(0xDBEAFE), Color(0x1E3A5F))
    val ACTIVE_FG: Color get() = JBColor(Color(0x1D4ED8), Color(0x93C5FD))
    val RESOLVED_BG: Color get() = JBColor(Color(0xDCFCE7), Color(0x14532D))
    val RESOLVED_FG: Color get() = JBColor(Color(0x166534), Color(0x86EFAC))

    // ── Vote colors ─────────────────────────────────────────────────
    val APPROVED_BG: Color get() = JBColor(Color(0xDCFCE7), Color(0x14532D))
    val APPROVED_FG: Color get() = JBColor(Color(0x166534), Color(0x86EFAC))
    val SUGGEST_BG: Color get() = JBColor(Color(0xFEF3C7), Color(0x451A03))
    val SUGGEST_FG: Color get() = JBColor(Color(0x92400E), Color(0xFCD34D))
    val WAIT_BG: Color get() = JBColor(Color(0xFFEDD5), Color(0x431407))
    val WAIT_FG: Color get() = JBColor(Color(0x9A3412), Color(0xFDBA74))
    val REJECT_BG: Color get() = JBColor(Color(0xFEE2E2), Color(0x450A0A))
    val REJECT_FG: Color get() = JBColor(Color(0x991B1B), Color(0xFCA5A5))
    val NOVOTE_BG: Color get() = JBColor(Color(0xF4F4F5), Color(0x27272A))
    val NOVOTE_FG: Color get() = JBColor(Color(0x71717A), Color(0xA1A1AA))

    // ── Vote event accent lines ─────────────────────────────────────
    val VOTE_APPROVED_ACCENT: Color get() = JBColor(Color(0x22C55E), Color(0x4ADE80))
    val VOTE_WARN_ACCENT: Color get() = JBColor(Color(0xF59E0B), Color(0xFBBF24))
    val VOTE_REJECT_ACCENT: Color get() = JBColor(Color(0xEF4444), Color(0xF87171))

    // ── Dimensions ──────────────────────────────────────────────────
    const val CARD_RADIUS = 12
    const val AVATAR_SIZE = 28
    const val AVATAR_SMALL = 20
    const val TIMELINE_LEFT_MARGIN = 44  // px left of cards for the timeline connector
}

/**
 * Modern elevated card with rounded corners, subtle border, and drop shadow.
 * Optionally draws a colored top-accent bar.
 */
class ElevatedCard(
    private val accentColor: Color? = null
) : JPanel() {

    init { isOpaque = false }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val arc = JBUI.scale(TimelineTheme.CARD_RADIUS).toFloat()
        val inset = 1.5f
        val shape = RoundRectangle2D.Float(inset, inset, width - inset * 2, height - inset * 2, arc, arc)

        // Shadow (offset 0, 2)
        g2.color = TimelineTheme.CARD_SHADOW
        val shadowShape = RoundRectangle2D.Float(inset, inset + 2f, width - inset * 2, height - inset * 2, arc, arc)
        g2.fill(shadowShape)

        // Fill
        g2.color = TimelineTheme.CARD_BG
        g2.fill(shape)

        // Top accent bar
        if (accentColor != null) {
            val clip = g2.clip
            g2.clip(shape)
            g2.color = accentColor
            g2.fillRect(0, 0, width, JBUI.scale(3))
            g2.clip = clip
        }

        // Border
        g2.color = TimelineTheme.CARD_BORDER
        g2.stroke = BasicStroke(1f)
        g2.draw(shape)

        g2.dispose()
    }
}

/**
 * A single row in the connected timeline.
 *
 * Draws a vertical line on the left with a dot at the avatar position,
 * then renders the content card to the right.
 *
 * ┃        ┌─────────────────┐
 * ● ────── │  Card content   │
 * ┃        └─────────────────┘
 */
class TimelineRow(
    private val avatarIcon: javax.swing.Icon?,
    private val isFirst: Boolean = false,
    private val isLast: Boolean = false,
    private val dotColor: Color = TimelineTheme.TIMELINE_DOT
) : JPanel(BorderLayout()) {

    init {
        isOpaque = false
        border = JBUI.Borders.empty(0, 0, 2, 0)
    }

    override fun paintChildren(g: Graphics) {
        // Paint timeline connector BEHIND children
        paintTimelineConnector(g)
        super.paintChildren(g)
    }

    private fun paintTimelineConnector(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val lineX = JBUI.scale(20)  // center of the timeline
        val dotRadius = JBUI.scale(5)
        val dotY = JBUI.scale(18)   // vertical center of avatar area

        // Vertical line
        g2.color = TimelineTheme.TIMELINE_LINE
        g2.stroke = BasicStroke(JBUI.scale(2).toFloat())
        val lineTop = if (isFirst) dotY else 0
        val lineBottom = if (isLast) dotY else height
        g2.drawLine(lineX, lineTop, lineX, lineBottom)

        // Dot
        g2.color = dotColor
        g2.fillOval(lineX - dotRadius, dotY - dotRadius, dotRadius * 2, dotRadius * 2)

        // Avatar (drawn on top of the dot area)
        if (avatarIcon != null) {
            val avatarX = lineX - avatarIcon.iconWidth / 2
            val avatarY = dotY - avatarIcon.iconHeight / 2
            avatarIcon.paintIcon(this, g2, avatarX, avatarY)
        }

        g2.dispose()
    }
}
