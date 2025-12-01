package paol0b.azuredevops.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.Comment
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.PullRequestCommentsService
import java.awt.BorderLayout
import java.awt.Dimension
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * Dialog to view and manage a comment thread
 * Improved with auto-refresh, visual feedback, and error handling
 */
class CommentThreadDialog(
    private val project: Project,
    private var thread: CommentThread,
    private val pullRequest: PullRequest,
    private val commentsService: PullRequestCommentsService
) : DialogWrapper(project) {

    private val replyTextArea = JBTextArea(3, 50)
    private val commentsPanel = JPanel()
    private val resolveButton = JButton()
    private val statusPanel = JPanel(BorderLayout())
    private var isLoading = false

    init {
        title = "PR Comments #${pullRequest.pullRequestId}"
        init()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10))
        panel.border = JBUI.Borders.empty(10)

        // Thread info
        val infoPanel = JPanel(BorderLayout())
        val statusLabel = JLabel(
            if (thread.isResolved()) "✓ Resolved" else "○ Active",
            if (thread.isResolved()) AllIcons.RunConfigurations.TestPassed else AllIcons.General.InspectionsWarning,
            SwingConstants.LEFT
        )
        statusLabel.font = statusLabel.font.deriveFont(12f)
        
        val locationText = if (thread.getFilePath() != null) {
            "Line ${thread.getRightFileStart() ?: "?"} in ${thread.getFilePath()}"
        } else {
            "General comment on the PR"
        }
        val locationLabel = JLabel(locationText)
        locationLabel.font = locationLabel.font.deriveFont(10f)
        locationLabel.foreground = javax.swing.UIManager.getColor("Label.disabledForeground")
        
        val topPanel = JPanel(BorderLayout())
        topPanel.add(statusLabel, BorderLayout.WEST)
        topPanel.add(locationLabel, BorderLayout.EAST)
        
        infoPanel.add(topPanel, BorderLayout.NORTH)
        panel.add(infoPanel, BorderLayout.NORTH)

        // List of comments
        commentsPanel.layout = BoxLayout(commentsPanel, BoxLayout.Y_AXIS)
        buildCommentsPanel()
        
        val scrollPane = JBScrollPane(commentsPanel)
        scrollPane.preferredSize = Dimension(600, 300)
        panel.add(scrollPane, BorderLayout.CENTER)

        // Reply area
        val replyPanel = JPanel(BorderLayout(0, 5))
        replyPanel.border = JBUI.Borders.empty(10, 0, 0, 0)
        
        val replyLabel = JLabel("Reply:")
        replyPanel.add(replyLabel, BorderLayout.NORTH)
        
        replyTextArea.lineWrap = true
        replyTextArea.wrapStyleWord = true
        replyTextArea.toolTipText = "Write your reply here (Ctrl+Enter to send)"

        // Add Ctrl+Enter shortcut to send
        replyTextArea.addKeyListener(object : java.awt.event.KeyAdapter() {
            override fun keyPressed(e: java.awt.event.KeyEvent) {
                if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER && e.isControlDown) {
                    val content = replyTextArea.text.trim()
                    if (content.isNotEmpty()) {
                        sendReply(content)
                    }
                    e.consume()
                }
            }
        })
        
        val replyScrollPane = JBScrollPane(replyTextArea)
        replyScrollPane.preferredSize = Dimension(600, 60)
        replyPanel.add(replyScrollPane, BorderLayout.CENTER)
        
        val buttonsPanel = JPanel()
        buttonsPanel.layout = BoxLayout(buttonsPanel, BoxLayout.X_AXIS)
        
        val replyButton = JButton("Send Reply", AllIcons.Actions.MenuSaveall)
        replyButton.toolTipText = "Send the reply to the comment thread"
        replyButton.addActionListener {
            val content = replyTextArea.text.trim()
            if (content.isNotEmpty()) {
                sendReply(content)
            } else {
                Messages.showWarningDialog(project, "Enter a comment before sending.", "Empty Comment")
            }
        }
        
        resolveButton.toolTipText = "Change the thread status (resolved/active)"
        resolveButton.addActionListener {
            toggleResolveStatus()
        }
        updateResolveButton()
        
        buttonsPanel.add(Box.createHorizontalGlue())
        buttonsPanel.add(resolveButton)
        buttonsPanel.add(Box.createHorizontalStrut(10))
        buttonsPanel.add(replyButton)
        
        // Status panel for feedback
        statusPanel.border = JBUI.Borders.empty(5, 0, 0, 0)
        statusPanel.isVisible = false
        replyPanel.add(statusPanel, BorderLayout.NORTH)
        
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
        
        // Header with author and date
        val headerPanel = JPanel(BorderLayout())
        
        val authorLabel = JLabel(comment.author?.displayName ?: "Unknown")
        authorLabel.font = authorLabel.font.deriveFont(java.awt.Font.BOLD)
        
        val dateLabel = JLabel(formatDate(comment.publishedDate))
        dateLabel.font = dateLabel.font.deriveFont(10f)
        dateLabel.foreground = javax.swing.UIManager.getColor("Label.disabledForeground")
        
        headerPanel.add(authorLabel, BorderLayout.WEST)
        headerPanel.add(dateLabel, BorderLayout.EAST)
        
        panel.add(headerPanel, BorderLayout.NORTH)
        
        // Content
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
        if (isLoading) return
        
        val threadId = thread.id ?: run {
            Messages.showErrorDialog(project, "Invalid thread ID.", "Error")
            return
        }
        
        isLoading = true
        showStatus("Sending...", JBColor.BLUE)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                apiClient.addCommentToThread(pullRequest.pullRequestId, threadId, content)
                
                // Reload the updated thread
                val updatedThreads = apiClient.getCommentThreads(pullRequest.pullRequestId)
                val updatedThread = updatedThreads.firstOrNull { it.id == threadId }
                
                ApplicationManager.getApplication().invokeLater {
                    isLoading = false
                    replyTextArea.text = ""  // Clear the textbox
                    showStatus("✓ Reply sent successfully!", JBColor.GREEN)

                    // Auto-refresh comments
                    if (updatedThread != null) {
                        thread = updatedThread
                        buildCommentsPanel()
                    }
                    
                    // Hide the success message after 3 seconds
                    Timer(3000) {
                        hideStatus()
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    isLoading = false
                    showStatus("✗ Error: ${e.message}", JBColor.RED)
                    Messages.showErrorDialog(
                        project,
                        "Error while sending reply:\n${e.message}",
                        "Send Error"
                    )
                }
            }
        }
    }

    private fun toggleResolveStatus() {
        if (isLoading) return
        
        val threadId = thread.id ?: run {
            Messages.showErrorDialog(project, "Invalid thread ID.", "Error")
            return
        }
        
        isLoading = true
        val action = if (thread.isResolved()) "Reopening" else "Resolving"
        showStatus("$action...", JBColor.BLUE)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val newStatus = if (thread.isResolved()) {
                    paol0b.azuredevops.model.ThreadStatus.Active
                } else {
                    paol0b.azuredevops.model.ThreadStatus.Fixed
                }
                
                apiClient.updateThreadStatus(pullRequest.pullRequestId, threadId, newStatus)
                
                // Reload the updated thread
                val updatedThreads = apiClient.getCommentThreads(pullRequest.pullRequestId)
                val updatedThread = updatedThreads.firstOrNull { it.id == threadId }
                
                ApplicationManager.getApplication().invokeLater {
                    isLoading = false
                    showStatus("✓ Status updated successfully!", JBColor.GREEN)

                    if (updatedThread != null) {
                        thread = updatedThread
                        updateResolveButton()
                        buildCommentsPanel()
                    }
                    
                    // Hide the message after 3 seconds
                    Timer(3000) {
                        hideStatus()
                    }.apply {
                        isRepeats = false
                        start()
                    }
                }
            } catch (e: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    isLoading = false
                    showStatus("✗ Error: ${e.message}", JBColor.RED)
                    Messages.showErrorDialog(
                        project,
                        "Error while updating status:\n${e.message}",
                        "Update Error"
                    )
                }
            }
        }
    }
    
    private fun showStatus(message: String, color: JBColor) {
        statusPanel.removeAll()
        val label = JLabel(message)
        label.foreground = color
        label.font = label.font.deriveFont(java.awt.Font.BOLD)
        statusPanel.add(label, BorderLayout.CENTER)
        statusPanel.isVisible = true
        statusPanel.revalidate()
        statusPanel.repaint()
    }
    
    private fun hideStatus() {
        statusPanel.isVisible = false
        statusPanel.removeAll()
    }

    private fun updateResolveButton() {
        if (thread.isResolved()) {
            resolveButton.text = "Reopen"
            resolveButton.icon = AllIcons.General.InspectionsWarning
        } else {
            resolveButton.text = "Resolve"
            resolveButton.icon = AllIcons.RunConfigurations.TestPassed
        }
    }

    override fun createActions(): Array<Action> {
        return arrayOf(cancelAction)
    }
}
