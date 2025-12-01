package paol0b.azuredevops.toolwindow

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.GitRepositoryService
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import javax.swing.*

/**
 * Tool Window to navigate all comments of the active PR
 * Allows scrolling through comments and automatically opening files
 */
class CommentsNavigatorToolWindow : ToolWindowFactory {

    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val content = toolWindow.contentManager.factory.createContent(
            CommentsNavigatorPanel(project),
            "",
            false
        )
        toolWindow.contentManager.addContent(content)
    }

    override fun shouldBeAvailable(project: Project): Boolean = true
}

/**
 * Main panel of the tool window
 */
class CommentsNavigatorPanel(private val project: Project) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(CommentsNavigatorPanel::class.java)
    private val commentsList: JBList<CommentItem>
    private val statusLabel: JLabel
    private var currentPullRequest: PullRequest? = null
    private var allComments: List<CommentItem> = emptyList()

    init {
        // Header with info and refresh button
        val headerPanel = createHeaderPanel()
        add(headerPanel, BorderLayout.NORTH)

        // List of comments
        commentsList = JBList<CommentItem>().apply {
            cellRenderer = CommentItemRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    selectedValue?.let { navigateToComment(it) }
                }
            }
        }

        val scrollPane = JBScrollPane(commentsList)
        add(scrollPane, BorderLayout.CENTER)

        // Status bar at the bottom
        statusLabel = JLabel("No active Pull Request")
        statusLabel.border = JBUI.Borders.empty(5)
        add(statusLabel, BorderLayout.SOUTH)

        // Automatically load comments
        loadComments()
    }

    private fun createHeaderPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        panel.border = JBUI.Borders.empty(5)

        val titleLabel = JLabel("PR Comments Navigator")
        titleLabel.font = titleLabel.font.deriveFont(14f).deriveFont(java.awt.Font.BOLD)

        val buttonsPanel = JPanel()
        
        val refreshButton = JButton("Refresh", AllIcons.Actions.Refresh)
        refreshButton.addActionListener { 
            // Manual refresh + restart polling
            val pollingService = paol0b.azuredevops.services.CommentsPollingService.getInstance(project)
            pollingService.refreshNow()
            loadComments()
        }
        
        val autoRefreshLabel = JLabel("Auto-refresh: ON (30s)")
        autoRefreshLabel.foreground = JBColor.GRAY
        autoRefreshLabel.font = autoRefreshLabel.font.deriveFont(10f)
        
        buttonsPanel.add(autoRefreshLabel)
        buttonsPanel.add(Box.createHorizontalStrut(10))
        buttonsPanel.add(refreshButton)

        panel.add(titleLabel, BorderLayout.WEST)
        panel.add(buttonsPanel, BorderLayout.EAST)

        return panel
    }

    private fun loadComments() {
        statusLabel.text = "Loading comments..."
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                // Find PR for current branch
                val gitService = GitRepositoryService.getInstance(project)
                val currentBranch = gitService.getCurrentBranch()
                
                if (currentBranch == null) {
                    updateStatus("No active branch")
                    return@executeOnPooledThread
                }

                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val pullRequest = apiClient.findPullRequestForBranch(currentBranch.displayName)

                if (pullRequest == null) {
                    updateStatus("No PR found for branch ${currentBranch.displayName}")
                    return@executeOnPooledThread
                }

                currentPullRequest = pullRequest
                
                // Load all comment threads
                val threads = apiClient.getCommentThreads(pullRequest.pullRequestId)
                
                // Group comments: first those in files, then general ones
                val commentItems = threads
                    .sortedWith(compareBy(
                        { it.getFilePath() == null }, // General comments at the end
                        { it.getFilePath() },
                        { it.getRightFileStart() ?: 0 }
                    ))
                    .map { thread ->
                        CommentItem(
                            thread = thread,
                            filePath = thread.getFilePath() ?: "[General PR comment]",
                            line = thread.getRightFileStart() ?: 0,
                            pullRequest = pullRequest,
                            isGeneralComment = thread.getFilePath() == null
                        )
                    }

                allComments = commentItems

                ApplicationManager.getApplication().invokeLater {
                    val model = DefaultListModel<CommentItem>()
                    commentItems.forEach { model.addElement(it) }
                    commentsList.model = model

                    val fileComments = commentItems.count { !it.isGeneralComment }
                    val generalComments = commentItems.count { it.isGeneralComment }
                    val statusText = buildString {
                        append("PR #${pullRequest.pullRequestId}: ")
                        if (fileComments > 0) append("$fileComments file comments")
                        if (generalComments > 0) {
                            if (fileComments > 0) append(", ")
                            append("$generalComments general comments")
                        }
                    }
                    updateStatus(statusText)
                }

            } catch (e: Exception) {
                logger.error("Failed to load comments", e)
                updateStatus("Error: ${e.message}")
            }
        }
    }

    private fun navigateToComment(item: CommentItem) {
        logger.info("Navigating to comment: ${item.filePath}:${item.line}")
        
        // If it's a general comment, just open the dialog
        if (item.isGeneralComment) {
            val dialog = paol0b.azuredevops.ui.CommentThreadDialog(
                project,
                item.thread,
                item.pullRequest,
                paol0b.azuredevops.services.PullRequestCommentsService.getInstance(project)
            )
            dialog.show()
            return
        }
        
        // Find the file in the project
        val projectBasePath = project.basePath ?: return
        val fullPath = "$projectBasePath/${item.filePath.trimStart('/')}"
        
        val virtualFile = com.intellij.openapi.vfs.LocalFileSystem.getInstance()
            .findFileByPath(fullPath)
        
        if (virtualFile == null) {
            logger.warn("File not found: $fullPath")
            JOptionPane.showMessageDialog(
                this,
                "File not found: ${item.filePath}",
                "Navigation Error",
                JOptionPane.WARNING_MESSAGE
            )
            return
        }

        ApplicationManager.getApplication().invokeLater {
            // Open the file and go to the comment line
            val descriptor = OpenFileDescriptor(project, virtualFile, item.line - 1, 0)
            val editor = FileEditorManager.getInstance(project).openTextEditor(descriptor, true)
            
            if (editor != null) {
                // Scroll to the line
                editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.CENTER)
            }
        }
    }

    private fun updateStatus(message: String) {
        ApplicationManager.getApplication().invokeLater {
            statusLabel.text = message
        }
    }
}

/**
 * List item: represents a comment
 */
data class CommentItem(
    val thread: CommentThread,
    val filePath: String,
    val line: Int,
    val pullRequest: PullRequest,
    val isGeneralComment: Boolean = false
) {
    val fileName: String = if (isGeneralComment) filePath else filePath.substringAfterLast('/')
    val author: String = thread.comments?.firstOrNull()?.author?.displayName ?: "Unknown"
    val content: String = thread.comments?.firstOrNull()?.content?.take(100) ?: ""
    val isResolved: Boolean = thread.isResolved()
    val commentCount: Int = thread.comments?.size ?: 0
}

/**
 * Custom renderer for list items
 */
class CommentItemRenderer : DefaultListCellRenderer() {
    
    override fun getListCellRendererComponent(
        list: JList<*>?,
        value: Any?,
        index: Int,
        isSelected: Boolean,
        cellHasFocus: Boolean
    ): Component {
        super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
        
        if (value is CommentItem) {
            val statusIcon = if (value.isResolved) "✓" else "💬"
            val statusColor = if (value.isResolved) "gray" else "orange"
            val repliesText = if (value.commentCount > 1) " (+${value.commentCount - 1})" else ""
            val locationText = if (value.isGeneralComment) {
                "<b><font color='blue'>📋 ${value.fileName}</font></b>"
            } else {
                "<b><font color='$statusColor'>$statusIcon</font> ${value.fileName}:${value.line}</b>"
            }
            
            text = """
                <html>
                <div style='padding: 5px;'>
                    $locationText$repliesText<br>
                    <font color='gray' size='-1'>${value.author}: ${value.content}</font>
                </div>
                </html>
            """.trimIndent()
            
            // Different background color for resolved/active/general
            if (!isSelected) {
                background = when {
                    value.isGeneralComment && !value.isResolved -> JBColor(
                        java.awt.Color(240, 248, 255),
                        java.awt.Color(45, 55, 70)
                    )
                    value.isResolved -> list?.background
                    else -> JBColor(
                        java.awt.Color(255, 250, 240),
                        java.awt.Color(60, 55, 45)
                    )
                }
            }
            
            border = JBUI.Borders.empty(2, 5)
        }
        
        return this
    }
    
    override fun getPreferredSize(): Dimension {
        val size = super.getPreferredSize()
        return Dimension(size.width, 60)
    }
}
