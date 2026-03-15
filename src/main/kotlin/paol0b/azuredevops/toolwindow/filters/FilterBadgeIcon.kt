package paol0b.azuredevops.toolwindow.filters

import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import java.awt.Color
import java.awt.Component
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Ellipse2D
import javax.swing.Icon

/**
 * Wraps a base icon and paints a small blue dot badge in the top-right corner
 * when [showBadge] is true — replicating the JetBrains GitHub plugin's
 * filter-active indicator on the funnel button.
 *
 * Shared across PR, Pipeline, and Work Item filter panels.
 */
class FilterBadgeIcon(private val base: Icon) : Icon {
    var showBadge: Boolean = false

    override fun getIconWidth(): Int = base.iconWidth
    override fun getIconHeight(): Int = base.iconHeight

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        base.paintIcon(c, g, x, y)
        if (!showBadge) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val dotSize = JBUIScale.scale(6)
            g2.color = JBColor(Color(71, 136, 227), Color(75, 110, 175))
            g2.fill(Ellipse2D.Double(
                (x + iconWidth - dotSize).toDouble(), y.toDouble(),
                dotSize.toDouble(), dotSize.toDouble()
            ))
        } finally {
            g2.dispose()
        }
    }
}
