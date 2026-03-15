package paol0b.azuredevops.toolwindow.metrics

import com.intellij.ui.JBColor
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.WeeklyBucket
import java.awt.*
import javax.swing.JPanel

/**
 * Custom-painted vertical bar chart for weekly trends.
 * Renders axis labels, grid lines, and colored bars with hover tooltips.
 */
class BarChartComponent(
    private val chartTitle: String,
    private val barColor: Color = JBColor(Color(0x3574F0), Color(0x548AF7)),
    private val unitLabel: String = ""
) : JPanel() {

    private var data: List<WeeklyBucket> = emptyList()
    private var hoveredIndex: Int = -1

    init {
        isOpaque = false
        preferredSize = Dimension(400, 200)
        minimumSize = Dimension(200, 140)

        addMouseMotionListener(object : java.awt.event.MouseMotionAdapter() {
            override fun mouseMoved(e: java.awt.event.MouseEvent) {
                val newIndex = hitTestBar(e.x, e.y)
                if (newIndex != hoveredIndex) {
                    hoveredIndex = newIndex
                    toolTipText = if (newIndex >= 0 && newIndex < data.size) {
                        val b = data[newIndex]
                        "${b.label}: ${formatValue(b.value)} $unitLabel"
                    } else null
                    repaint()
                }
            }
        })
    }

    fun setData(buckets: List<WeeklyBucket>) {
        data = buckets
        hoveredIndex = -1
        repaint()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        val insets = insets
        val chartLeft = insets.left + JBUI.scale(40)
        val chartTop = insets.top + JBUI.scale(24)
        val chartRight = width - insets.right - JBUI.scale(8)
        val chartBottom = height - insets.bottom - JBUI.scale(30)
        val chartWidth = chartRight - chartLeft
        val chartHeight = chartBottom - chartTop

        // Title
        g2.color = UIUtil.getLabelForeground()
        g2.font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 12f)
        g2.drawString(chartTitle, insets.left, insets.top + g2.fontMetrics.ascent)

        if (data.isEmpty() || chartWidth <= 0 || chartHeight <= 0) {
            g2.color = JBColor.GRAY
            g2.font = UIUtil.getLabelFont().deriveFont(Font.ITALIC, 11f)
            g2.drawString("No data", chartLeft + chartWidth / 2 - 20, chartTop + chartHeight / 2)
            g2.dispose()
            return
        }

        val maxVal = data.maxOf { it.value }.coerceAtLeast(1.0)

        // Grid lines
        g2.color = JBColor(Color(0xE0E0E0), Color(0x3C3F41))
        g2.font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 9f)
        val gridSteps = 4
        for (i in 0..gridSteps) {
            val y = chartBottom - (chartHeight * i / gridSteps)
            g2.color = JBColor(Color(0xE8E8E8), Color(0x3C3F41))
            g2.drawLine(chartLeft, y, chartRight, y)
            g2.color = JBColor.GRAY
            val label = formatValue(maxVal * i / gridSteps)
            val labelWidth = g2.fontMetrics.stringWidth(label)
            g2.drawString(label, chartLeft - labelWidth - JBUI.scale(4), y + g2.fontMetrics.ascent / 2)
        }

        // Bars
        val barCount = data.size
        val totalGap = JBUI.scale(2) * (barCount - 1).coerceAtLeast(0)
        val barWidth = ((chartWidth - totalGap) / barCount.toDouble()).toInt().coerceAtLeast(3)
        val gap = if (barCount > 1) ((chartWidth - barWidth * barCount) / (barCount - 1).toDouble()).toInt() else 0

        data.forEachIndexed { index, bucket ->
            val barHeight = ((bucket.value / maxVal) * chartHeight).toInt().coerceAtLeast(1)
            val x = chartLeft + index * (barWidth + gap)
            val y = chartBottom - barHeight

            // Bar fill
            val color = if (index == hoveredIndex) barColor.brighter() else barColor
            g2.color = color
            g2.fillRoundRect(x, y, barWidth, barHeight, 3, 3)

            // X-axis label (show every Nth to avoid overlap)
            val showLabel = barCount <= 12 || index % ((barCount / 8).coerceAtLeast(1)) == 0
            if (showLabel) {
                g2.color = JBColor.GRAY
                g2.font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 9f)
                val labelWidth = g2.fontMetrics.stringWidth(bucket.label)
                val labelX = x + barWidth / 2 - labelWidth / 2
                g2.drawString(bucket.label, labelX, chartBottom + g2.fontMetrics.height)
            }
        }

        g2.dispose()
    }

    private fun hitTestBar(mouseX: Int, mouseY: Int): Int {
        if (data.isEmpty()) return -1
        val insets = insets
        val chartLeft = insets.left + JBUI.scale(40)
        val chartRight = width - insets.right - JBUI.scale(8)
        val chartWidth = chartRight - chartLeft
        val barCount = data.size
        val totalGap = JBUI.scale(2) * (barCount - 1).coerceAtLeast(0)
        val barWidth = ((chartWidth - totalGap) / barCount.toDouble()).toInt().coerceAtLeast(3)
        val gap = if (barCount > 1) ((chartWidth - barWidth * barCount) / (barCount - 1).toDouble()).toInt() else 0

        data.forEachIndexed { index, _ ->
            val x = chartLeft + index * (barWidth + gap)
            if (mouseX in x..(x + barWidth)) return index
        }
        return -1
    }

    private fun formatValue(v: Double): String {
        return if (v == v.toLong().toDouble()) v.toLong().toString()
        else String.format("%.1f", v)
    }
}
