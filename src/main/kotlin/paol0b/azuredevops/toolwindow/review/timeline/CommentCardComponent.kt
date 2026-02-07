package paol0b.azuredevops.toolwindow.review.timeline

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.ThreadStatus
import paol0b.azuredevops.services.AvatarService
import java.awt.*
import javax.swing.*

/**
 * A card component that renders a single comment thread in the PR timeline.
 *
 * Layout:
 * ┌────────────────────────────────────────────────┐
 * │ [avatar]  Author  · timestamp        [status] ⋮│
 * │ Comment body text…                              │
 * │ 📎 filename.kt                                  │
 * │ 💬 2 replies                                    │
 * ├────────────────────────────────────────────────┤
 * │   ↳ [avatar] Reply Author · time               │
 * │     Reply body text…                            │
 * │   ↳ …                                          │
 * ├────────────────────────────────────────────────┤
 * │ [reply text area]                     [Reply]   │
 * └────────────────────────────────────────────────┘
 */
class CommentCardComponent(
    private val project: Project,
    private val entry: TimelineEntry,
    private val onReply: (threadId: Int, content: String) -> Unit,
    private val onStatusChange: (threadId: Int, newStatus: ThreadStatus) -> Unit
) : JPanel() {

    private val avatarService = AvatarService.getInstance(project)

    // Card colours – subtle background to separate cards from the panel background
    private val cardBg = JBColor(Color(245, 247, 250), Color(50, 52, 56))
    private val cardBorder = JBColor(Color(218, 222, 228), Color(65, 68, 74))
    private val activeBadgeBg = JBColor(Color(220, 240, 255), Color(35, 55, 75))
    private val resolvedBadgeBg = JBColor(Color(220, 255, 220), Color(35, 70, 45))

    private var repliesVisible = true

    init {
        layout = BorderLayout()
        isOpaque = false
        border = JBUI.Borders.empty(4, 0)
        alignmentX = Component.LEFT_ALIGNMENT
        buildUI()
    }

    private fun buildUI() {
        val card = RoundedPanel(10, cardBg, cardBorder)
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
        card.border = JBUI.Borders.empty(10, 12, 10, 12)

        // ── Header row: avatar + author + time + status badge + ⋮ button ──
        card.add(createHeaderRow())

        // ── Body: comment content ──
        card.add(Box.createVerticalStrut(6))
        card.add(createContentLabel(entry.content))

        // ── File path badge ──
        if (!entry.filePath.isNullOrBlank()) {
            card.add(Box.createVerticalStrut(4))
            card.add(createFilePathLabel(entry.filePath!!))
        }

        // ── Reply count toggle ──
        val replies = entry.replies
        if (replies.isNotEmpty()) {
            card.add(Box.createVerticalStrut(6))
            val toggle = createReplyToggle(replies.size)
            card.add(toggle)
        }

        // ── Nested replies ──
        val repliesContainer = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyLeft(28) // indent under the avatar
        }
        for (reply in replies) {
            repliesContainer.add(ReplyCardComponent(project, reply))
        }
        card.add(repliesContainer)

        // ── Inline reply area ──
        if (entry.threadId != null) {
            card.add(Box.createVerticalStrut(8))
            card.add(createReplyArea(entry.threadId!!))
        }

        add(card, BorderLayout.CENTER)
    }

    // ----------------------------------------------------------------
    //  Header row
    // ----------------------------------------------------------------

    private fun createHeaderRow(): JPanel {
        val row = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 32)
        }

        // Left: avatar + name + time
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }
        val avatarIcon = avatarService.getAvatar(entry.authorImageUrl, 24) { repaint() }
        left.add(JBLabel(avatarIcon))
        left.add(JBLabel(entry.author).apply {
            font = font.deriveFont(Font.BOLD, 12f)
        })
        val ts = TimelineUtils.formatTimeAgo(entry.timestamp)
        if (ts.isNotEmpty()) {
            left.add(JBLabel("· $ts").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(11f)
            })
        }
        row.add(left, BorderLayout.WEST)

        // Right: status badge + ⋮ menu
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        if (entry.threadStatus != null && entry.threadStatus != ThreadStatus.Unknown) {
            right.add(createStatusBadge(entry.threadStatus!!))
        }
        if (entry.threadId != null) {
            right.add(createOverflowMenuButton())
        }
        row.add(right, BorderLayout.EAST)

        return row
    }

    // ----------------------------------------------------------------
    //  Content
    // ----------------------------------------------------------------

    private fun createContentLabel(text: String): JComponent {
        return JBLabel("<html><div style='width:500px'>${TimelineUtils.escapeHtml(text)}</div></html>").apply {
            font = UIUtil.getLabelFont().deriveFont(12f)
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    private fun createFilePathLabel(path: String): JComponent {
        return JBLabel(path).apply {
            icon = AllIcons.FileTypes.Any_type
            foreground = JBColor(Color(70, 130, 180), Color(100, 149, 237))
            font = font.deriveFont(11f)
            alignmentX = Component.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
    }

    // ----------------------------------------------------------------
    //  Reply toggle
    // ----------------------------------------------------------------

    private fun createReplyToggle(count: Int): JComponent {
        val label = JBLabel("💬 $count ${if (count == 1) "reply" else "replies"}").apply {
            foreground = JBColor(Color(70, 130, 180), Color(100, 149, 237))
            font = font.deriveFont(Font.BOLD, 11f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        label.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                repliesVisible = !repliesVisible
                // Toggle the replies container visibility
                (parent as? JPanel)?.components?.filterIsInstance<JPanel>()?.forEach { child ->
                    if (child.border == JBUI.Borders.emptyLeft(28)) {
                        child.isVisible = repliesVisible
                    }
                }
                revalidate()
                repaint()
            }
        })
        return label
    }

    // ----------------------------------------------------------------
    //  Status badge
    // ----------------------------------------------------------------

    private fun createStatusBadge(status: ThreadStatus): JComponent {
        val (text, bg, fg) = when (status) {
            ThreadStatus.Active, ThreadStatus.Pending -> Triple(
                status.getDisplayName(),
                activeBadgeBg,
                JBColor(Color(0, 100, 180), Color(80, 180, 255))
            )
            ThreadStatus.Fixed, ThreadStatus.Closed, ThreadStatus.ByDesign, ThreadStatus.WontFix -> Triple(
                status.getDisplayName(),
                resolvedBadgeBg,
                JBColor(Color(34, 139, 34), Color(50, 200, 50))
            )
            else -> Triple(
                status.getDisplayName(),
                UIUtil.getPanelBackground(),
                JBColor.GRAY
            )
        }
        return JBLabel(text).apply {
            isOpaque = true
            background = bg
            foreground = fg
            border = JBUI.Borders.empty(2, 8, 2, 8)
            font = font.deriveFont(Font.BOLD, 10f)
        }
    }

    // ----------------------------------------------------------------
    //  Overflow (⋮) menu button
    // ----------------------------------------------------------------

    private fun createOverflowMenuButton(): JComponent {
        val btn = JButton("⋮").apply {
            preferredSize = Dimension(28, 24)
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = font.deriveFont(Font.BOLD, 16f)
            toolTipText = "Actions"
        }
        btn.addActionListener { e ->
            val popup = TimelineDropdownMenu.createThreadPopup(
                threadId = entry.threadId!!,
                currentStatus = entry.threadStatus ?: ThreadStatus.Active,
                onStatusChange = { newStatus -> onStatusChange(entry.threadId!!, newStatus) }
            )
            popup.show(btn, 0, btn.height)
        }
        return btn
    }

    // ----------------------------------------------------------------
    //  Reply area
    // ----------------------------------------------------------------

    private fun createReplyArea(threadId: Int): JComponent {
        val panel = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 36)
            border = JBUI.Borders.emptyTop(2)
        }

        val field = javax.swing.JTextField().apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(cardBorder),
                JBUI.Borders.empty(4, 8)
            )
            font = UIUtil.getLabelFont().deriveFont(12f)
            toolTipText = "Write a reply…"
        }
        // Placeholder
        field.text = ""
        field.putClientProperty("JTextField.placeholderText", "Write a reply…")

        val sendBtn = JButton("Reply").apply {
            font = font.deriveFont(11f)
            preferredSize = Dimension(70, 28)
        }
        sendBtn.addActionListener {
            val txt = field.text.trim()
            if (txt.isNotEmpty()) {
                onReply(threadId, txt)
                field.text = ""
            }
        }
        // Allow Enter to send
        field.addActionListener {
            sendBtn.doClick()
        }

        panel.add(field, BorderLayout.CENTER)
        panel.add(sendBtn, BorderLayout.EAST)
        return panel
    }
}

/**
 * A simple JPanel with rounded corners and a thin border.
 */
class RoundedPanel(
    private val cornerRadius: Int,
    private val fillColor: Color,
    private val borderColor: Color
) : JPanel() {

    init {
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = fillColor
        g2.fillRoundRect(0, 0, width, height, cornerRadius, cornerRadius)
        g2.color = borderColor
        g2.drawRoundRect(0, 0, width - 1, height - 1, cornerRadius, cornerRadius)
        g2.dispose()
    }
}
