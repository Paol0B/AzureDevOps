package paol0b.azuredevops.toolwindow.review.timeline

import com.intellij.openapi.project.Project
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.services.AvatarService
import java.awt.*
import java.awt.geom.RoundRectangle2D
import javax.swing.*

/**
 * Compact reply bubble with a left accent bar and rounded background.
 */
class ReplyCardComponent(
    project: Project,
    private val reply: TimelineReply
) : JPanel() {

    private val avatarService = AvatarService.getInstance(project)

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT
        border = JBUI.Borders.empty(0, 0)
        buildUI()
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g.create() as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

        val arc = JBUI.scale(8).toFloat()
        val shape = RoundRectangle2D.Float(0f, 0f, width.toFloat(), height.toFloat(), arc, arc)

        // Background
        g2.color = TimelineTheme.REPLY_BG
        g2.fill(shape)

        // Left accent bar (blue, 3px, rounded)
        g2.color = TimelineTheme.REPLY_BAR
        g2.fill(RoundRectangle2D.Float(0f, 0f, JBUI.scale(3).toFloat(), height.toFloat(), 6f, 6f))

        g2.dispose()
        super.paintComponent(g)
    }

    private fun buildUI() {
        border = JBUI.Borders.empty(8, 14, 8, 12)

        val header = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 22)
        }

        val avatarIcon = avatarService.getAvatar(reply.authorImageUrl, TimelineTheme.AVATAR_SMALL) { repaint() }
        header.add(JBLabel(avatarIcon))
        header.add(JBLabel(reply.author).apply {
            font = font.deriveFont(Font.BOLD, 11.5f)
            foreground = TimelineTheme.PRIMARY_FG
        })

        val ts = TimelineUtils.formatTimeAgo(reply.timestamp)
        if (ts.isNotEmpty()) {
            header.add(JBLabel(ts).apply {
                foreground = TimelineTheme.MUTED_FG
                font = font.deriveFont(10f)
            })
        }
        add(header)

        add(Box.createVerticalStrut(4))
        add(JBLabel("<html><div style='width:420px;line-height:1.4'>${TimelineUtils.escapeHtml(reply.content)}</div></html>").apply {
            font = UIUtil.getLabelFont().deriveFont(11.5f)
            foreground = TimelineTheme.PRIMARY_FG
            alignmentX = Component.LEFT_ALIGNMENT
        })
    }
}
