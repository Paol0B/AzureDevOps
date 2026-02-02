package paol0b.azuredevops.toolwindow.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextArea
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.Comment
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.ThreadStatus
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*

/**
 * Inline comment component that displays a comment thread in the diff viewer
 * Supports collapse/expand, reply, and status changes
 * Designed for cross-repository compatibility
 */
class InlineCommentComponent(
    private val thread: CommentThread,
    private val apiClient: AzureDevOpsApiClient,
    private val pullRequestId: Int,
    private val projectName: String?,
    private val repositoryId: String?,
    private val onStatusChanged: () -> Unit = {},
    private val onReplyAdded: () -> Unit = {}
) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(InlineCommentComponent::class.java)
    
    private var isCollapsed = false
    private val contentPanel = JPanel(BorderLayout())
    private val commentsContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
    }
    private val replyPanel = createReplyPanel()
    private val collapseButton = createCollapseButton()
    
    // Colors
    private val activeColor = JBColor(Color(255, 248, 220), Color(60, 60, 40))
    private val resolvedColor = JBColor(Color(220, 255, 220), Color(40, 60, 40))
    private val borderColor = JBColor(Color(200, 200, 200), Color(80, 80, 80))

    init {
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor, 1),
            JBUI.Borders.empty(0)
        )
        background = if (thread.isActive()) activeColor else resolvedColor
        
        buildUI()
    }
    
    private fun buildUI() {
        removeAll()
        
        // Header with collapse button, author info, and status
        val headerPanel = createHeaderPanel()
        add(headerPanel, BorderLayout.NORTH)
        
        // Content area (comments + reply)
        contentPanel.background = background
        contentPanel.border = JBUI.Borders.empty(0, 8, 8, 8)
        
        // Add all comments
        commentsContainer.removeAll()
        commentsContainer.background = background
        thread.comments?.forEach { comment ->
            if (comment.commentType != "system") {
                commentsContainer.add(createCommentPanel(comment))
                commentsContainer.add(Box.createVerticalStrut(4))
            }
        }
        
        contentPanel.add(commentsContainer, BorderLayout.CENTER)
        contentPanel.add(replyPanel, BorderLayout.SOUTH)
        
        if (!isCollapsed) {
            add(contentPanel, BorderLayout.CENTER)
        }
        
        revalidate()
        repaint()
    }
    
    private fun createHeaderPanel(): JPanel {
        val headerPanel = JPanel(BorderLayout())
        headerPanel.background = background
        headerPanel.border = JBUI.Borders.empty(6, 8, 4, 8)
        
        // Left side: collapse button + author info
        val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0))
        leftPanel.background = background
        leftPanel.isOpaque = false
        
        leftPanel.add(collapseButton)
        
        // Author and time
        val firstComment = thread.comments?.firstOrNull()
        val authorName = firstComment?.author?.displayName ?: "Unknown"
        val timeAgo = firstComment?.publishedDate?.let { formatTimeAgo(it) } ?: ""
        
        val authorLabel = JBLabel("<html><b>$authorName</b> <span style='color:gray'>$timeAgo</span></html>")
        authorLabel.font = authorLabel.font.deriveFont(12f)
        leftPanel.add(authorLabel)
        
        // Comments count badge
        val commentCount = thread.comments?.size ?: 0
        if (commentCount > 1) {
            val countBadge = JBLabel("($commentCount)")
            countBadge.foreground = JBColor.GRAY
            countBadge.font = countBadge.font.deriveFont(11f)
            leftPanel.add(countBadge)
        }
        
        headerPanel.add(leftPanel, BorderLayout.WEST)
        
        // Right side: action buttons and status
        val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0))
        rightPanel.background = background
        rightPanel.isOpaque = false
        
        // Status dropdown
        val statusCombo = createStatusComboBox()
        rightPanel.add(statusCombo)
        
        headerPanel.add(rightPanel, BorderLayout.EAST)
        
        return headerPanel
    }
    
    private fun createCollapseButton(): JButton {
        val button = JButton()
        button.icon = if (isCollapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
        button.preferredSize = Dimension(20, 20)
        button.isBorderPainted = false
        button.isContentAreaFilled = false
        button.isFocusPainted = false
        button.cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        button.toolTipText = if (isCollapsed) "Expand" else "Collapse"
        
        button.addActionListener {
            isCollapsed = !isCollapsed
            button.icon = if (isCollapsed) AllIcons.General.ArrowRight else AllIcons.General.ArrowDown
            button.toolTipText = if (isCollapsed) "Expand" else "Collapse"
            
            if (isCollapsed) {
                remove(contentPanel)
            } else {
                add(contentPanel, BorderLayout.CENTER)
            }
            revalidate()
            repaint()
        }
        
        return button
    }
    
    private fun createCommentPanel(comment: Comment): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = background
        panel.border = JBUI.Borders.empty(4, 0)
        panel.maximumSize = Dimension(Int.MAX_VALUE, panel.preferredSize.height)
        
        // For replies (not first comment), show author
        val isReply = thread.comments?.firstOrNull() != comment
        if (isReply) {
            val replyAuthor = comment.author?.displayName ?: "Unknown"
            val replyTime = comment.publishedDate?.let { formatTimeAgo(it) } ?: ""
            val authorLabel = JBLabel("<html><small><b>$replyAuthor</b> <span style='color:gray'>$replyTime</span></small></html>")
            authorLabel.border = JBUI.Borders.empty(0, 0, 2, 0)
            panel.add(authorLabel, BorderLayout.NORTH)
        }
        
        // Comment content
        val contentLabel = JBLabel("<html><div style='width: 350px'>${escapeHtml(comment.content ?: "")}</div></html>")
        contentLabel.font = UIUtil.getLabelFont()
        panel.add(contentLabel, BorderLayout.CENTER)
        
        return panel
    }
    
    private fun createReplyPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.background = background
        panel.border = JBUI.Borders.empty(8, 0, 0, 0)
        panel.isVisible = !isCollapsed
        
        val replyArea = JBTextArea(2, 30)
        replyArea.lineWrap = true
        replyArea.wrapStyleWord = true
        replyArea.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(borderColor),
            JBUI.Borders.empty(4)
        )
        
        // Placeholder text
        replyArea.text = "Write a reply..."
        replyArea.foreground = JBColor.GRAY
        replyArea.addFocusListener(object : java.awt.event.FocusAdapter() {
            override fun focusGained(e: java.awt.event.FocusEvent) {
                if (replyArea.text == "Write a reply...") {
                    replyArea.text = ""
                    replyArea.foreground = UIUtil.getLabelForeground()
                }
            }
            override fun focusLost(e: java.awt.event.FocusEvent) {
                if (replyArea.text.isEmpty()) {
                    replyArea.text = "Write a reply..."
                    replyArea.foreground = JBColor.GRAY
                }
            }
        })
        
        val scrollPane = JBScrollPane(replyArea)
        scrollPane.border = null
        panel.add(scrollPane, BorderLayout.CENTER)
        
        // Button panel
        val buttonPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 4))
        buttonPanel.background = background
        
        val resolveButton = JButton(if (thread.isActive()) "Resolve" else "Reopen")
        resolveButton.font = resolveButton.font.deriveFont(11f)
        resolveButton.addActionListener {
            val newStatus = if (thread.isActive()) ThreadStatus.Fixed else ThreadStatus.Active
            updateThreadStatus(newStatus)
        }
        
        val replyButton = JButton("Reply")
        replyButton.font = replyButton.font.deriveFont(11f)
        replyButton.addActionListener {
            val text = replyArea.text.trim()
            if (text.isNotEmpty() && text != "Write a reply...") {
                addReply(text, replyArea)
            }
        }
        
        buttonPanel.add(resolveButton)
        buttonPanel.add(replyButton)
        panel.add(buttonPanel, BorderLayout.SOUTH)
        
        return panel
    }
    
    private fun createStatusComboBox(): ComboBox<String> {
        val statuses = arrayOf("Active", "Resolved", "Won't Fix", "Closed", "By Design", "Pending")
        val combo = ComboBox(statuses)
        combo.preferredSize = Dimension(100, 24)
        combo.font = combo.font.deriveFont(11f)
        
        // Set current status
        val currentStatus = when (thread.status) {
            ThreadStatus.Active -> "Active"
            ThreadStatus.Fixed -> "Resolved"
            ThreadStatus.WontFix -> "Won't Fix"
            ThreadStatus.Closed -> "Closed"
            ThreadStatus.ByDesign -> "By Design"
            ThreadStatus.Pending -> "Pending"
            else -> "Active"
        }
        combo.selectedItem = currentStatus
        
        combo.addActionListener {
            val selectedStatus = when (combo.selectedItem as String) {
                "Active" -> ThreadStatus.Active
                "Resolved" -> ThreadStatus.Fixed
                "Won't Fix" -> ThreadStatus.WontFix
                "Closed" -> ThreadStatus.Closed
                "By Design" -> ThreadStatus.ByDesign
                "Pending" -> ThreadStatus.Pending
                else -> ThreadStatus.Active
            }
            if (selectedStatus != thread.status) {
                updateThreadStatus(selectedStatus)
            }
        }
        
        return combo
    }
    
    private fun updateThreadStatus(newStatus: ThreadStatus) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val threadId = thread.id ?: return@executeOnPooledThread
                apiClient.updateThreadStatus(pullRequestId, threadId, newStatus)
                
                logger.info("Thread #$threadId status updated to $newStatus")
                
                ApplicationManager.getApplication().invokeLater {
                    // Update background color based on new status
                    val isNowActive = newStatus == ThreadStatus.Active || newStatus == ThreadStatus.Pending
                    background = if (isNowActive) activeColor else resolvedColor
                    contentPanel.background = background
                    commentsContainer.background = background
                    
                    onStatusChanged()
                }
            } catch (e: Exception) {
                logger.error("Failed to update thread status", e)
            }
        }
    }
    
    private fun addReply(text: String, replyArea: JBTextArea) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val threadId = thread.id ?: return@executeOnPooledThread
                apiClient.addCommentToThread(pullRequestId, threadId, text)
                
                logger.info("Reply added to thread #$threadId")
                
                ApplicationManager.getApplication().invokeLater {
                    replyArea.text = "Write a reply..."
                    replyArea.foreground = JBColor.GRAY
                    onReplyAdded()
                }
            } catch (e: Exception) {
                logger.error("Failed to add reply", e)
            }
        }
    }
    
    private fun formatTimeAgo(dateString: String): String {
        return try {
            // Parse ISO 8601 date
            val formats = listOf(
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
            )
            
            var date: Date? = null
            for (format in formats) {
                format.timeZone = TimeZone.getTimeZone("UTC")
                try {
                    date = format.parse(dateString)
                    break
                } catch (_: Exception) {}
            }
            
            if (date == null) return ""
            
            val now = Date()
            val diffMs = now.time - date.time
            val diffMinutes = diffMs / (1000 * 60)
            val diffHours = diffMs / (1000 * 60 * 60)
            val diffDays = diffMs / (1000 * 60 * 60 * 24)
            
            when {
                diffMinutes < 1 -> "just now"
                diffMinutes < 60 -> "${diffMinutes}m ago"
                diffHours < 24 -> "${diffHours}h ago"
                diffDays < 7 -> "${diffDays}d ago"
                else -> SimpleDateFormat("MMM d", Locale.US).format(date)
            }
        } catch (e: Exception) {
            ""
        }
    }
    
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")
    }
    
    /**
     * Get the line number for this comment (1-based)
     * Returns the right file line if available, otherwise left file line
     */
    fun getLineNumber(): Int? {
        val ctx = thread.pullRequestThreadContext ?: thread.threadContext
        return ctx?.rightFileStart?.line ?: ctx?.leftFileStart?.line
    }
    
    /**
     * Check if this comment is on the left (base) side of the diff
     */
    fun isOnLeftSide(): Boolean {
        val ctx = thread.pullRequestThreadContext ?: thread.threadContext
        return ctx?.leftFileStart != null && ctx.rightFileStart == null
    }
    
    /**
     * Get the file path for this comment
     */
    fun getFilePath(): String? = thread.getFilePath()
}
