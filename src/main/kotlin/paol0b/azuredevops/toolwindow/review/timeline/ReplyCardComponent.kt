package paol0b.azuredevops.toolwindow.review.timeline

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.services.AvatarService
import java.awt.*
import javax.swing.*

/**
 * A small indented card showing a reply inside a comment thread.
 *
 * Layout:
 * │  [avatar] Author · time ago
 * │  Reply content…
 */
class ReplyCardComponent(
    project: Project,
    private val reply: TimelineReply
) : JPanel() {

    private val avatarService = AvatarService.getInstance(project)

    private val replyBg = JBColor(Color(250, 251, 253), Color(45, 47, 51))
    private val leftBarColor = JBColor(Color(180, 195, 215), Color(80, 90, 105))

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = BorderFactory.createCompoundBorder(
            // Left accent bar
            BorderFactory.createMatteBorder(0, 3, 0, 0, leftBarColor),
            JBUI.Borders.empty(6, 10, 6, 8)
        )
        background = replyBg

        buildUI()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.color = replyBg
        g2.fillRect(0, 0, width, height)
        g2.dispose()
        super.paintComponent(g)
    }

    private fun buildUI() {
        // Header: avatar + name + time
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 24)
        }

        val avatarIcon = avatarService.getAvatar(reply.authorImageUrl, 20) { repaint() }
        header.add(JBLabel(avatarIcon))
        header.add(JBLabel(reply.author).apply {
            font = font.deriveFont(Font.BOLD, 11f)
        })

        val ts = TimelineUtils.formatTimeAgo(reply.timestamp)
        if (ts.isNotEmpty()) {
            header.add(JBLabel("· $ts").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(10f)
            })
        }
        add(header)

        // Content
        add(Box.createVerticalStrut(2))
        add(JBLabel("<html><div style='width:460px'>${TimelineUtils.escapeHtml(reply.content)}</div></html>").apply {
            font = UIUtil.getLabelFont().deriveFont(11.5f)
            alignmentX = Component.LEFT_ALIGNMENT
        })
    }
}
