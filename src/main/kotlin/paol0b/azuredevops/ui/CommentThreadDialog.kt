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
    private lateinit var commentsScrollPane: JBScrollPane
    private val statusComboBox = javax.swing.JComboBox<paol0b.azuredevops.model.ThreadStatus>()
    private val statusPanel = JPanel(BorderLayout())
    private val headerPanel = JPanel(BorderLayout())
    private lateinit var sendButton: JButton
    private var refreshTimer: Timer? = null
    private var isLoading = false
    private var isRefreshing = false
    private var isUpdatingUi = false
    private var lastThreadHash: Int = 0

    init {
        title = "PR #${pullRequest.pullRequestId} - Comment Thread"
        init()
        setSize(700, 500)
        startAutoRefresh()
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
        lastThreadHash = calculateThreadHash(thread)
        
        commentsScrollPane = JBScrollPane(commentsPanel).apply {
            preferredSize = Dimension(660, 300)
            border = JBUI.Borders.customLine(JBUI.CurrentTheme.CustomFrameDecorations.separatorForeground(), 1)
        }
        panel.add(commentsScrollPane, BorderLayout.CENTER)

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
            when {
                thread.isResolved() -> {
                    text = "✓ Resolved"
                    icon = AllIcons.RunConfigurations.TestPassed
                    foreground = JBColor(java.awt.Color(100, 200, 100), java.awt.Color(80, 150, 80))
                }
                thread.status == paol0b.azuredevops.model.ThreadStatus.Pending -> {
                    text = "◐ Pending"
                    icon = AllIcons.RunConfigurations.TestState.Run
                    foreground = JBColor(java.awt.Color(100, 150, 255), java.awt.Color(120, 160, 200))
                }
                else -> {
                    text = "● Active"
                    icon = AllIcons.General.InspectionsWarning
                    foreground = JBColor(java.awt.Color(255, 140, 0), java.awt.Color(255, 160, 50))
                }
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
        
        // Also refresh the scroll pane and scroll to bottom
        if (::commentsScrollPane.isInitialized) {
            commentsScrollPane.revalidate()
            commentsScrollPane.repaint()
            
            // Scroll to bottom to show the latest comment
            javax.swing.SwingUtilities.invokeLater {
                val verticalBar = commentsScrollPane.verticalScrollBar
                verticalBar.value = verticalBar.maximum
            }
        }
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
        
        // Status dropdown with all available statuses
        val statusLabel = JLabel("Status:")
        statusComboBox.apply {
            // Add all thread statuses except Unknown
            paol0b.azuredevops.model.ThreadStatus.entries
                .filter { it != paol0b.azuredevops.model.ThreadStatus.Unknown }
                .forEach { addItem(it) }
            
            // Set the current thread status as default
            val currentStatus = thread.status ?: paol0b.azuredevops.model.ThreadStatus.Active
            selectedItem = currentStatus
            
            // Custom renderer to show display names
            renderer = object : javax.swing.DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: javax.swing.JList<*>?,
                    value: Any?,
                    index: Int,
                    isSelected: Boolean,
                    cellHasFocus: Boolean
                ): java.awt.Component {
                    super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is paol0b.azuredevops.model.ThreadStatus) {
                        text = value.getDisplayName()
                    }
                    return this
                }
            }
            toolTipText = "Select the thread status"
            
            // Auto-update when selection changes
            addItemListener { event ->
                if (event.stateChange == java.awt.event.ItemEvent.SELECTED) {
                    val selectedStatus = selectedItem as? paol0b.azuredevops.model.ThreadStatus
                    if (selectedStatus != null && selectedStatus != thread.status && !isLoading && !isUpdatingUi) {
                        updateThreadStatus(selectedStatus)
                    }
                }
            }
        }
        
        sendButton = JButton("Send Reply", AllIcons.Actions.MenuSaveall).apply {
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
        buttonsPanel.add(statusLabel)
        buttonsPanel.add(Box.createHorizontalStrut(5))
        buttonsPanel.add(statusComboBox)
        buttonsPanel.add(Box.createHorizontalStrut(10))
        buttonsPanel.add(sendButton)
        
        val bottomPanel = JPanel(BorderLayout()).apply {
            add(statusPanel, BorderLayout.NORTH)
            add(buttonsPanel, BorderLayout.CENTER)
        }
        
        panel.add(bottomPanel, BorderLayout.SOUTH)
        
        return panel
    }

    private fun updateStatusComboBox() {
        isUpdatingUi = true
        val currentStatus = thread.status ?: paol0b.azuredevops.model.ThreadStatus.Active
        statusComboBox.selectedItem = currentStatus
        isUpdatingUi = false
    }

    private fun sendReply(content: String) {
        if (isLoading) return
        
        val threadId = thread.id ?: run {
            Messages.showErrorDialog(project, "Invalid thread ID.", "Error")
            return
        }
        
        isLoading = true
        setControlsEnabled(false)
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
                    setControlsEnabled(true)
                    replyTextArea.text = ""
                    showStatus("✓ Reply sent successfully!", JBColor.GREEN)

                    // Update thread and refresh UI to show the new reply immediately
                    if (updatedThread != null) {
                        applyThreadUpdate(updatedThread)
                        
                        // Trigger global refresh to update all views (polling, toolwindow, editor)
                        triggerGlobalRefresh()
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
                    setControlsEnabled(true)
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

    private fun updateThreadStatus(newStatus: paol0b.azuredevops.model.ThreadStatus) {
        if (isLoading) return
        
        val threadId = thread.id ?: run {
            Messages.showErrorDialog(project, "Invalid thread ID.", "Error")
            return
        }
        
        // Check if status actually changed
        if (newStatus == thread.status) {
            return
        }
        
        isLoading = true
        setControlsEnabled(false)
        showStatus("Updating status to '${newStatus.getDisplayName()}'...", JBColor.BLUE)

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                
                apiClient.updateThreadStatus(pullRequest.pullRequestId, threadId, newStatus)
                
                // Reload thread
                val updatedThreads = apiClient.getCommentThreads(pullRequest.pullRequestId)
                val updatedThread = updatedThreads.firstOrNull { it.id == threadId }
                
                ApplicationManager.getApplication().invokeLater {
                    isLoading = false
                    setControlsEnabled(true)
                    showStatus("✓ Status updated to '${newStatus.getDisplayName()}'!", JBColor.GREEN)

                    if (updatedThread != null) {
                        applyThreadUpdate(updatedThread)
                        
                        // Trigger global refresh to update all views (polling, toolwindow, editor)
                        triggerGlobalRefresh()
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
                    setControlsEnabled(true)
                    showStatus("✗ Error: ${e.message}", JBColor.RED)
                    // Reset combobox to current thread status
                    updateStatusComboBox()
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

    private fun setControlsEnabled(enabled: Boolean) {
        statusComboBox.isEnabled = enabled
        sendButton.isEnabled = enabled
        replyTextArea.isEditable = enabled
        replyTextArea.isEnabled = enabled
    }

    private fun startAutoRefresh() {
        refreshTimer?.stop()
        refreshTimer = Timer(8000) {
            refreshThreadFromServer()
        }.apply {
            isRepeats = true
            start()
        }
    }

    private fun refreshThreadFromServer() {
        if (isLoading || isRefreshing) return

        val threadId = thread.id ?: return
        isRefreshing = true

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val updatedThreads = apiClient.getCommentThreads(pullRequest.pullRequestId)
                val updatedThread = updatedThreads.firstOrNull { it.id == threadId }

                ApplicationManager.getApplication().invokeLater {
                    isRefreshing = false
                    if (updatedThread != null) {
                        val newHash = calculateThreadHash(updatedThread)
                        if (newHash != lastThreadHash) {
                            applyThreadUpdate(updatedThread)
                        }
                    }
                }
            } catch (_: Exception) {
                ApplicationManager.getApplication().invokeLater {
                    isRefreshing = false
                }
            }
        }
    }

    private fun applyThreadUpdate(updatedThread: CommentThread) {
        thread = updatedThread
        lastThreadHash = calculateThreadHash(updatedThread)
        updateStatusComboBox()
        buildCommentsPanel()
        refreshHeaderPanel()
    }

    private fun calculateThreadHash(updatedThread: CommentThread): Int {
        var hash = updatedThread.id ?: 0
        hash = 31 * hash + (updatedThread.status?.hashCode() ?: 0)
        hash = 31 * hash + (updatedThread.comments?.size ?: 0)
        hash = 31 * hash + (updatedThread.comments?.firstOrNull()?.content?.hashCode() ?: 0)
        hash = 31 * hash + (updatedThread.comments?.lastOrNull()?.content?.hashCode() ?: 0)
        return hash
    }

    private fun triggerGlobalRefresh() {
        // Refresh polling service
        paol0b.azuredevops.services.CommentsPollingService.getInstance(project).refreshNow()
        
        // Refresh toolwindow if open
        val toolWindowManager = com.intellij.openapi.wm.ToolWindowManager.getInstance(project)
        val toolWindow = toolWindowManager.getToolWindow("PR Comments")
        if (toolWindow != null && toolWindow.isVisible) {
            val contentManager = toolWindow.contentManager
            val content = contentManager.selectedContent
            if (content != null) {
                val component = content.component
                if (component is paol0b.azuredevops.toolwindow.CommentsNavigatorPanel) {
                    component.forceRefresh()
                }
            }
        }
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

    override fun dispose() {
        refreshTimer?.stop()
        refreshTimer = null
        super.dispose()
    }
}
