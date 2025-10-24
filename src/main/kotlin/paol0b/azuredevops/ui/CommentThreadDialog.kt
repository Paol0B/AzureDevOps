package paol0b.azuredevops.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.Comment
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.PullRequestCommentsService
import java.awt.BorderLayout
import java.awt.Dimension
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * Dialog per visualizzare e gestire un thread di commenti
 */
class CommentThreadDialog(
    private val project: Project,
    private val thread: CommentThread,
    private val pullRequest: PullRequest,
    private val commentsService: PullRequestCommentsService
) : DialogWrapper(project) {

    private val replyTextArea = JBTextArea(3, 50)
    private val commentsPanel = JPanel()
    private val resolveButton = JButton()

    init {
        title = "Commenti PR #${pullRequest.pullRequestId}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10))
        panel.border = JBUI.Borders.empty(10)

        // Info thread
        val infoPanel = JPanel(BorderLayout())
        val statusLabel = JLabel(
            if (thread.isResolved()) "✓ Risolto" else "○ Attivo",
            if (thread.isResolved()) AllIcons.RunConfigurations.TestPassed else AllIcons.General.InspectionsWarning,
            SwingConstants.LEFT
        )
        statusLabel.font = statusLabel.font.deriveFont(12f)
        
        val locationLabel = JLabel("Linea ${thread.getRightFileStart() ?: "?"} in ${thread.getFilePath() ?: "?"}")
        locationLabel.font = locationLabel.font.deriveFont(10f)
        locationLabel.foreground = javax.swing.UIManager.getColor("Label.disabledForeground")
        
        val topPanel = JPanel(BorderLayout())
        topPanel.add(statusLabel, BorderLayout.WEST)
        topPanel.add(locationLabel, BorderLayout.EAST)
        
        infoPanel.add(topPanel, BorderLayout.NORTH)
        panel.add(infoPanel, BorderLayout.NORTH)

        // Lista commenti
        commentsPanel.layout = BoxLayout(commentsPanel, BoxLayout.Y_AXIS)
        buildCommentsPanel()
        
        val scrollPane = JBScrollPane(commentsPanel)
        scrollPane.preferredSize = Dimension(600, 300)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Area risposta
        val replyPanel = JPanel(BorderLayout(0, 5))
        replyPanel.border = JBUI.Borders.empty(10, 0, 0, 0)
        
        val replyLabel = JLabel("Rispondi:")
        replyPanel.add(replyLabel, BorderLayout.NORTH)
        
        replyTextArea.lineWrap = true
        replyTextArea.wrapStyleWord = true
        val replyScrollPane = JBScrollPane(replyTextArea)
        replyScrollPane.preferredSize = Dimension(600, 60)
        replyPanel.add(replyScrollPane, BorderLayout.CENTER)
        
        val buttonsPanel = JPanel()
        buttonsPanel.layout = BoxLayout(buttonsPanel, BoxLayout.X_AXIS)
        
        val replyButton = JButton("Invia Risposta")
        replyButton.addActionListener {
            val content = replyTextArea.text.trim()
            if (content.isNotEmpty()) {
                sendReply(content)
            }
        }
        
        resolveButton.addActionListener {
            toggleResolveStatus()
        }
        updateResolveButton()
        
        buttonsPanel.add(Box.createHorizontalGlue())
        buttonsPanel.add(resolveButton)
        buttonsPanel.add(Box.createHorizontalStrut(10))
        buttonsPanel.add(replyButton)
        
        replyPanel.add(buttonsPanel, BorderLayout.SOUTH)
        panel.add(replyPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun buildCommentsPanel() {
        commentsPanel.removeAll()
        
        val comments = thread.comments ?: emptyList()
        
        for ((index, comment) in comments.withIndex()) {
            if (index > 0) {
                commentsPanel.add(Box.createVerticalStrut(10))
                commentsPanel.add(JSeparator(SwingConstants.HORIZONTAL))
                commentsPanel.add(Box.createVerticalStrut(10))
            }
            
            commentsPanel.add(createCommentPanel(comment))
        }
        
        commentsPanel.revalidate()
        commentsPanel.repaint()
    }

    private fun createCommentPanel(comment: Comment): JPanel {
        val panel = JPanel(BorderLayout(5, 5))
        panel.border = JBUI.Borders.empty(5)
        
        // Header con autore e data
        val headerPanel = JPanel(BorderLayout())
        
        val authorLabel = JLabel(comment.author?.displayName ?: "Unknown")
        authorLabel.font = authorLabel.font.deriveFont(java.awt.Font.BOLD)
        
        val dateLabel = JLabel(formatDate(comment.publishedDate))
        dateLabel.font = dateLabel.font.deriveFont(10f)
        dateLabel.foreground = javax.swing.UIManager.getColor("Label.disabledForeground")
        
        headerPanel.add(authorLabel, BorderLayout.WEST)
        headerPanel.add(dateLabel, BorderLayout.EAST)
        
        panel.add(headerPanel, BorderLayout.NORTH)
        
        // Contenuto
        val contentArea = JBTextArea(comment.content ?: "")
        contentArea.isEditable = false
        contentArea.lineWrap = true
        contentArea.wrapStyleWord = true
        contentArea.background = panel.background
        contentArea.border = JBUI.Borders.empty(5, 0)
        
        panel.add(contentArea, BorderLayout.CENTER)
        
        return panel
    }

    private fun formatDate(dateString: String?): String {
        if (dateString == null) return ""
        
        return try {
            val zonedDateTime = ZonedDateTime.parse(dateString)
            zonedDateTime.format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))
        } catch (e: Exception) {
            dateString
        }
    }

    private fun sendReply(content: String) {
        val threadId = thread.id ?: return
        
        commentsService.replyToComment(pullRequest, threadId, content) {
            replyTextArea.text = ""
            // Ricarica i commenti (in un'implementazione completa)
            close(OK_EXIT_CODE)
        }
    }

    private fun toggleResolveStatus() {
        val threadId = thread.id ?: return
        
        if (thread.isResolved()) {
            commentsService.unresolveThread(pullRequest, threadId) {
                close(OK_EXIT_CODE)
            }
        } else {
            commentsService.resolveThread(pullRequest, threadId) {
                close(OK_EXIT_CODE)
            }
        }
    }

    private fun updateResolveButton() {
        if (thread.isResolved()) {
            resolveButton.text = "Riapri"
            resolveButton.icon = AllIcons.General.InspectionsWarning
        } else {
            resolveButton.text = "Risolvi"
            resolveButton.icon = AllIcons.RunConfigurations.TestPassed
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(cancelAction)
    }
}
