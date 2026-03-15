package paol0b.azuredevops.toolwindow.metrics

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.JPanel

/**
 * A compact summary card showing a single metric with title, large value, and optional subtitle.
 * Mutable — call [update] to change displayed values without replacing the component.
 *
 * ┌─────────────────┐
 * │  Title           │
 * │  42.5h           │  ← large colored value
 * │  avg merge time  │  ← subtitle
 * └─────────────────┘
 */
class MetricCardComponent(
    private var title: String,
    private var value: String,
    private var subtitle: String = "",
    private var accentColor: Color = JBColor(Color(0x3574F0), Color(0x548AF7))
) : JPanel() {

    init {
        isOpaque = true
        border = JBUI.Borders.empty(12, 14)
        preferredSize = Dimension(170, 90)
        minimumSize = Dimension(140, 80)
    }

    fun update(newValue: String, newSubtitle: String = subtitle) {
        value = newValue
        subtitle = newSubtitle
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        val insets = insets
        val x = insets.left

        // Background with rounded border
        g2.color = UIUtil.getPanelBackground()
        g2.fillRoundRect(0, 0, width, height, 8, 8)
        g2.color = JBColor.border()
        g2.drawRoundRect(0, 0, width - 1, height - 1, 8, 8)

        var y = insets.top

        // Title
        g2.color = JBColor.GRAY
        g2.font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 11f)
        g2.drawString(title, x, y + g2.fontMetrics.ascent)
        y += g2.fontMetrics.height + JBUI.scale(4)

        // Large value
        g2.color = accentColor
        g2.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 20f)
        g2.drawString(value, x, y + g2.fontMetrics.ascent)
        y += g2.fontMetrics.height + JBUI.scale(2)

        // Subtitle
        if (subtitle.isNotBlank()) {
            g2.color = JBColor.GRAY
            g2.font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 10f)
            g2.drawString(subtitle, x, y + g2.fontMetrics.ascent)
        }

        g2.dispose()
    }
}
