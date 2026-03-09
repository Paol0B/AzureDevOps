package paol0b.azuredevops.toolwindow.filters

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.Icon
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * A filter chip component that mimics the JetBrains GitHub plugin's FilterComponent.
 *
 * When no value is selected: shows "FilterName ▾" in a muted style.
 * When a value is selected: shows "Value ×" with an active/highlighted style.
 *
 * Clicking the chip opens a popup (via [onShowPopup]).
 * Clicking the × clears the filter.
 */
class FilterChipComponent(
    private val filterName: String,
    private val onShowPopup: (FilterChipComponent) -> Unit,
    private val onClear: () -> Unit
) : JPanel(BorderLayout()) {

    private val label = JLabel()
    private val closeLabel = JLabel(AllIcons.Actions.Close)
    private var valueText: String? = null
    private var valueIcon: Icon? = null
    private var hovered = false

    companion object {
        private val INACTIVE_FG = JBColor.namedColor("Label.disabledForeground", JBColor(Color(150, 150, 150), Color(140, 140, 140)))
        private val ACTIVE_FG = JBColor.namedColor("Label.foreground", JBColor(Color(60, 60, 60), Color(200, 200, 200)))
        private val HOVER_BG = JBColor.namedColor("ActionButton.hoverBackground", JBColor(Color(0, 0, 0, 20), Color(255, 255, 255, 20)))
        private val ACTIVE_BG = JBColor.namedColor("ActionButton.pressedBackground", JBColor(Color(0, 0, 0, 30), Color(255, 255, 255, 25)))
        private val BORDER_COLOR = JBColor.namedColor("Component.borderColor", JBColor(Color(200, 200, 200), Color(70, 70, 70)))
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(2, 6)

        label.font = JBUI.Fonts.smallFont()
        label.foreground = INACTIVE_FG
        label.border = JBUI.Borders.emptyRight(2)

        closeLabel.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        closeLabel.isVisible = false
        closeLabel.border = JBUI.Borders.emptyLeft(2)
        closeLabel.toolTipText = "Clear filter"

        add(label, BorderLayout.CENTER)
        add(closeLabel, BorderLayout.EAST)

        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        updateDisplay()

        // Mouse handling for the whole chip
        addMouseListener(object : MouseAdapter() {
            override fun mouseEntered(e: MouseEvent) {
                hovered = true
                repaint()
            }

            override fun mouseExited(e: MouseEvent) {
                hovered = false
                repaint()
            }

            override fun mouseClicked(e: MouseEvent) {
                // If clicked on close area and value is set, clear it
                val closeArea = closeLabel.bounds
                if (valueText != null && closeArea.contains(e.point)) {
                    clearValue()
                } else {
                    onShowPopup(this@FilterChipComponent)
                }
            }
        })

        // Direct listener on close label for precise click handling
        closeLabel.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                e.consume()
                clearValue()
            }
        })
    }

    fun setValue(text: String?, icon: Icon? = null) {
        valueText = text
        valueIcon = icon
        updateDisplay()
        revalidate()
        repaint()
    }

    fun getValue(): String? = valueText

    fun clearValue() {
        valueText = null
        valueIcon = null
        updateDisplay()
        revalidate()
        repaint()
        onClear()
    }

    private fun updateDisplay() {
        if (valueText != null) {
            label.text = valueText
            label.icon = valueIcon
            label.foreground = ACTIVE_FG
            label.font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            closeLabel.isVisible = true
        } else {
            label.text = "$filterName \u25BE"  // ▾ down-pointing triangle
            label.icon = null
            label.foreground = INACTIVE_FG
            label.font = JBUI.Fonts.smallFont()
            closeLabel.isVisible = false
        }
    }

    override fun getPreferredSize(): Dimension {
        val pref = super.getPreferredSize()
        val h = maxOf(pref.height, JBUIScale.scale(24))
        return Dimension(pref.width, h)
    }

    override fun getMinimumSize(): Dimension = preferredSize

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val arc = JBUIScale.scale(6).toDouble()
        val shape = RoundRectangle2D.Double(
            0.0, 0.0,
            width.toDouble() - 1, height.toDouble() - 1,
            arc, arc
        )

        // Background
        if (valueText != null) {
            g2.color = ACTIVE_BG
            g2.fill(shape)
            g2.color = BORDER_COLOR
            g2.draw(shape)
        } else if (hovered) {
            g2.color = HOVER_BG
            g2.fill(shape)
        }

        g2.dispose()
        super.paintComponent(g)
    }
}
