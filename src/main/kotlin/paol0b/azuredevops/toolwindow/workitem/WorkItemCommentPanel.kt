package paol0b.azuredevops.toolwindow.workitem

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.WorkItemComment
import paol0b.azuredevops.services.AvatarService
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.util.NotificationUtil
import java.awt.*
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import javax.swing.*

/**
 * Reusable panel for displaying and adding work item discussion comments.
 */
class WorkItemCommentPanel(
    private val project: Project,
    private val workItemId: Int
) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(WorkItemCommentPanel::class.java)
    private val apiClient = AzureDevOpsApiClient.getInstance(project)
    private val avatarService = AvatarService.getInstance(project)

    private val commentsContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    private val commentInput = JBTextArea(3, 40).apply {
        emptyText.text = "Add a comment... (Ctrl+Enter to submit)"
        border = JBUI.Borders.empty(8)
        lineWrap = true
        wrapStyleWord = true
    }

    private val submitButton = JButton("Comment", AllIcons.Actions.Execute).apply {
        isEnabled = false
    }

    private val noCommentsLabel = JBLabel("No comments yet").apply {
        foreground = JBColor.GRAY
        font = font.deriveFont(Font.ITALIC, 11f)
        border = JBUI.Borders.empty(8)
    }

    init {
        isOpaque = false
        border = JBUI.Borders.empty(4)

        // Comments list
        val commentsScroll = JBScrollPane(commentsContainer).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(0, JBUI.scale(200))
            verticalScrollBar.unitIncrement = 16
        }
        add(commentsScroll, BorderLayout.CENTER)

        // Input area
        val inputPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(8, 0, 0, 0)

            val inputScroll = JBScrollPane(commentInput).apply {
                border = BorderFactory.createLineBorder(JBColor.border(), 1)
            }
            add(inputScroll, BorderLayout.CENTER)

            val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 4)).apply {
                isOpaque = false
                add(submitButton)
            }
            add(buttonPanel, BorderLayout.SOUTH)
        }
        add(inputPanel, BorderLayout.SOUTH)

        // Enable submit when text is entered
        commentInput.document.addDocumentListener(object : javax.swing.event.DocumentListener {
            override fun insertUpdate(e: javax.swing.event.DocumentEvent?) { updateSubmitState() }
            override fun removeUpdate(e: javax.swing.event.DocumentEvent?) { updateSubmitState() }
            override fun changedUpdate(e: javax.swing.event.DocumentEvent?) { updateSubmitState() }
        })

        // Ctrl+Enter to submit
        commentInput.addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.isControlDown && e.keyCode == KeyEvent.VK_ENTER) {
                    if (commentInput.text.isNotBlank()) {
                        submitComment()
                    }
                }
            }
        })

        submitButton.addActionListener { submitComment() }

        // Load comments
        refresh()
    }

    private fun updateSubmitState() {
        submitButton.isEnabled = commentInput.text.isNotBlank()
    }

    fun refresh() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val comments = apiClient.getWorkItemComments(workItemId)
                ApplicationManager.getApplication().invokeLater {
                    displayComments(comments)
                }
            } catch (e: Exception) {
                logger.warn("Failed to load comments for work item #$workItemId: ${e.message}")
                ApplicationManager.getApplication().invokeLater {
                    commentsContainer.removeAll()
                    commentsContainer.add(JBLabel("Failed to load comments").apply {
                        foreground = JBColor(Color(0xCF222E), Color(0xF85149))
                        border = JBUI.Borders.empty(8)
                    })
                    commentsContainer.revalidate()
                    commentsContainer.repaint()
                }
            }
        }
    }

    private fun displayComments(comments: List<WorkItemComment>) {
        commentsContainer.removeAll()

        if (comments.isEmpty()) {
            commentsContainer.add(noCommentsLabel)
        } else {
            comments.forEach { comment ->
                commentsContainer.add(createCommentCard(comment))
                commentsContainer.add(Box.createVerticalStrut(JBUI.scale(6)))
            }
        }

        commentsContainer.revalidate()
        commentsContainer.repaint()
    }

    private fun createCommentCard(comment: WorkItemComment): JPanel {
        return JPanel(BorderLayout()).apply {
            isOpaque = true
            background = UIUtil.getPanelBackground()
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(8, 10)
            )
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(120))

            // Author line
            val authorPanel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
                isOpaque = false

                // Avatar
                val avatarLabel = JBLabel()
                comment.createdBy?.imageUrl?.let { url ->
                    avatarService.getAvatar(url, 18) {
                        avatarLabel.icon = avatarService.getAvatar(url, 18)
                    }.let { avatarLabel.icon = it }
                }
                add(avatarLabel)

                // Name
                add(JBLabel(comment.createdBy?.displayName ?: "Unknown").apply {
                    font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 11f)
                })

                // Date
                add(JBLabel(comment.getRelativeDate()).apply {
                    foreground = JBColor.GRAY
                    font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 10f)
                })
            }
            add(authorPanel, BorderLayout.NORTH)

            // Comment text (HTML)
            val textPane = JEditorPane("text/html", "").apply {
                isEditable = false
                isOpaque = false
                putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
                font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 11f)
                text = """
                    <html><body style="font-family: ${UIUtil.getLabelFont().family}; font-size: 11px; margin: 4px 0 0 0;">
                    ${comment.text ?: ""}
                    </body></html>
                """.trimIndent()
            }
            add(textPane, BorderLayout.CENTER)
        }
    }

    private fun submitComment() {
        val text = commentInput.text.trim()
        if (text.isBlank()) return

        submitButton.isEnabled = false
        commentInput.isEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                apiClient.addWorkItemComment(workItemId, text)
                ApplicationManager.getApplication().invokeLater {
                    commentInput.text = ""
                    commentInput.isEnabled = true
                    refresh()
                }
            } catch (e: Exception) {
                logger.error("Failed to add comment: ${e.message}")
                ApplicationManager.getApplication().invokeLater {
                    commentInput.isEnabled = true
                    submitButton.isEnabled = true
                    NotificationUtil.error(project, "Comment Failed",
                        "Failed to add comment: ${e.message?.take(100)}")
                }
            }
        }
    }
}
