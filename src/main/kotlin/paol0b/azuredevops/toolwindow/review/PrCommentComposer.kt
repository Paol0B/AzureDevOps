package paol0b.azuredevops.toolwindow.review

import java.awt.BorderLayout
import java.awt.Color
import java.awt.FlowLayout
import javax.swing.*

/** The composer stays outside the polling timeline so refreshes cannot erase a draft. */
class PrCommentComposer(
    private val submitComment: (String, (Result<Unit>) -> Unit) -> Unit,
    private val onCommentAdded: () -> Unit
) : JPanel(BorderLayout(0, 6)) {
    private val textArea = ReviewCommentInput(null, false, "Write a comment…").apply {
        preferredSize = java.awt.Dimension(450, 100)
        accessibleContext.accessibleName = "PR comment"
    }
    private val errorLabel = JLabel().apply { foreground = Color(190, 45, 45); isVisible = false }
    private val submitButton = JButton("Add comment")

    init {
        border = BorderFactory.createEmptyBorder(8, 14, 10, 14)
        add(JLabel("Comment on this pull request"), BorderLayout.NORTH)
        add(textArea, BorderLayout.CENTER)
        val footer = JPanel(BorderLayout()).apply {
            add(errorLabel, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, 0, 0)).apply { add(submitButton) }, BorderLayout.EAST)
        }
        add(footer, BorderLayout.SOUTH)
        submitButton.addActionListener { submit() }
        textArea.registerSubmitShortcut { if (submitButton.isEnabled) submit() }
    }

    fun focusComment() { textArea.requestEditorFocus() }

    private fun submit() {
        val content = textArea.text.trim()
        if (content.isEmpty() || !submitButton.isEnabled) return
        errorLabel.isVisible = false
        submitButton.isEnabled = false
        submitButton.text = "Adding…"
        textArea.isEnabled = false
        val completed: (Result<Unit>) -> Unit = { result ->
            submitButton.isEnabled = true
            submitButton.text = "Add comment"
            textArea.isEnabled = true
            result.fold(
                onSuccess = { textArea.text = ""; onCommentAdded() },
                onFailure = { errorLabel.text = it.message ?: "Unable to add comment. Please try again."; errorLabel.isVisible = true }
            )
            revalidate()
            repaint()
        }
        try { submitComment(content, completed) } catch (e: Exception) { completed(Result.failure(e)) }
    }
}
