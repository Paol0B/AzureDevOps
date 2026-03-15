package paol0b.azuredevops.toolwindow.metrics

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.LeaderboardEntry
import java.awt.*
import javax.swing.JPanel

/**
 * Horizontal bar chart leaderboard showing ranked entries (e.g., top authors, top reviewers).
 * Each row shows: rank medal/number, name, colored bar proportional to count, count label.
 */
class LeaderboardComponent(
    private val chartTitle: String,
    private val barColor: Color = JBColor(Color(0x3574F0), Color(0x548AF7))
) : JPanel() {

    private var entries: List<LeaderboardEntry> = emptyList()

    init {
        isOpaque = false
        preferredSize = Dimension(350, 200)
        minimumSize = Dimension(200, 120)
    }

    fun setData(data: List<LeaderboardEntry>) {
        entries = data.take(7)
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        val insets = insets
        val x0 = insets.left

        // Title
        g2.color = UIUtil.getLabelForeground()
        g2.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 12f)
        g2.drawString(chartTitle, x0, insets.top + g2.fontMetrics.ascent)

        if (entries.isEmpty()) {
            g2.color = JBColor.GRAY
            g2.font = UIUtil.getLabelFont().deriveFont(Font.ITALIC, 11f)
            g2.drawString("No data", x0 + JBUI.scale(10), insets.top + JBUI.scale(40))
            g2.dispose()
            return
        }

        val startY = insets.top + JBUI.scale(28)
        val nameColWidth = JBUI.scale(110)
        val countColWidth = JBUI.scale(32)
        val barAreaLeft = x0 + JBUI.scale(24) + nameColWidth
        val barAreaRight = width - insets.right - countColWidth - JBUI.scale(8)
        val barAreaWidth = (barAreaRight - barAreaLeft).coerceAtLeast(20)
        val rowHeight = JBUI.scale(22)
        val barHeight = JBUI.scale(14)
        val maxCount = entries.maxOf { it.count }.coerceAtLeast(1)

        entries.forEachIndexed { index, entry ->
            val y = startY + index * rowHeight

            // Rank
            g2.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 11f)
            val rankStr = when (index) {
                0 -> "\uD83E\uDD47" // gold medal emoji
                1 -> "\uD83E\uDD48"
                2 -> "\uD83E\uDD49"
                else -> "${index + 1}."
            }
            g2.color = UIUtil.getLabelForeground()
            g2.drawString(rankStr, x0, y + g2.fontMetrics.ascent)

            // Name (truncated)
            g2.font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 11f)
            g2.color = UIUtil.getLabelForeground()
            val name = truncate(entry.displayName, g2.fontMetrics, nameColWidth)
            g2.drawString(name, x0 + JBUI.scale(24), y + g2.fontMetrics.ascent)

            // Bar
            val barW = ((entry.count.toDouble() / maxCount) * barAreaWidth).toInt().coerceAtLeast(3)
            val barY = y + (rowHeight - barHeight) / 2
            g2.color = barColor
            g2.fillRoundRect(barAreaLeft, barY, barW, barHeight, 4, 4)

            // Count
            g2.color = JBColor.GRAY
            g2.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 10f)
            g2.drawString(entry.count.toString(), barAreaRight + JBUI.scale(6), y + g2.fontMetrics.ascent)
        }

        g2.dispose()
    }

    private fun truncate(text: String, fm: FontMetrics, maxWidth: Int): String {
        if (fm.stringWidth(text) <= maxWidth) return text
        var truncated = text
        while (truncated.isNotEmpty() && fm.stringWidth("$truncated...") > maxWidth) {
            truncated = truncated.dropLast(1)
        }
        return "$truncated..."
    }
}
