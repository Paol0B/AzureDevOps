package paol0b.azuredevops.toolwindow.workitem.board

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.WorkItem
import paol0b.azuredevops.services.AvatarService
import paol0b.azuredevops.toolwindow.workitem.WorkItemToolWindowFactory
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Individual card on the Kanban board representing a single work item.
 * Supports drag (via parent column's DragSource) and double-click to open detail.
 */
class BoardCardComponent(
    private val project: Project,
    val workItem: WorkItem
) : JPanel() {

    init {
        layout = BorderLayout()
        isOpaque = true
        background = UIUtil.getPanelBackground()

        val typeColor = workItem.getTypeColor()
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, JBUI.scale(3), 0, 0, typeColor),
            BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(8, 10, 8, 8)
            )
        )
        maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(80))
        preferredSize = Dimension(JBUI.scale(230), JBUI.scale(72))

        cursor = Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR)

        // Top: type icon + title
        val topPanel = JPanel(BorderLayout()).apply {
            isOpaque = false

            val typeIcon = getTypeIcon(workItem.getWorkItemType())
            val iconLabel = JBLabel(typeIcon)
            add(iconLabel, BorderLayout.WEST)

            val titleLabel = JBLabel().apply {
                text = truncate(workItem.getTitle(), 40)
                toolTipText = workItem.getTitle()
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 11f)
                border = JBUI.Borders.empty(0, 6, 0, 0)
            }
            add(titleLabel, BorderLayout.CENTER)
        }
        add(topPanel, BorderLayout.NORTH)

        // Bottom: ID + assigned to + priority
        val bottomPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 0, 0, 0)

            // ID badge
            add(JBLabel("#${workItem.id}").apply {
                foreground = workItem.getTypeColor()
                font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 10f)
            })

            // Assigned to avatar
            val avatarService = AvatarService.getInstance(project)
            workItem.getAssignedToImageUrl()?.let { url ->
                val avatarLabel = JBLabel()
                avatarService.getAvatar(url, 16) {
                    avatarLabel.icon = avatarService.getAvatar(url, 16)
                }.let { avatarLabel.icon = it }
                add(avatarLabel)
            }

            // Assigned to name (abbreviated)
            workItem.getAssignedTo()?.let { name ->
                add(JBLabel(abbreviateName(name)).apply {
                    foreground = JBColor.GRAY
                    font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 10f)
                })
            }

            // Priority
            workItem.getPriority()?.let { priority ->
                WorkItem.priorityColor(priority)?.let { pColor ->
                    add(JBLabel("P$priority").apply {
                        foreground = pColor
                        font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 9f)
                    })
                }
            }
        }
        add(bottomPanel, BorderLayout.SOUTH)

        // Double-click to open detail
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    WorkItemToolWindowFactory.openWorkItemDetailTab(project, workItem)
                }
            }
        })
    }

    private fun getTypeIcon(type: String): Icon = WorkItem.typeIcon(type)

    private fun truncate(text: String, maxLen: Int): String {
        return if (text.length > maxLen) text.take(maxLen - 1) + "..." else text
    }

    private fun abbreviateName(name: String): String {
        val parts = name.trim().split(" ")
        return if (parts.size >= 2) {
            "${parts.first()} ${parts.last().first()}."
        } else {
            name.take(12)
        }
    }
}
