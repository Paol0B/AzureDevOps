package paol0b.azuredevops.toolwindow.workitem

import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.WorkItem
import paol0b.azuredevops.services.AvatarService
import java.awt.*
import java.awt.geom.Ellipse2D
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * Custom cell renderer for the Work Item list, replicating the
 * GitHub-plugin-style layout used by [PullRequestListCellRenderer].
 *
 * Two-line layout per row:
 *   Line 1: [type dot] [title]  ...  [state badge] [priority badge]
 *   Line 2: Type #id · assigned to · changed date · iteration
 */
class WorkItemListCellRenderer(
    private val avatarService: AvatarService
) : ListCellRenderer<WorkItem>, JPanel(BorderLayout()) {

    // Components
    private val typeDot = JLabel()
    private val titleLabel = JLabel().apply { minimumSize = Dimension(0, 0) }
    private val stateBadge = BadgeLabel()
    private val priorityBadge = BadgeLabel()
    private val avatarLabel = JLabel()
    private val commentCountLabel = JLabel()
    private val infoLabel = JLabel()

    // First line: left (dot + title), right (badges)
    private val firstLinePanel = object : JPanel(null) {
        private val hgap = JBUIScale.scale(6)
        init { isOpaque = false }
        override fun doLayout() {
            val ins = insets
            val totalW = width - ins.left - ins.right
            val totalH = height - ins.top - ins.bottom
            val rightW = minOf(rightPanel.preferredSize.width, totalW)
            val leftW = maxOf(0, totalW - rightW - hgap)
            leftPanel.setBounds(ins.left, ins.top, leftW, totalH)
            rightPanel.setBounds(ins.left + leftW + hgap, ins.top, rightW, totalH)
        }
        override fun getPreferredSize(): Dimension {
            val l = leftPanel.preferredSize
            val r = rightPanel.preferredSize
            return Dimension(l.width + hgap + r.width, maxOf(l.height, r.height))
        }
    }

    private val leftPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.X_AXIS)
        isOpaque = false
    }

    private val rightPanel = object : JPanel(null) {
        private val gap = JBUIScale.scale(4)
        init { isOpaque = false }
        private fun visibleItems() = listOf(stateBadge, priorityBadge, avatarLabel, commentCountLabel)
            .filter { it.isVisible }
        override fun doLayout() {
            val totalH = height
            var x = 0
            val visible = visibleItems()
            visible.forEachIndexed { i, c ->
                val cPref = c.preferredSize
                val cy = maxOf(0, (totalH - cPref.height) / 2)
                c.setBounds(x, cy, cPref.width, cPref.height)
                x += cPref.width + if (i < visible.size - 1) gap else 0
            }
        }
        override fun getPreferredSize(): Dimension {
            val visible = visibleItems()
            if (visible.isEmpty()) return Dimension(0, 0)
            val w = visible.sumOf { it.preferredSize.width } + (visible.size - 1) * gap
            val h = visible.maxOf { it.preferredSize.height }
            return Dimension(w, h)
        }
        override fun getMinimumSize() = preferredSize
        override fun getMaximumSize() = preferredSize
    }

    init {
        isOpaque = true
        border = JBUI.Borders.empty(6, 10, 6, 10)

        infoLabel.font = JBFont.small()
        infoLabel.border = JBUI.Borders.emptyLeft(JBUIScale.scale(20))

        leftPanel.add(typeDot)
        leftPanel.add(Box.createHorizontalStrut(JBUIScale.scale(6)))
        leftPanel.add(titleLabel)

        rightPanel.add(stateBadge)
        rightPanel.add(priorityBadge)
        rightPanel.add(avatarLabel)
        rightPanel.add(commentCountLabel)

        firstLinePanel.add(leftPanel)
        firstLinePanel.add(rightPanel)

        add(firstLinePanel, BorderLayout.CENTER)
        add(infoLabel, BorderLayout.SOUTH)
    }

    override fun getListCellRendererComponent(
        list: JList<out WorkItem>,
        value: WorkItem?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        if (value == null) return this

        // Colors
        background = if (isSelected) {
            UIUtil.getListSelectionBackground(list.hasFocus())
        } else {
            list.background
        }
        val primaryFg = if (isSelected) UIUtil.getListSelectionForeground(list.hasFocus()) else UIUtil.getListForeground()
        val secondaryFg = if (isSelected) UIUtil.getListSelectionForeground(list.hasFocus()) else JBColor.GRAY

        // Type dot
        typeDot.icon = TypeDotIcon(value.getTypeColor())

        // Title
        titleLabel.text = value.getTitle()
        titleLabel.foreground = primaryFg
        titleLabel.font = JBUI.Fonts.label().deriveFont(Font.PLAIN)

        // State badge
        val stateColor = value.getStateColor()
        stateBadge.isVisible = true
        stateBadge.text = value.getState().uppercase()
        stateBadge.setColors(stateColor, Color.WHITE)

        // Priority badge
        val priority = value.getPriority()
        val pColor = WorkItem.priorityColor(priority)
        if (priority != null && pColor != null) {
            priorityBadge.isVisible = true
            priorityBadge.text = "P$priority"
            priorityBadge.setColors(pColor, Color.WHITE)
        } else {
            priorityBadge.isVisible = false
        }

        // Avatar
        val imageUrl = value.getAssignedToImageUrl()
        if (imageUrl != null) {
            avatarLabel.isVisible = true
            avatarLabel.icon = avatarService.getAvatar(imageUrl, 20) { list.repaint() }
        } else {
            avatarLabel.isVisible = false
        }

        // Comment count
        val commentCount = value.getCommentCount()
        if (commentCount > 0) {
            commentCountLabel.isVisible = true
            commentCountLabel.icon = AllIcons.General.Balloon
            commentCountLabel.text = commentCount.toString()
            commentCountLabel.foreground = secondaryFg
            commentCountLabel.font = JBFont.small()
        } else {
            commentCountLabel.isVisible = false
        }

        // Info line (second row)
        val assignedTo = value.getAssignedTo() ?: "Unassigned"
        val dateStr = value.getRelativeDate()
        val info = buildString {
            append("${value.getWorkItemType()} #${value.id}")
            append(" · $assignedTo")
            if (dateStr.isNotBlank()) append(" · $dateStr")
            value.getIterationPath()?.let { iter ->
                val shortIter = iter.substringAfterLast("\\")
                if (shortIter.isNotBlank()) append(" · $shortIter")
            }
        }
        infoLabel.text = info
        infoLabel.foreground = secondaryFg

        return this
    }
}

/**
 * Colored circle icon for work item type indicator.
 */
private class TypeDotIcon(private val color: Color) : Icon {
    private val size = JBUIScale.scale(8)

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = color
        val insetY = (c?.height ?: size) / 2 - size / 2
        g2.fill(Ellipse2D.Double(x.toDouble(), (y + insetY - (c?.height ?: 0) / 2 + size).toDouble(), size.toDouble(), size.toDouble()))
        g2.dispose()
    }

    override fun getIconWidth(): Int = size
    override fun getIconHeight(): Int = size
}

/**
 * Rounded colored badge label for state/priority.
 */
private class BadgeLabel : JLabel() {
    private var bgColor: Color = JBColor.LIGHT_GRAY
    private val arcSize = JBUIScale.scale(6)

    init {
        isOpaque = false
        font = JBFont.small().deriveFont(Font.BOLD)
        border = JBUI.Borders.empty(1, 6, 1, 6)
        horizontalAlignment = CENTER
    }

    fun setColors(background: Color, foreground: Color) {
        this.bgColor = background
        this.foreground = foreground
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = bgColor
        g2.fillRoundRect(0, 0, width, height, arcSize, arcSize)
        g2.dispose()
        super.paintComponent(g)
    }
}
