package paol0b.azuredevops.checkout

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Rectangle
import java.awt.RenderingHints
import java.awt.event.ComponentAdapter
import java.awt.event.ComponentEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * Combo-box-style field that visualizes the current project selection.
 *
 * Rendering modes, decided per layout pass based on the available width:
 *  - **Loading**     no projects known yet, shows "Loading…" placeholder.
 *  - **Placeholder** projects exist but none selected, shows a grayed italic prompt.
 *  - **AllSelected** every project is selected, shows a single "All N projects" summary chip.
 *  - **Chips**       a chip per selected project, each with a × to deselect.
 *  - **Fallback**    chips don't fit, falls back to "X of Y projects selected" text.
 *
 * Click on the field body opens the popup (delegated via [onClick]); click on a chip's ×
 * removes that project (delegated via [onRemove]).
 */
internal class SelectedProjectsField(
    private val onClick: () -> Unit,
    private val onRemove: (projectId: String) -> Unit
) : JPanel(null) {

    private companion object {
        const val FIELD_HEIGHT_PX = 28
        const val FIELD_PAD_PX = 6
        const val CHIP_GAP_PX = 4
        const val FIELD_ARC_PX = 6

        val PLACEHOLDER_FG: JBColor = JBColor.namedColor(
            "Label.disabledForeground",
            JBColor(Color(150, 150, 150), Color(140, 140, 140))
        )
        val FIELD_BORDER: JBColor = JBColor.namedColor(
            "Component.borderColor",
            JBColor(Color(200, 200, 200), Color(70, 70, 70))
        )
        val FIELD_BG: JBColor = JBColor.namedColor(
            "TextField.background",
            JBColor(Color(255, 255, 255), Color(60, 63, 65))
        )
    }

    // Forwards a click on any non-interactive child (labels, caret) to the field's onClick.
    // Without this, clicks that happen to land directly on a JLabel are swallowed by the
    // label and the popup never opens — user perceives this as "needs multiple clicks".
    private val forwardClickToField = object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (isEnabled) onClick()
        }
    }

    private val placeholderLabel = JLabel().apply {
        foreground = PLACEHOLDER_FG
        font = JBUI.Fonts.smallFont().deriveFont(Font.ITALIC)
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(forwardClickToField)
    }
    private val fallbackLabel = JLabel().apply {
        font = JBUI.Fonts.smallFont()
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(forwardClickToField)
    }
    private val caretLabel = JLabel(AllIcons.General.ArrowDown).apply {
        horizontalAlignment = SwingConstants.CENTER
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        addMouseListener(forwardClickToField)
    }

    private data class ChipModel(val projectId: String?, val text: String)

    private var totalProjects = 0
    private var projectsKnown = false
    private var chipModels: List<ChipModel> = emptyList()

    // Cached chip components so we don't re-create them on every resize.
    private var chipCache: Map<ChipModel, Chip> = emptyMap()

    init {
        isOpaque = false
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (isEnabled) onClick()
            }
        })

        addComponentListener(object : ComponentAdapter() {
            override fun componentResized(e: ComponentEvent) {
                revalidate()
                repaint()
            }
        })
    }

    /**
     * Updates the displayed selection.
     *
     * @param projectsLoaded false while the project list hasn't been fetched yet (shows
     *                       a "Loading…" placeholder).
     * @param totalProjects  total number of projects available for the current account.
     * @param selected       projects currently selected, in display order.
     */
    fun setSelection(
        projectsLoaded: Boolean,
        totalProjects: Int,
        selected: List<AzureDevOpsCloneApiClient.Project>
    ) {
        this.projectsKnown = projectsLoaded
        this.totalProjects = totalProjects

        val sorted = selected.sortedBy { it.name.lowercase() }
        val newModels = sorted.map { ChipModel(it.id, it.name) }
        chipModels = newModels

        // Recycle existing chip components keyed by model so chip identity (and hover state)
        // is preserved across no-op layouts.
        val newCache = HashMap<ChipModel, Chip>(newModels.size)
        for (model in newModels) {
            newCache[model] = chipCache[model] ?: Chip(model)
        }
        chipCache = newCache

        revalidate()
        repaint()
    }

    override fun getPreferredSize(): Dimension =
        Dimension(JBUIScale.scale(120), JBUIScale.scale(FIELD_HEIGHT_PX))

    override fun getMinimumSize(): Dimension = preferredSize

    override fun doLayout() {
        removeAll()

        val w = width
        val h = height
        if (w <= 0 || h <= 0) return

        val pad = JBUIScale.scale(FIELD_PAD_PX)
        val gap = JBUIScale.scale(CHIP_GAP_PX)

        val caretSize = caretLabel.preferredSize
        add(caretLabel)
        caretLabel.bounds = Rectangle(
            w - pad - caretSize.width,
            (h - caretSize.height) / 2,
            caretSize.width,
            caretSize.height
        )

        val contentLeft = pad
        val contentRight = w - pad - caretSize.width - pad
        val contentW = contentRight - contentLeft
        if (contentW <= 0) return

        when {
            !projectsKnown -> placeText(placeholderLabel, "Loading…", contentLeft, contentW, h)
            totalProjects == 0 -> placeText(placeholderLabel, "No projects available", contentLeft, contentW, h)
            // Empty selection = "no filter" = same display as "all selected".
            chipModels.isEmpty() || chipModels.size >= totalProjects -> {
                val summary = Chip(ChipModel(null, "All $totalProjects projects"))
                val cw = summary.preferredSize.width.coerceAtMost(contentW)
                val ch = summary.preferredSize.height
                add(summary)
                summary.bounds = Rectangle(contentLeft, (h - ch) / 2, cw, ch)
            }
            else -> {
                val chips = chipModels.map { chipCache.getValue(it) }
                var totalChipsW = 0
                chips.forEachIndexed { i, chip ->
                    if (i > 0) totalChipsW += gap
                    totalChipsW += chip.preferredSize.width
                }
                if (totalChipsW <= contentW) {
                    var x = contentLeft
                    chips.forEach { chip ->
                        val cw = chip.preferredSize.width
                        val ch = chip.preferredSize.height
                        add(chip)
                        chip.bounds = Rectangle(x, (h - ch) / 2, cw, ch)
                        x += cw + gap
                    }
                } else {
                    placeText(
                        fallbackLabel,
                        "${chipModels.size} of $totalProjects projects selected",
                        contentLeft, contentW, h
                    )
                }
            }
        }
    }

    private fun placeText(label: JLabel, text: String, x: Int, contentW: Int, fieldH: Int) {
        label.text = text
        add(label)
        val labelH = label.preferredSize.height
        label.bounds = Rectangle(x, (fieldH - labelH) / 2, contentW, labelH)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val arc = JBUIScale.scale(FIELD_ARC_PX).toDouble()
            val shape = RoundRectangle2D.Double(
                0.5, 0.5, (width - 1).toDouble(), (height - 1).toDouble(), arc, arc
            )
            g2.color = if (isEnabled) FIELD_BG else UIUtil.getPanelBackground()
            g2.fill(shape)
            g2.color = FIELD_BORDER
            g2.draw(shape)
        } finally {
            g2.dispose()
        }
        super.paintComponent(g)
    }

    // ---- Inner chip ----

    private inner class Chip(model: ChipModel) : JPanel(BorderLayout(JBUIScale.scale(2), 0)) {
        private val projectId: String? = model.projectId
        private var hovered = false

        private val textLabel = JLabel(model.text).apply {
            font = JBUI.Fonts.smallFont().deriveFont(Font.BOLD)
            border = JBUI.Borders.empty(0, 6, 0, if (model.projectId == null) 6 else 0)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        private val closeLabel: JLabel? = if (model.projectId != null) {
            JLabel(AllIcons.Actions.Close).apply {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                border = JBUI.Borders.empty(0, 2, 0, 6)
                toolTipText = "Remove from selection"
            }
        } else null

        init {
            isOpaque = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            add(textLabel, BorderLayout.CENTER)
            closeLabel?.let { add(it, BorderLayout.EAST) }

            // Hover state — tracked on the chip itself; the label children inherit it visually
            // since they're drawn over the chip's painted background.
            val hoverListener = object : MouseAdapter() {
                override fun mouseEntered(e: MouseEvent) { hovered = true; repaint() }
                override fun mouseExited(e: MouseEvent) {
                    // Only un-hover when the mouse genuinely leaves the chip bounds — Swing
                    // also fires mouseExited when crossing into a child, which would flicker.
                    val pt = e.locationOnScreen
                    if (isShowing) {
                        val onScreen = locationOnScreen
                        val inChip = pt.x in onScreen.x until (onScreen.x + width) &&
                            pt.y in onScreen.y until (onScreen.y + height)
                        if (inChip) return
                    }
                    hovered = false
                    repaint()
                }
            }
            addMouseListener(hoverListener)
            textLabel.addMouseListener(hoverListener)
            closeLabel?.addMouseListener(hoverListener)

            // Click on the chip body (panel area or text label) opens the popup, just like
            // clicking on the field itself.
            val bodyClickListener = object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    this@SelectedProjectsField.onClick()
                }
            }
            addMouseListener(bodyClickListener)
            textLabel.addMouseListener(bodyClickListener)

            // The × icon: dedicated listener so the click never falls through to the body
            // handler (which would re-open the popup right after we asked to remove).
            if (closeLabel != null && projectId != null) {
                closeLabel.addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) {
                        this@SelectedProjectsField.onRemove(projectId)
                    }
                })
            }
        }

        override fun getPreferredSize(): Dimension {
            val pref = super.getPreferredSize()
            return Dimension(pref.width, JBUIScale.scale(20))
        }

        override fun paintComponent(g: Graphics) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                val arc = JBUIScale.scale(5).toDouble()
                val shape = RoundRectangle2D.Double(
                    0.0, 0.0, (width - 1).toDouble(), (height - 1).toDouble(), arc, arc
                )
                g2.color = if (hovered) HOVER_BG else CHIP_BG
                g2.fill(shape)
                g2.color = CHIP_BORDER
                g2.draw(shape)
            } finally {
                g2.dispose()
            }
            super.paintComponent(g)
        }
    }

    private val CHIP_BG: JBColor = JBColor.namedColor(
        "ActionButton.pressedBackground",
        JBColor(Color(0, 0, 0, 30), Color(255, 255, 255, 25))
    )
    private val HOVER_BG: JBColor = JBColor.namedColor(
        "ActionButton.hoverBackground",
        JBColor(Color(0, 0, 0, 20), Color(255, 255, 255, 20))
    )
    private val CHIP_BORDER: JBColor = JBColor.namedColor(
        "Component.borderColor",
        JBColor(Color(200, 200, 200), Color(70, 70, 70))
    )
}
