package paol0b.azuredevops.toolwindow.metrics

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.LabeledValue
import java.awt.*
import java.awt.geom.Arc2D
import javax.swing.JPanel

/**
 * Custom-painted donut chart with a legend.
 * Each slice is colored from a predefined palette with hover tooltips.
 */
class DonutChartComponent(
    private val chartTitle: String
) : JPanel() {

    private var data: List<LabeledValue> = emptyList()
    private var hoveredIndex: Int = -1

    companion object {
        private val PALETTE = arrayOf(
            JBColor(Color(0x3574F0), Color(0x548AF7)),  // Blue
            JBColor(Color(0x2DA44E), Color(0x3FB950)),  // Green
            JBColor(Color(0xCF222E), Color(0xF85149)),  // Red
            JBColor(Color(0xBF8700), Color(0xD29922)),  // Yellow
            JBColor(Color(0x8250DF), Color(0xA371F7)),  // Purple
            JBColor(Color(0x0969DA), Color(0x58A6FF)),  // Light blue
            JBColor(Color(0x6E7781), Color(0x8B949E))   // Gray
        )
    }

    init {
        isOpaque = false
        preferredSize = Dimension(300, 200)
        minimumSize = Dimension(200, 150)

        addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                val newIndex = hitTestSlice(e.x, e.y)
                if (newIndex != hoveredIndex) {
                    hoveredIndex = newIndex
                    toolTipText = if (newIndex >= 0 && newIndex < data.size) {
                        val d = data[newIndex]
                        val total = data.sumOf { it.value }
                        val pct = if (total > 0) (d.value / total * 100) else 0.0
                        "${d.label}: ${d.value.toInt()} (${String.format("%.0f", pct)}%)"
                    } else null
                    repaint()
                }
            }
        })
    }

    fun setData(values: List<LabeledValue>) {
        data = values
        hoveredIndex = -1
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        val insets = insets

        // Title
        g2.color = UIUtil.getLabelForeground()
        g2.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 12f)
        g2.drawString(chartTitle, insets.left, insets.top + g2.fontMetrics.ascent)

        if (data.isEmpty()) {
            g2.color = JBColor.GRAY
            g2.font = UIUtil.getLabelFont().deriveFont(Font.ITALIC, 11f)
            g2.drawString("No data", width / 2 - 20, height / 2)
            g2.dispose()
            return
        }

        val titleHeight = JBUI.scale(24)
        val availHeight = height - insets.top - insets.bottom - titleHeight
        val legendWidth = JBUI.scale(120)
        val donutSize = (minOf(width - insets.left - insets.right - legendWidth, availHeight) * 0.85).toInt()
        val donutX = insets.left + (width - insets.left - insets.right - legendWidth - donutSize) / 2
        val donutY = insets.top + titleHeight + (availHeight - donutSize) / 2
        val thickness = (donutSize * 0.25).toInt()

        val total = data.sumOf { it.value }
        if (total <= 0) {
            g2.dispose()
            return
        }

        // Draw arcs
        var startAngle = 90.0
        data.forEachIndexed { index, item ->
            val sweep = (item.value / total) * 360.0
            val color = PALETTE[index % PALETTE.size]

            g2.color = if (index == hoveredIndex) color.brighter() else color
            g2.stroke = BasicStroke(thickness.toFloat(), BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER)
            val arc = Arc2D.Double(
                (donutX + thickness / 2).toDouble(),
                (donutY + thickness / 2).toDouble(),
                (donutSize - thickness).toDouble(),
                (donutSize - thickness).toDouble(),
                startAngle,
                -sweep,
                Arc2D.OPEN
            )
            g2.draw(arc)
            startAngle -= sweep
        }

        // Center text: total
        g2.color = UIUtil.getLabelForeground()
        g2.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 18f)
        val totalStr = total.toInt().toString()
        val totalWidth = g2.fontMetrics.stringWidth(totalStr)
        g2.drawString(totalStr, donutX + donutSize / 2 - totalWidth / 2, donutY + donutSize / 2 + g2.fontMetrics.ascent / 2 - JBUI.scale(4))

        g2.font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 9f)
        g2.color = JBColor.GRAY
        val label = "total"
        val labelW = g2.fontMetrics.stringWidth(label)
        g2.drawString(label, donutX + donutSize / 2 - labelW / 2, donutY + donutSize / 2 + JBUI.scale(12))

        // Legend
        val legendX = width - insets.right - legendWidth
        var legendY = donutY + JBUI.scale(4)
        g2.font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 11f)
        val lineHeight = g2.fontMetrics.height + JBUI.scale(4)

        data.forEachIndexed { index, item ->
            val color = PALETTE[index % PALETTE.size]
            g2.color = color
            g2.fillRoundRect(legendX, legendY + 2, JBUI.scale(10), JBUI.scale(10), 3, 3)

            g2.color = UIUtil.getLabelForeground()
            val pct = if (total > 0) String.format("%.0f%%", item.value / total * 100) else "0%"
            g2.drawString("${item.label} ($pct)", legendX + JBUI.scale(16), legendY + g2.fontMetrics.ascent)
            legendY += lineHeight
        }

        g2.dispose()
    }

    private fun hitTestSlice(mx: Int, my: Int): Int {
        if (data.isEmpty()) return -1
        val insets = insets
        val titleHeight = JBUI.scale(24)
        val availHeight = height - insets.top - insets.bottom - titleHeight
        val legendWidth = JBUI.scale(120)
        val donutSize = (minOf(width - insets.left - insets.right - legendWidth, availHeight) * 0.85).toInt()
        val cx = insets.left + (width - insets.left - insets.right - legendWidth - donutSize) / 2 + donutSize / 2
        val cy = insets.top + titleHeight + (availHeight - donutSize) / 2 + donutSize / 2

        val dx = mx - cx
        val dy = my - cy
        val dist = Math.sqrt((dx * dx + dy * dy).toDouble())
        val thickness = donutSize * 0.25
        val innerR = donutSize / 2.0 - thickness
        val outerR = donutSize / 2.0
        if (dist < innerR || dist > outerR) return -1

        // Angle from top (clockwise)
        var angle = Math.toDegrees(Math.atan2(-dx.toDouble(), -dy.toDouble())) + 180.0
        angle = (angle + 90) % 360.0 // adjust to start from top

        val total = data.sumOf { it.value }
        if (total <= 0) return -1

        var cumAngle = 0.0
        data.forEachIndexed { index, item ->
            val sweep = (item.value / total) * 360.0
            if (angle >= cumAngle && angle < cumAngle + sweep) return index
            cumAngle += sweep
        }
        return -1
    }
}
