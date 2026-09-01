package paol0b.azuredevops.toolwindow.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.toolwindow.review.timeline.RoundedPanel
import java.awt.*
import javax.swing.*

/**
 * GitHub-style "Add Review Comment" editor shown inline below a diff line.
 *
 * Appears when the user clicks the "+" gutter icon on a line:
 * ┌──────────────────────────────────────────────────────┐
 * │  Write a comment…                                    │
 * │                                                      │
 * │  [Cancel]                    [Add Review Comment]    │
 * └──────────────────────────────────────────────────────┘
 */
class InlineCommentEditorComponent(
    private val project: com.intellij.openapi.project.Project,
    private val apiClient: AzureDevOpsApiClient,
    private val pullRequestId: Int,
    private val filePath: String,
    private val lineNumber: Int,
    private val isLeftSide: Boolean,
    private val projectName: String?,
    private val repositoryId: String?,
    private val changeTrackingId: Int?,
    private val onCommentAdded: () -> Unit,
    private val onCancel: () -> Unit
) : JPanel() {

    private val logger = Logger.getInstance(InlineCommentEditorComponent::class.java)

    private val cardBg = JBColor(Color(245, 247, 250), Color(50, 52, 56))
    private val cardBorder = JBColor(Color(208, 215, 222), Color(60, 63, 68))
    private val commentInput = ReviewCommentInput(
        project = project,
        oneLineMode = false,
        placeholder = "Write a comment..."
    )

    val preferredFocusComponent: JComponent
        get() = commentInput

    fun requestInputFocus() {
        commentInput.requestEditorFocus()
    }

    init {
        layout = BorderLayout()
        isOpaque = false
        border = JBUI.Borders.empty(4, 0, 4, 0)
        buildUI()
    }

    private fun buildUI() {
        val card = RoundedPanel(8, cardBg, cardBorder)
        card.layout = BoxLayout(card, BoxLayout.Y_AXIS)
        card.border = JBUI.Borders.empty(10, 12, 10, 12)

        // Label
        card.add(JBLabel("Add review comment — line $lineNumber").apply {
            font = font.deriveFont(Font.BOLD, 11f)
            foreground = JBColor.GRAY
            alignmentX = Component.LEFT_ALIGNMENT
        })
        card.add(Box.createVerticalStrut(6))

        // Use the IDE editor component so Windows IME composition works in the popup.
        commentInput.apply {
            alignmentX = Component.LEFT_ALIGNMENT
            preferredSize = Dimension(440, 84)
            maximumSize = Dimension(Int.MAX_VALUE, 90)
            border = BorderFactory.createLineBorder(cardBorder)
        }
        card.add(commentInput)

        card.add(Box.createVerticalStrut(8))

        // Buttons row
        val buttonsRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 32)
        }

        val cancelBtn = JButton("Cancel").apply {
            font = font.deriveFont(11f)
            addActionListener { onCancel() }
        }

        val submitBtn = JButton("Add Review Comment").apply {
            font = font.deriveFont(Font.BOLD, 11f)
        }

        submitBtn.addActionListener {
            val text = commentInput.text.trim()
            if (text.isEmpty()) return@addActionListener

            submitBtn.isEnabled = false
            submitBtn.text = "Adding…"
            cancelBtn.isEnabled = false

            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    apiClient.createThread(
                        pullRequestId = pullRequestId,
                        filePath = filePath,
                        content = text,
                        startLine = lineNumber,
                        endLine = lineNumber,
                        isLeft = isLeftSide,
                        projectName = projectName,
                        repositoryId = repositoryId,
                        changeTrackingId = changeTrackingId
                    )
                    logger.info("Comment added to $filePath:$lineNumber")
                    ApplicationManager.getApplication().invokeLater { onCommentAdded() }
                } catch (e: Exception) {
                    logger.error("Failed to add comment", e)
                    ApplicationManager.getApplication().invokeLater {
                        submitBtn.isEnabled = true
                        submitBtn.text = "Add Review Comment"
                        cancelBtn.isEnabled = true
                    }
                }
            }
        }

        // Keep Enter available to the IME; only Ctrl+Enter submits.
        commentInput.registerSubmitShortcut { submitBtn.doClick() }

        val leftBtns = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply { isOpaque = false }
        leftBtns.add(cancelBtn)

        val rightBtns = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }
        rightBtns.add(submitBtn)

        buttonsRow.add(leftBtns, BorderLayout.WEST)
        buttonsRow.add(rightBtns, BorderLayout.EAST)

        card.add(buttonsRow)

        add(card, BorderLayout.CENTER)
    }
}
