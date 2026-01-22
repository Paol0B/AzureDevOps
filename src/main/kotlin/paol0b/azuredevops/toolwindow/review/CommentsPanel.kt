package paol0b.azuredevops.toolwindow.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.ThreadStatus
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.PullRequestCommentsService
import java.awt.*
import javax.swing.*
import javax.swing.border.CompoundBorder

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
        border = JBUI.Borders.empty(10)
        background = UIUtil.getListBackground()
    }
    
    private val filterField = JBTextField().apply {
        emptyText.text = "Filter comments..."
    }
    
    private val filterComboBox = JComboBox(arrayOf("All", "Active", "Resolved", "Unresolved")).apply {
        selectedIndex = 0
    }
    
    private var allThreads: List<CommentThread> = emptyList()
    
    private val refreshButton = JButton(AllIcons.Actions.Refresh).apply {
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
            background = UIUtil.getPanelBackground()
            
            val leftPanel = JPanel(FlowLayout(FlowLayout.LEFT, 5, 0)).apply {
                add(JBLabel("Review Comments").apply { font = JBFont.h3().asBold() })
            }
            
            val rightPanel = JPanel(FlowLayout(FlowLayout.RIGHT, 5, 0)).apply {
                add(filterComboBox)
                add(filterField.apply { 
                    preferredSize = Dimension(150, 30)
                })
                add(refreshButton)
            }
            
            add(leftPanel, BorderLayout.WEST)
            add(rightPanel, BorderLayout.CENTER)
        }
        
        // Filter listeners
        filterField.addActionListener { applyFilters() }
        filterComboBox.addActionListener { applyFilters() }
        
        // Scrollable comments container
        val scrollPane = JBScrollPane(commentsContainer).apply {
            border = JBUI.Borders.empty()
            verticalScrollBarPolicy = JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBarPolicy = JScrollPane.HORIZONTAL_SCROLLBAR_NEVER
            viewport.background = UIUtil.getListBackground()
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
        commentsContainer.add(Box.createVerticalGlue())
        commentsContainer.add(JPanel(FlowLayout(FlowLayout.CENTER)).apply {
            isOpaque = false
            add(JBLabel("Loading comments...", AllIcons.General.ContextHelp, SwingConstants.CENTER).apply {
                foreground = JBColor.GRAY
            })
        })
        commentsContainer.add(Box.createVerticalGlue())
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
            commentsContainer.add(Box.createVerticalGlue())
            commentsContainer.add(JPanel(FlowLayout(FlowLayout.CENTER)).apply {
                isOpaque = false
                add(JBLabel("No comments found", AllIcons.General.Balloon, SwingConstants.CENTER).apply {
                    foreground = JBColor.GRAY
                    font = JBFont.h4()
                })
            })
            commentsContainer.add(Box.createVerticalGlue())
        } else {
            // Group by file
            val fileThreads = threads.filter { it.threadContext?.filePath != null }
            val generalThreads = threads.filter { it.threadContext?.filePath == null }
            
            // Display general comments
            if (generalThreads.isNotEmpty()) {
                commentsContainer.add(createSectionLabel("General Comments"))
                generalThreads.forEach { thread ->
                    commentsContainer.add(createThreadPanel(thread))
                    commentsContainer.add(Box.createVerticalStrut(10))
                }
            }

            // Display file comments
            if (fileThreads.isNotEmpty()) {
                commentsContainer.add(createSectionLabel("File Comments"))
                
                val sortedGroups = fileThreads.groupBy { it.threadContext?.filePath }.toSortedMap(compareBy { it ?: "" })
                
                sortedGroups.forEach { (filePath, fileThreadsList) ->
                    val fileName = filePath?.substringAfterLast('/') ?: "Unknown"
                    val fullPath = filePath ?: ""
                    
                    commentsContainer.add(createFileHeader(fileName, fullPath))
                    commentsContainer.add(Box.createVerticalStrut(5))
                    
                    fileThreadsList.sortedBy { it.threadContext?.rightFileStart?.line ?: 0 }.forEach { thread ->
                        commentsContainer.add(createThreadPanel(thread))
                        commentsContainer.add(Box.createVerticalStrut(10))
                    }
                }
            }
        }
        
        commentsContainer.revalidate()
        commentsContainer.repaint()
    }

    /**
     * Create a section label
     */
    private fun createSectionLabel(text: String): JComponent {
        return JPanel(FlowLayout(FlowLayout.LEFT)).apply {
            isOpaque = false
            add(JBLabel(text).apply {
                font = JBFont.h4().asBold()
                foreground = JBColor.GRAY
            })
        }
    }

    /**
     * Create a file header
     */
    private fun createFileHeader(fileName: String, filePath: String): JComponent {
        return JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(10, 5, 5, 5)
            
            val icon = if (fileName.endsWith(".kt") || fileName.endsWith(".java")) AllIcons.FileTypes.Java else AllIcons.FileTypes.Text
            
            val labelPanel = JPanel(FlowLayout(FlowLayout.LEFT)).apply {
                isOpaque = false
                add(JBLabel(fileName, icon, SwingConstants.LEFT).apply {
                    font = JBFont.label().asBold()
                })
                add(JBLabel(filePath).apply {
                    foreground = JBColor.GRAY
                    font = JBFont.small()
                })
            }
            
            add(labelPanel, BorderLayout.CENTER)
            add(JSeparator(), BorderLayout.SOUTH)
        }
    }

    /**
     * Create a comment thread panel
     */
    private fun createThreadPanel(thread: CommentThread): JComponent {
        val cardPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
            border = CompoundBorder(
                JBUI.Borders.customLine(JBColor.border(), 1),
                JBUI.Borders.empty(10)
            )
        }
        
        // Header: Status and Line Info
        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyBottom(8)
            
            // Left: Status Badge
            val statusColor = when {
                thread.isDeleted == true -> JBColor.GRAY
                thread.status == ThreadStatus.Active -> JBColor.ORANGE
                thread.status == ThreadStatus.Fixed || thread.status == ThreadStatus.Closed -> JBColor.GREEN.darker()
                else -> JBColor.BLUE
            }
            
            val statusText = when {
                thread.isDeleted == true -> "Deleted"
                thread.status == ThreadStatus.Active -> "Active"
                thread.status == ThreadStatus.Fixed || thread.status == ThreadStatus.Closed -> "Resolved"
                else -> thread.status?.getDisplayName() ?: "Unknown"
            }
            
            val statusLabel = JBLabel(statusText).apply {
                foreground = statusColor
                font = JBFont.small().asBold()
                icon = if (statusText == "Resolved") AllIcons.RunConfigurations.TestPassed else AllIcons.General.Balloon
            }
            
            // Right: Line Info
            val lineText = thread.threadContext?.let { context ->
                 val line = context.rightFileStart?.line ?: context.leftFileStart?.line
                 if (line != null) "Line $line" else null
            }
            
            val navPanel = JPanel(FlowLayout(FlowLayout.RIGHT)).apply {
                isOpaque = false
                if (lineText != null) {
                    add(JBLabel(lineText).apply {
                        font = JBFont.small()
                        foreground = JBColor.GRAY
                    })
                }
            }
            
            add(statusLabel, BorderLayout.WEST)
            add(navPanel, BorderLayout.EAST)
        }
        
        cardPanel.add(headerPanel)
        cardPanel.add(JSeparator())
        cardPanel.add(Box.createVerticalStrut(8))
        
        // Comments
        val comments = thread.comments ?: emptyList()
        comments.forEachIndexed { index, comment ->
            val isFirst = index == 0
            val authorName = comment.author?.displayName ?: "Unknown"
            val content = comment.content ?: ""
            val date = comment.publishedDate?.let { dateStr ->
                try {
                    // Try to make it more readable or just use substring
                     dateStr.replace("T", " ").substringBeforeLast('.')
                } catch (e: Exception) { dateStr }
            } ?: ""
            
            val commentBlock = JPanel(BorderLayout()).apply {
                isOpaque = false
                border = JBUI.Borders.empty(5, 0)
            }
            
            // Author Line
            val authorPanel = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
                isOpaque = false
                add(JBLabel(authorName, AllIcons.General.User, SwingConstants.LEFT).apply {
                    font = JBFont.label().asBold()
                })
                add(Box.createHorizontalStrut(8))
                add(JBLabel(date).apply {
                    font = JBFont.small()
                    foreground = JBColor.GRAY
                })
            }
            
            // Content
            val contentArea = JTextArea(content).apply {
                lineWrap = true
                wrapStyleWord = true
                isEditable = false
                isOpaque = false
                background = UIUtil.TRANSPARENT_COLOR
                font = JBFont.regular()
                border = JBUI.Borders.emptyTop(4)
            }
            
            commentBlock.add(authorPanel, BorderLayout.NORTH)
            commentBlock.add(contentArea, BorderLayout.CENTER)
            
            if (!isFirst) {
                // Indent replies
                val indentedPanel = JPanel(BorderLayout()).apply {
                    isOpaque = false
                    border = JBUI.Borders.emptyLeft(15) // Indent
                    add(commentBlock, BorderLayout.CENTER)
                }
                
                // Add a small separator before reply
                cardPanel.add(JSeparator())
                cardPanel.add(indentedPanel)
            } else {
                cardPanel.add(commentBlock)
            }
        }
        
        return cardPanel
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
