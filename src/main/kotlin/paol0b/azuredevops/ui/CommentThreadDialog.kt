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
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.Comment
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.PullRequestCommentsService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.Font
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * Dialog to view and manage a comment thread
 * Features: better formatting, HTML escape, visual improvements, real-time updates
 */
class CommentThreadDialog(
    private val project: Project,
    private var thread: CommentThread,
    private val pullRequest: PullRequest,
    private val commentsService: PullRequestCommentsService
) : DialogWrapper(project) {

    private val replyTextArea = JBTextArea(4, 60)
    private val commentsPanel = JPanel()
    private val resolveButton = JButton()
    private val statusPanel = JPanel(BorderLayout())
    private val headerPanel = JPanel(BorderLayout())
    private var isLoading = false

    init {
        title = "PR #${pullRequest.pullRequestId} - Comment Thread"
        init()
        setSize(700, 500)
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout(0, 10))
        panel.border = JBUI.Borders.empty(15)

        // Thread info header
        refreshHeaderPanel()
        panel.add(headerPanel, BorderLayout.NORTH)

        // Comments list
        commentsPanel.layout = BoxLayout(commentsPanel, BoxLayout.Y_AXIS)
        buildCommentsPanel()
        
        val scrollPane = JBScrollPane(commentsPanel).apply {
            preferredSize = Dimension(660, 300)
            border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1)
        }
        panel.add(scrollPane, BorderLayout.CENTER)

        // Reply section
        val replyPanel = createReplyPanel()
        panel.add(replyPanel, BorderLayout.SOUTH)

        return panel
    }

    private fun refreshHeaderPanel() {
        headerPanel.removeAll()
        headerPanel.border = JBUI.Borders.empty(0, 0, 10, 0)
        headerPanel.layout = BorderLayout()
        
        // Status label with icon
        val statusLabel = JLabel().apply {
            if (thread.isResolved()) {
                text = "✓ Resolved"
                icon = AllIcons.RunConfigurations.TestPassed
                foreground = JBColor(java.awt.Color(100, 200, 100), java.awt.Color(80, 150, 80))
            } else {
                text = "● Active"
                icon = AllIcons.General.InspectionsWarning
                foreground = JBColor(java.awt.Color(255, 140, 0), java.awt.Color(255, 160, 50))
            }
            font = font.deriveFont(Font.BOLD, 13f)
        }
        
        // Location/file info
        val locationText = if (thread.getFilePath() != null) {
            "📄 ${thread.getFilePath()} : Line ${thread.getRightFileStart() ?: "?"}"
        } else {
            "📋 General PR Comment"
        }
        val locationLabel = JLabel(locationText).apply {
            font = font.deriveFont(11f)
            foreground = UIUtil.getLabelDisabledForeground()
        }
        
        val infoPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            add(statusLabel)
            add(Box.createVerticalStrut(5))
            add(locationLabel)
        }
        
        headerPanel.add(infoPanel, BorderLayout.WEST)
        
        // Comment count
        val countLabel = JLabel("${thread.comments?.size ?: 0} comment${if (thread.comments?.size != 1) "s" else ""}").apply {
            font = font.deriveFont(11f)
            foreground = UIUtil.getLabelDisabledForeground()
        }
        headerPanel.add(countLabel, BorderLayout.EAST)
        
        headerPanel.revalidate()
        headerPanel.repaint()
    }

    private fun buildCommentsPanel() {
        commentsPanel.removeAll()
        
        val comments = thread.comments ?: emptyList()
        
        if (comments.isEmpty()) {
            val emptyLabel = JLabel("No comments").apply {
                foreground = UIUtil.getLabelDisabledForeground()
                border = JBUI.Borders.empty(20)
            }
            commentsPanel.add(emptyLabel)
        } else {
            for ((index, comment) in comments.withIndex()) {
                if (index > 0) {
                    commentsPanel.add(Box.createVerticalStrut(5))
                    commentsPanel.add(JSeparator(SwingConstants.HORIZONTAL))
                    commentsPanel.add(Box.createVerticalStrut(5))
                }
                
                commentsPanel.add(createCommentPanel(comment))
            }
        }
        
        commentsPanel.revalidate()
        commentsPanel.repaint()
    }

    private fun createCommentPanel(comment: Comment): JPanel {
        val panel = JPanel(BorderLayout(5, 5))
        panel.border = JBUI.Borders.empty(10)
        panel.background = UIUtil.getListBackground()
        
        // Header: author and date
        val headerPanel = JPanel(BorderLayout())
        headerPanel.background = UIUtil.getListBackground()
        
        val authorLabel = JLabel(comment.author?.displayName ?: "Unknown User").apply {
            icon = AllIcons.General.User
            font = font.deriveFont(Font.BOLD, 12f)
        }
        
        val dateLabel = JLabel(formatDate(comment.publishedDate)).apply {
            font = font.deriveFont(10f)
            foreground = UIUtil.getLabelDisabledForeground()
        }
        
        headerPanel.add(authorLabel, BorderLayout.WEST)
        headerPanel.add(dateLabel, BorderLayout.EAST)
        
        panel.add(headerPanel, BorderLayout.NORTH)
        
        // Comment content - properly formatted plain text (no HTML rendering)
        val content = comment.content ?: "(No content)"
        val contentArea = JBTextArea(content).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            background = UIUtil.getListBackground()
            foreground = UIUtil.getLabelForeground()
            border = JBUI.Borders.empty(5, 0)
            font = UIUtil.getLabelFont()
        }
        
        panel.add(contentArea, BorderLayout.CENTER)
        
        return panel
    }

    private fun createReplyPanel(): JPanel {
        val panel = JPanel(BorderLayout(0, 10))
        panel.border = JBUI.Borders.empty(10, 0, 0, 0)
        
        val titleLabel = JLabel("Add Reply:").apply {
            font = font.deriveFont(Font.BOLD)
        }
        panel.add(titleLabel, BorderLayout.NORTH)
        
        // Reply text area
        replyTextArea.apply {
            lineWrap = true
            wrapStyleWord = true
            toolTipText = "Write your reply here (Ctrl+Enter to send quickly)"
            
            // Keyboard shortcut: Ctrl+Enter to send
            addKeyListener(object : java.awt.event.KeyAdapter() {
                override fun keyPressed(e: java.awt.event.KeyEvent) {
                    if (e.keyCode == java.awt.event.KeyEvent.VK_ENTER && e.isControlDown) {
                        val content = text.trim()
                        if (content.isNotEmpty()) {
                            sendReply(content)
                        }
                        e.consume()
                    }
                }
            })
        }
        
        val replyScrollPane = JBScrollPane(replyTextArea).apply {
            preferredSize = Dimension(660, 80)
            border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1)
        }
        panel.add(replyScrollPane, BorderLayout.CENTER)
        
        // Status feedback panel
        statusPanel.apply {
            border = JBUI.Borders.empty(5, 0)
            isVisible = false
        }
        
        // Buttons
        val buttonsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
        }
        
        resolveButton.apply {
            toolTipText = "Toggle between active and resolved status"
            addActionListener { toggleResolveStatus() }
        }
        updateResolveButton()
        
        val sendButton = JButton("Send Reply", AllIcons.Actions.MenuSaveall).apply {
            toolTipText = "Send your reply (or press Ctrl+Enter)"
            addActionListener {
                val content = replyTextArea.text.trim()
                if (content.isNotEmpty()) {
                    sendReply(content)
                } else {
                    Messages.showWarningDialog(project, "Please enter a comment before sending.", "Empty Reply")
                }
            }
        }
        
        buttonsPanel.add(Box.createHorizontalGlue())
        buttonsPanel.add(resolveButton)
        buttonsPanel.add(Box.createHorizontalStrut(10))
        buttonsPanel.add(sendButton)
        
        val bottomPanel = JPanel(BorderLayout()).apply {
            add(statusPanel, BorderLayout.NORTH)
            add(buttonsPanel, BorderLayout.CENTER)
        }
        
        panel.add(bottomPanel, BorderLayout.SOUTH)
        
        return panel
    }

    private fun updateResolveButton() {
        if (thread.isResolved()) {
            resolveButton.text = "Reopen Thread"
            resolveButton.icon = AllIcons.General.InspectionsWarning
        } else {
            resolveButton.text = "Resolve Thread"
            resolveButton.icon = AllIcons.RunConfigurations.TestPassed
        }
    }

    private fun sendReply(content: String) {
        if (isLoading) return
        
        val threadId = thread.id ?: run {
            Messages.showErrorDialog(project, "Invalid thread ID.", "Error")
            return
        }
        
        isLoading = true
        showStatus("Sending reply...", JBColor.BLUE)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                apiClient.addCommentToThread(pullRequest.pullRequestId, threadId, content)
                
                // Reload thread to get the new reply
                val updatedThreads = apiClient.getCommentThreads(pullRequest.pullRequestId)
                val updatedThread = updatedThreads.firstOrNull { it.id == threadId }
                
                ApplicationManager.getApplication().invokeLater {
                    isLoading = false
                    replyTextArea.text = ""
                    showStatus("✓ Reply sent successfully!", JBColor.GREEN)

                    // Update thread and refresh UI to show the new reply immediately
                    if (updatedThread != null) {
                        thread = updatedThread
                        buildCommentsPanel()
                        refreshHeaderPanel()
                        
                        // Trigger background refresh to update other views
                        paol0b.azuredevops.services.CommentsPollingService.getInstance(project).refreshNow()
                    }
                    
                    // Auto-hide success message after 3 seconds
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
                        "Failed to send reply:\n${e.message}",
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
                
                // Reload thread
                val updatedThreads = apiClient.getCommentThreads(pullRequest.pullRequestId)
                val updatedThread = updatedThreads.firstOrNull { it.id == threadId }
                
                ApplicationManager.getApplication().invokeLater {
                    isLoading = false
                    showStatus("✓ Status updated successfully!", JBColor.GREEN)

                    if (updatedThread != null) {
                        thread = updatedThread
                        updateResolveButton()
                        buildCommentsPanel()
                        refreshHeaderPanel()
                        
                        // Trigger background refresh to update other views
                        paol0b.azuredevops.services.CommentsPollingService.getInstance(project).refreshNow()
                    }
                    
                    // Auto-hide success message
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
                        "Failed to update status:\n${e.message}",
                        "Update Error"
                    )
                }
            }
        }
    }

    private fun showStatus(message: String, color: JBColor) {
        statusPanel.removeAll()
        val label = JLabel(message).apply {
            foreground = color
            font = font.deriveFont(Font.BOLD)
        }
        statusPanel.add(label, BorderLayout.CENTER)
        statusPanel.isVisible = true
        statusPanel.revalidate()
        statusPanel.repaint()
    }
    
    private fun hideStatus() {
        statusPanel.isVisible = false
        statusPanel.removeAll()
    }

    private fun formatDate(dateString: String?): String {
        if (dateString == null) return ""
        
        return try {
            val zonedDateTime = ZonedDateTime.parse(dateString)
            val formatter = DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm")
            zonedDateTime.format(formatter)
        } catch (e: Exception) {
            dateString.substringBefore('T')
        }
    }
}
