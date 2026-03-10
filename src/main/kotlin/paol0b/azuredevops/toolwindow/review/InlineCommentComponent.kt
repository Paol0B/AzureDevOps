package paol0b.azuredevops.toolwindow.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.Comment
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.ThreadStatus
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.toolwindow.review.timeline.RoundedPanel
import paol0b.azuredevops.toolwindow.review.timeline.TimelineDropdownMenu
import paol0b.azuredevops.toolwindow.review.timeline.TimelineUtils
import java.awt.*
import javax.swing.*

/**
 * GitHub-style inline comment component for the diff viewer.
 *
 * Renders a compact, embedded comment thread card below the commented line.
 * Mirrors the GitHub JetBrains plugin inline review comment UX:
 *
 * ┌─────────────────────────────────────────────────────┐
 * │ [▼] Author · time ago               [status] [⋮]   │
 * │ Comment body text…                                   │
 * ├─────────────────────────────────────────────────────┤
 * │   ┃ Reply Author · time ago                          │
 * │   ┃ Reply body text…                                 │
 * ├─────────────────────────────────────────────────────┤
 * │ [reply text field]                        [Reply]    │
 * └─────────────────────────────────────────────────────┘
 */
class InlineCommentComponent(
    private val thread: CommentThread,
    private val apiClient: AzureDevOpsApiClient,
    private val pullRequestId: Int,
    private val projectName: String?,
    private val repositoryId: String?,
    private val onStatusChanged: () -> Unit = {},
    private val onReplyAdded: () -> Unit = {}
) : JPanel() {

    private val logger = Logger.getInstance(InlineCommentComponent::class.java)

    private var isCollapsed = false

    // GitHub-style colors
    private val cardBg = JBColor(Color(245, 247, 250), Color(50, 52, 56))
    private val cardBorder = JBColor(Color(208, 215, 222), Color(60, 63, 68))
    private val activeBadgeBg = JBColor(Color(220, 240, 255), Color(35, 55, 75))
    private val activeBadgeFg = JBColor(Color(0, 100, 180), Color(80, 180, 255))
    private val resolvedBadgeBg = JBColor(Color(220, 255, 220), Color(35, 70, 45))
    private val resolvedBadgeFg = JBColor(Color(34, 139, 34), Color(50, 200, 50))
    private val replyBarColor = JBColor(Color(180, 195, 215), Color(80, 90, 105))

    init {
        layout = BorderLayout()
        isOpaque = false
        border = JBUI.Borders.empty(4, 0, 4, 0)
        buildUI()
    }

    private fun buildUI() {
        removeAll()

        val card = RoundedPanel(8, cardBg, cardBorder)
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
        card.border = JBUI.Borders.empty(8, 10, 8, 10)

        // ── Header: collapse toggle + author + time + status badge + ⋮ ──
        card.add(createHeaderRow())

        // ── Root comment body ──
        val rootComment = thread.comments?.firstOrNull { it.commentType != "system" }
        if (rootComment != null && !isCollapsed) {
            card.add(Box.createVerticalStrut(4))
            card.add(createContentLabel(rootComment.content ?: ""))
        }

        // ── Replies ──
        if (!isCollapsed) {
            val replies = thread.comments
                ?.filter { it.commentType != "system" }
                ?.drop(1)
                ?: emptyList()

            if (replies.isNotEmpty()) {
                card.add(Box.createVerticalStrut(6))
                card.add(JSeparator().apply {
                    maximumSize = Dimension(Int.MAX_VALUE, 1)
                    alignmentX = Component.LEFT_ALIGNMENT
                })

                val repliesPanel = JPanel().apply {
                    layout = BoxLayout(this, BoxLayout.Y_AXIS)
                    isOpaque = false
                    alignmentX = Component.LEFT_ALIGNMENT
                    border = JBUI.Borders.emptyLeft(20)
                }
                for (reply in replies) {
                    repliesPanel.add(createReplyCard(reply))
                }
                card.add(repliesPanel)
            }

            // ── Inline reply area ──
            card.add(Box.createVerticalStrut(6))
            card.add(createInlineReplyArea())
        }

        add(card, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    // ────────────────────────────────────────────────────────
    //  Header row
    // ────────────────────────────────────────────────────────

    private fun createHeaderRow(): JPanel {
        val row = JPanel(BorderLayout(4, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 28)
        }

        // Left: collapse chevron + author + time
        val left = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }

        val chevronIcon = if (isCollapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
        val chevronBtn = JButton(chevronIcon).apply {
            preferredSize = Dimension(20, 20)
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            toolTipText = if (isCollapsed) "Expand" else "Collapse"
            addActionListener {
                isCollapsed = !isCollapsed
                buildUI()
            }
        }
        left.add(chevronBtn)

        val firstComment = thread.comments?.firstOrNull { it.commentType != "system" }
        val authorName = firstComment?.author?.displayName ?: "Unknown"
        left.add(JBLabel(authorName).apply {
            font = font.deriveFont(Font.BOLD, 12f)
        })

        val timeAgo = TimelineUtils.formatTimeAgo(firstComment?.publishedDate)
        if (timeAgo.isNotEmpty()) {
            left.add(JBLabel("· $timeAgo").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(11f)
            })
        }

        // Reply count
        val replyCount = (thread.comments?.filter { it.commentType != "system" }?.size ?: 1) - 1
        if (replyCount > 0) {
            left.add(JBLabel("· $replyCount ${if (replyCount == 1) "reply" else "replies"}").apply {
                foreground = JBColor(Color(70, 130, 180), Color(100, 149, 237))
                font = font.deriveFont(11f)
            })
        }

        row.add(left, BorderLayout.WEST)

        // Right: status badge + overflow menu
        val right = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }

        val status = thread.status
        if (status != null && status != ThreadStatus.Unknown) {
            right.add(createStatusBadge(status))
        }

        val menuBtn = JButton("⋮").apply {
            preferredSize = Dimension(24, 20)
            isBorderPainted = false
            isContentAreaFilled = false
            isFocusPainted = false
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            font = font.deriveFont(Font.BOLD, 14f)
            toolTipText = "Actions"
            addActionListener {
                val threadId = thread.id ?: return@addActionListener
                val popup = TimelineDropdownMenu.createThreadPopup(
                    threadId = threadId,
                    currentStatus = thread.status ?: ThreadStatus.Active,
                    onStatusChange = { newStatus -> updateThreadStatus(newStatus) }
                )
                popup.show(this, 0, height)
            }
        }
        right.add(menuBtn)

        row.add(right, BorderLayout.EAST)
        return row
    }

    // ────────────────────────────────────────────────────────
    //  Content
    // ────────────────────────────────────────────────────────

    private fun createContentLabel(text: String): JComponent {
        return JBLabel("<html><div style='width:400px'>${TimelineUtils.escapeHtml(text)}</div></html>").apply {
            font = UIUtil.getLabelFont().deriveFont(12f)
            alignmentX = Component.LEFT_ALIGNMENT
        }
    }

    // ────────────────────────────────────────────────────────
    //  Reply card (nested comment)
    // ────────────────────────────────────────────────────────

    private fun createReplyCard(comment: Comment): JPanel {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 3, 0, 0, replyBarColor),
                JBUI.Borders.empty(4, 8, 4, 0)
            )
        }

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 22)
        }
        header.add(JBLabel(comment.author?.displayName ?: "Unknown").apply {
            font = font.deriveFont(Font.BOLD, 11f)
        })
        val ts = TimelineUtils.formatTimeAgo(comment.publishedDate)
        if (ts.isNotEmpty()) {
            header.add(JBLabel("· $ts").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(10f)
            })
        }
        panel.add(header)
        panel.add(Box.createVerticalStrut(2))
        panel.add(JBLabel("<html><div style='width:380px'>${TimelineUtils.escapeHtml(comment.content ?: "")}</div></html>").apply {
            font = UIUtil.getLabelFont().deriveFont(11.5f)
            alignmentX = Component.LEFT_ALIGNMENT
        })

        return panel
    }

    // ────────────────────────────────────────────────────────
    //  Inline reply area
    // ────────────────────────────────────────────────────────

    private fun createInlineReplyArea(): JComponent {
        val panel = JPanel(BorderLayout(6, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 34)
            border = JBUI.Borders.emptyTop(2)
        }

        val field = JTextField().apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(cardBorder),
                JBUI.Borders.empty(4, 8)
            )
            font = UIUtil.getLabelFont().deriveFont(12f)
            putClientProperty("JTextField.placeholderText", "Reply…")
        }

        val sendBtn = JButton("Reply").apply {
            font = font.deriveFont(11f)
            preferredSize = Dimension(70, 28)
        }

        sendBtn.addActionListener { submitReply(field, sendBtn) }
        field.addActionListener { submitReply(field, sendBtn) }

        panel.add(field, BorderLayout.CENTER)
        panel.add(sendBtn, BorderLayout.EAST)
        return panel
    }

    private fun submitReply(field: JTextField, sendBtn: JButton) {
        val text = field.text.trim()
        if (text.isEmpty()) return
        val threadId = thread.id ?: return

        sendBtn.isEnabled = false
        sendBtn.text = "…"

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                apiClient.addCommentToThread(pullRequestId, threadId, text, projectName, repositoryId)
                logger.info("Reply added to thread #$threadId")
                ApplicationManager.getApplication().invokeLater {
                    field.text = ""
                    sendBtn.isEnabled = true
                    sendBtn.text = "Reply"
                    onReplyAdded()
                }
            } catch (e: Exception) {
                logger.error("Failed to add reply", e)
                ApplicationManager.getApplication().invokeLater {
                    sendBtn.isEnabled = true
                    sendBtn.text = "Reply"
                }
            }
        }
    }

    // ────────────────────────────────────────────────────────
    //  Status badge
    // ────────────────────────────────────────────────────────

    private fun createStatusBadge(status: ThreadStatus): JComponent {
        val isActive = status == ThreadStatus.Active || status == ThreadStatus.Pending
        val bg = if (isActive) activeBadgeBg else resolvedBadgeBg
        val fg = if (isActive) activeBadgeFg else resolvedBadgeFg

        return JBLabel(status.getDisplayName()).apply {
            isOpaque = true
            background = bg
            foreground = fg
            border = JBUI.Borders.empty(2, 6, 2, 6)
            font = font.deriveFont(Font.BOLD, 10f)
        }
    }

    // ────────────────────────────────────────────────────────
    //  Status change
    // ────────────────────────────────────────────────────────

    private fun updateThreadStatus(newStatus: ThreadStatus) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val threadId = thread.id ?: return@executeOnPooledThread
                apiClient.updateThreadStatus(pullRequestId, threadId, newStatus, projectName, repositoryId)
                logger.info("Thread #$threadId status updated to $newStatus")
                ApplicationManager.getApplication().invokeLater { onStatusChanged() }
            } catch (e: Exception) {
                logger.error("Failed to update thread status", e)
            }
        }
    }

    // ────────────────────────────────────────────────────────
    //  Public helpers
    // ────────────────────────────────────────────────────────

    fun getLineNumber(): Int? {
        val ctx = thread.pullRequestThreadContext ?: thread.threadContext
        return ctx?.rightFileStart?.line ?: ctx?.leftFileStart?.line
    }

    fun isOnLeftSide(): Boolean {
        val ctx = thread.pullRequestThreadContext ?: thread.threadContext
        return ctx?.leftFileStart != null && ctx.rightFileStart == null
    }

    fun getFilePath(): String? = thread.getFilePath()
}
