package paol0b.azuredevops.toolwindow.review.timeline

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.model.ThreadStatus
import paol0b.azuredevops.services.AvatarService
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * Premium comment thread card for the PR timeline.
 *
 * Uses [ElevatedCard] for a floating card effect with subtle shadow.
 * Integrates with the [TimelineRow] connector for a GitHub-style vertical timeline.
 */
class CommentCardComponent(
    private val project: Project,
    private val entry: TimelineEntry,
    private val pullRequest: PullRequest,
    private val onReply: (threadId: Int, content: String) -> Unit,
    private val onStatusChange: (threadId: Int, newStatus: ThreadStatus) -> Unit
) : JPanel() {

    private val avatarService = AvatarService.getInstance(project)
    private var repliesVisible = true

    init {
        layout = BorderLayout()
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        buildUI()
    }

    private fun buildUI() {
        val card = ElevatedCard()
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
        card.border = JBUI.Borders.empty(14, 16)

        // ── Header ──
        card.add(createHeaderRow())
        card.add(Box.createVerticalStrut(10))

        // ── Body ──
        card.add(createContentLabel(entry.content))

        // ── File path ──
        if (!entry.filePath.isNullOrBlank()) {
            card.add(Box.createVerticalStrut(8))
            card.add(createFilePathChip(entry.filePath!!))
        }

        // ── Diff preview ──
        if (!entry.filePath.isNullOrBlank() && entry.lineStart != null) {
            card.add(Box.createVerticalStrut(6))
            card.add(FileDiffPreviewComponent(
                project = project,
                pullRequest = pullRequest,
                filePath = entry.filePath!!,
                lineStart = entry.lineStart,
                lineEnd = entry.lineEnd ?: entry.lineStart,
                isLeftSide = entry.isLeftSide
            ))
        }

        // ── Replies section ──
        val replies = entry.replies
        if (replies.isNotEmpty()) {
            card.add(Box.createVerticalStrut(12))
            // Thin separator
            card.add(createSeparator())
            card.add(Box.createVerticalStrut(8))

            val toggle = createReplyToggle(replies.size)
            card.add(toggle)

            val repliesContainer = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                alignmentX = Component.LEFT_ALIGNMENT
                border = JBUI.Borders.emptyLeft(16)
                name = "replies-container"
            }
            for (reply in replies) {
                repliesContainer.add(Box.createVerticalStrut(6))
                repliesContainer.add(ReplyCardComponent(project, reply))
            }
            card.add(repliesContainer)
        }

        // ── Reply input ──
        if (entry.threadId != null) {
            card.add(Box.createVerticalStrut(12))
            card.add(createSeparator())
            card.add(Box.createVerticalStrut(10))
            card.add(createReplyArea(entry.threadId!!))
        }

        add(card, BorderLayout.CENTER)
    }

    private fun createSeparator(): JComponent = JSeparator().apply {
        alignmentX = Component.LEFT_ALIGNMENT
        maximumSize = Dimension(Int.MAX_VALUE, 1)
        foreground = TimelineTheme.CARD_BORDER
    }

    // ── Header ──────────────────────────────────────────────────────

    private fun createHeaderRow(): JPanel {
        val row = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 30)
        }

        val left = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply { isOpaque = false }
        val avatarIcon = avatarService.getAvatar(entry.authorImageUrl, TimelineTheme.AVATAR_SIZE) { repaint() }
        left.add(JBLabel(avatarIcon))
        left.add(JBLabel(entry.author).apply {
            font = font.deriveFont(Font.BOLD, 13f)
            foreground = TimelineTheme.PRIMARY_FG
        })
        val ts = TimelineUtils.formatTimeAgo(entry.timestamp)
        if (ts.isNotEmpty()) {
            left.add(JBLabel(ts).apply {
                foreground = TimelineTheme.MUTED_FG
                font = font.deriveFont(11f)
            })
        }
        row.add(left, BorderLayout.WEST)

        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 6, 0)).apply { isOpaque = false }
        if (entry.threadStatus != null && entry.threadStatus != ThreadStatus.Unknown) {
            right.add(StatusPill(entry.threadStatus!!))
        }
        if (entry.threadId != null) {
            right.add(createOverflowButton())
        }
        row.add(right, BorderLayout.EAST)

        return row
    }

    // ── Content ─────────────────────────────────────────────────────

    private fun createContentLabel(text: String): JComponent {
        return JBLabel("<html><div style='width:500px;line-height:1.5'>${TimelineUtils.escapeHtml(text)}</div></html>").apply {
            font = UIUtil.getLabelFont().deriveFont(12.5f)
            foreground = TimelineTheme.PRIMARY_FG
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    private fun createFilePathChip(path: String): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(JBLabel(AllIcons.FileTypes.Any_type))
            add(JBLabel(path).apply {
                foreground = TimelineTheme.LINK_FG
                font = font.deriveFont(11f)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            })
        }
    }

    // ── Reply toggle ────────────────────────────────────────────────

    private fun createReplyToggle(count: Int): JComponent {
        val text = "$count ${if (count == 1) "reply" else "replies"}"
        return JBLabel(text).apply {
            icon = AllIcons.General.Balloon
            foreground = TimelineTheme.LINK_FG
            font = font.deriveFont(Font.BOLD, 11f)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            alignmentX = Component.LEFT_ALIGNMENT
            addMouseListener(object : java.awt.event.MouseAdapter() {
                override fun mouseClicked(e: java.awt.event.MouseEvent) {
                    repliesVisible = !repliesVisible
                    val card = this@CommentCardComponent.getComponent(0) as? JPanel ?: return
                    for (comp in card.components) {
                        if (comp is JPanel && comp.name == "replies-container") {
                            comp.isVisible = repliesVisible
                        }
                    }
                    revalidate()
                    repaint()
                }
            })
        }
    }

    // ── Overflow menu ───────────────────────────────────────────────

    private fun createOverflowButton(): JComponent {
        return JButton(AllIcons.Actions.More).apply {
            preferredSize = Dimension(24, 24)
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = "Actions"
            addActionListener {
                val popup = TimelineDropdownMenu.createThreadPopup(
                    threadId = entry.threadId!!,
                    currentStatus = entry.threadStatus ?: ThreadStatus.Active,
                    onStatusChange = { newStatus -> onStatusChange(entry.threadId!!, newStatus) }
                )
                popup.show(this, 0, height)
            }
        }
    }

    // ── Reply area ──────────────────────────────────────────────────

    private fun createReplyArea(threadId: Int): JComponent {
        val panel = JPanel(BorderLayout(8, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 36)
        }

        val field = JTextField().apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(TimelineTheme.CARD_BORDER, 1, true),
                JBUI.Borders.empty(6, 10)
            )
            font = UIUtil.getLabelFont().deriveFont(12f)
            putClientProperty("JTextField.placeholderText", "Write a reply\u2026")
        }

        val sendBtn = object : JButton("Reply") {
            init {
                font = getFont().deriveFont(Font.BOLD, 11f)
                preferredSize = Dimension(72, 30)
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                isOpaque = false
                isBorderPainted = false
                isContentAreaFilled = false
                isFocusPainted = false
            }
            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                // Background
                val bgColor = if (model.isRollover) TimelineTheme.LINK_FG.brighter() else TimelineTheme.LINK_FG
                g2.color = bgColor
                g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), 8f, 8f))
                // Text (white, centered)
                g2.color = Color.WHITE
                g2.font = font
                val fm = g2.fontMetrics
                val textX = (width - fm.stringWidth(text)) / 2
                val textY = (height + fm.ascent - fm.descent) / 2
                g2.drawString(text, textX, textY)
                g2.dispose()
            }
        }
        sendBtn.addActionListener {
            val txt = field.text.trim()
            if (txt.isNotEmpty()) {
                onReply(threadId, txt)
                field.text = ""
            }
        }
        field.addActionListener { sendBtn.doClick() }

        panel.add(field, BorderLayout.CENTER)
        panel.add(sendBtn, BorderLayout.EAST)
        return panel
    }
}

/**
 * Rounded pill badge for thread status (Active, Fixed, Closed, etc.)
 */
class StatusPill(status: ThreadStatus) : JBLabel(status.getDisplayName()) {

    private val bg: Color
    private val pillFg: Color

    init {
        val (b, f) = when (status) {
            ThreadStatus.Active, ThreadStatus.Pending ->
                TimelineTheme.ACTIVE_BG to TimelineTheme.ACTIVE_FG
            ThreadStatus.Fixed, ThreadStatus.Closed, ThreadStatus.ByDesign, ThreadStatus.WontFix ->
                TimelineTheme.RESOLVED_BG to TimelineTheme.RESOLVED_FG
            else ->
                TimelineTheme.NOVOTE_BG to TimelineTheme.NOVOTE_FG
        }
        bg = b
        pillFg = f
        foreground = pillFg
        border = JBUI.Borders.empty(3, 10)
        font = getFont().deriveFont(Font.BOLD, 10f)
        isOpaque = false
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = bg
        g2.fill(RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), height.toFloat(), height.toFloat()))
        g2.dispose()
        super.paintComponent(g)
    }
}

/**
 * Legacy alias — kept for any external references.
 */
class RoundedPanel(
    private val cornerRadius: Int,
    private val fillColor: Color,
    private val borderColor: Color
) : JPanel() {
    init { isOpaque = false }
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

// Remove old ModernCard — replaced by ElevatedCard
