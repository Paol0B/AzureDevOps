package paol0b.azuredevops.toolwindow.review

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.ThreadStatus
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.PullRequestCommentsService
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import javax.swing.*

/**
 * Comments panel for displaying and managing PR comments
 * Shows threaded comments with filtering and navigation
 */
class CommentsPanel(
    private val project: Project,
    private val pullRequestId: Int
) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(CommentsPanel::class.java)
    private val apiClient = AzureDevOpsApiClient.getInstance(project)
    private val commentsService = PullRequestCommentsService.getInstance(project)
    
    private val commentsContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        border = JBUI.Borders.empty(5)
    }
    
    private val filterField = JBTextField().apply {
        emptyText.text = "Filter comments..."
    }
    
    private val filterComboBox = JComboBox(arrayOf("All", "Active", "Resolved", "Unresolved")).apply {
        selectedIndex = 0
    }
    
    private var allThreads: List<CommentThread> = emptyList()
    
    private val refreshButton = JButton("🔄 Refresh").apply {
        toolTipText = "Refresh comments"
        addActionListener { loadComments() }
    }

    init {
        setupUI()
        loadComments()
    }

    private fun setupUI() {
        // Top toolbar with filters
        val toolbar = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5)
            
            val leftPanel = JPanel(BorderLayout()).apply {
                add(JBLabel("Comments"), BorderLayout.WEST)
                add(Box.createHorizontalStrut(10), BorderLayout.CENTER)
            }
            
            val rightPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.X_AXIS)
                add(filterComboBox)
                add(Box.createHorizontalStrut(5))
                add(filterField.apply { 
                    maximumSize = Dimension(200, 30)
                    preferredSize = Dimension(150, 25)
                })
                add(Box.createHorizontalStrut(5))
                add(refreshButton)
            }
            
            add(leftPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.EAST)
        }
        
        // Filter listeners
        filterField.addActionListener { applyFilters() }
        filterComboBox.addActionListener { applyFilters() }
        
        // Scrollable comments container
        val scrollPane = JBScrollPane(commentsContainer).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            minimumSize = Dimension(0, 100)
            preferredSize = Dimension(0, 200)
        }
        
        add(toolbar, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
    }

    /**
     * Load comments from Azure DevOps
     */
    fun loadComments() {
        refreshButton.isEnabled = false
        commentsContainer.removeAll()
        commentsContainer.add(JBLabel("Loading comments...").apply {
            border = JBUI.Borders.empty(10)
        })
        commentsContainer.revalidate()
        commentsContainer.repaint()
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val threads = apiClient.getPullRequestThreads(pullRequestId)
                allThreads = threads
                
                ApplicationManager.getApplication().invokeLater {
                    displayComments(threads)
                    refreshButton.isEnabled = true
                }
            } catch (e: Exception) {
                logger.error("Failed to load comments for PR #$pullRequestId", e)
                ApplicationManager.getApplication().invokeLater {
                    showError("Failed to load comments: ${e.message}")
                    refreshButton.isEnabled = true
                }
            }
        }
    }

    /**
     * Display comments in the UI
     */
    private fun displayComments(threads: List<CommentThread>) {
        commentsContainer.removeAll()
        
        if (threads.isEmpty()) {
            commentsContainer.add(JBLabel("No comments found").apply {
                border = JBUI.Borders.empty(10)
                foreground = JBColor.GRAY
            })
        } else {
            // Group by file
            val fileThreads = threads.filter { it.threadContext?.filePath != null }
            val generalThreads = threads.filter { it.threadContext?.filePath == null }
            
            // Display file comments
            if (fileThreads.isNotEmpty()) {
                commentsContainer.add(createSectionLabel("File Comments"))
                
                fileThreads.groupBy { it.threadContext?.filePath }.forEach { (filePath, fileThreadsList) ->
                    val fileName = filePath?.substringAfterLast('/') ?: "Unknown"
                    commentsContainer.add(createFileHeader(fileName, filePath ?: ""))
                    
                    fileThreadsList.forEach { thread ->
                        commentsContainer.add(createThreadPanel(thread))
                    }
                }
            }
            
            // Display general comments
            if (generalThreads.isNotEmpty()) {
                commentsContainer.add(createSectionLabel("General Comments"))
                generalThreads.forEach { thread ->
                    commentsContainer.add(createThreadPanel(thread))
                }
            }
        }
        
        commentsContainer.revalidate()
        commentsContainer.repaint()
        
        logger.info("Displayed ${threads.size} comment threads")
    }

    /**
     * Create a section label
     */
    private fun createSectionLabel(text: String): JComponent {
        return JBLabel(text).apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
            border = JBUI.Borders.empty(10, 5, 5, 5)
        }
    }

    /**
     * Create a file header
     */
    private fun createFileHeader(fileName: String, filePath: String): JComponent {
        return JPanel(BorderLayout()).apply {
            border = JBUI.Borders.compound(
                JBUI.Borders.empty(5, 5, 2, 5),
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            )
            background = JBColor.background()
            
            add(JBLabel("📄 $fileName").apply {
                toolTipText = filePath
                font = font.deriveFont(java.awt.Font.BOLD)
            }, BorderLayout.WEST)
        }
    }

    /**
     * Create a comment thread panel
     */
    private fun createThreadPanel(thread: CommentThread): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.compound(
                JBUI.Borders.empty(5, 15, 5, 5),
                JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            )
            background = JBColor.background()
        }
        
        // Status indicator
        val statusColor = when {
            thread.isDeleted == true -> JBColor.GRAY
            thread.status == ThreadStatus.Active -> JBColor.YELLOW
            thread.status == ThreadStatus.Fixed || thread.status == ThreadStatus.Closed -> JBColor.GREEN
            else -> JBColor.BLUE
        }
        
        val statusLabel = JBLabel().apply {
            text = when {
                thread.isDeleted == true -> "🗑️ Deleted"
                thread.status == ThreadStatus.Active -> "💬 Active"
                thread.status == ThreadStatus.Fixed || thread.status == ThreadStatus.Closed -> "✅ Resolved"
                else -> "💭 ${thread.status?.getDisplayName() ?: "Unknown"}"
            }
            foreground = statusColor
        }
        
        // Line reference (if applicable)
        val lineInfo = thread.threadContext?.let { context ->
            context.rightFileStart?.let { line ->
                JBLabel("Line $line").apply {
                    font = font.deriveFont(java.awt.Font.ITALIC)
                    foreground = JBColor.GRAY
                }
            }
        }
        
        // Header with status and line info
        val header = JPanel(BorderLayout()).apply {
            add(statusLabel, BorderLayout.WEST)
            lineInfo?.let { add(it, BorderLayout.EAST) }
            border = JBUI.Borders.empty(0, 0, 5, 0)
        }
        panel.add(header)
        
        // Comments in the thread
        thread.comments?.forEachIndexed { index, comment ->
            val isFirst = index == 0
            val author = comment.author?.displayName ?: "Unknown"
            val content = comment.content ?: ""
            val date = comment.publishedDate?.substringBefore('T') ?: ""
            
            val commentPanel = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                border = JBUI.Borders.empty(if (isFirst) 0 else 5, if (isFirst) 0 else 10, 0, 0)
            }
            
            // Author and date
            val authorLabel = JBLabel("$author • $date").apply {
                font = font.deriveFont(java.awt.Font.BOLD, 11f)
            }
            commentPanel.add(authorLabel)
            
            // Comment content
            val contentLabel = JBLabel("<html>${content.replace("\n", "<br>")}</html>").apply {
                border = JBUI.Borders.empty(2, 0, 0, 0)
            }
            commentPanel.add(contentLabel)
            
            // Reply indicator
            if (!isFirst) {
                commentPanel.border = JBUI.Borders.compound(
                    commentPanel.border,
                    JBUI.Borders.customLine(JBColor.border(), 0, 2, 0, 0)
                )
            }
            
            panel.add(commentPanel)
        }
        
        return panel
    }

    /**
     * Apply filters to displayed comments
     */
    private fun applyFilters() {
        val filterText = filterField.text.lowercase()
        val statusFilter = filterComboBox.selectedItem as? String ?: "All"
        
        val filtered = allThreads.filter { thread ->
            // Status filter
            val matchesStatus = when (statusFilter) {
                "Active" -> thread.status == ThreadStatus.Active
                "Resolved" -> thread.status == ThreadStatus.Fixed || thread.status == ThreadStatus.Closed
                "Unresolved" -> thread.status != ThreadStatus.Fixed && thread.status != ThreadStatus.Closed
                else -> true
            }
            
            // Text filter
            val matchesText = if (filterText.isEmpty()) {
                true
            } else {
                val filePath = thread.threadContext?.filePath?.lowercase() ?: ""
                val comments = thread.comments?.joinToString(" ") { 
                    "${it.author?.displayName} ${it.content}"
                }?.lowercase() ?: ""
                
                filePath.contains(filterText) || comments.contains(filterText)
            }
            
            matchesStatus && matchesText
        }
        
        displayComments(filtered)
    }

    /**
     * Show error message
     */
    private fun showError(message: String) {
        commentsContainer.removeAll()
        commentsContainer.add(JBLabel("❌ $message").apply {
            border = JBUI.Borders.empty(10)
            foreground = JBColor.RED
        })
        commentsContainer.revalidate()
        commentsContainer.repaint()
    }

    /**
     * Refresh comments
     */
    fun refresh() {
        loadComments()
    }
}
