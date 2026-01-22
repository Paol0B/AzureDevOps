package paol0b.azuredevops.toolwindow.review

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.PullRequestChange
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.JPanel

/**
 * Diff viewer panel with syntax highlighting and inline comments support
 * Uses JetBrains Diff API for professional diff viewing
 */
class DiffViewerPanel(
    private val project: Project,
    private val pullRequestId: Int
) : JPanel(BorderLayout()), Disposable {

    private val logger = Logger.getInstance(DiffViewerPanel::class.java)
    private val apiClient = AzureDevOpsApiClient.getInstance(project)
    private val diffContentFactory = DiffContentFactory.getInstance()
    
    private var currentDiffPanel: DiffRequestPanel? = null
    private var currentChange: PullRequestChange? = null
    private var cachedPullRequest: paol0b.azuredevops.model.PullRequest? = null
    
    private val placeholderLabel = JBLabel("Select a file to view diff").apply {
        horizontalAlignment = JBLabel.CENTER
        border = JBUI.Borders.empty(20)
    }

    init {
        minimumSize = Dimension(400, 0)
        preferredSize = Dimension(600, 0)
        add(placeholderLabel, BorderLayout.CENTER)
    }

    /**
     * Load and display diff for a file change
     */
    fun loadDiff(change: PullRequestChange) {
        currentChange = change
        
        val filePath = change.item?.path ?: run {
            showError("Invalid file path")
            return
        }
        
        // Clear previous diff panel
        clearDiff()
        
        // Show loading state
        showLoading(filePath)
        
        // Load diff content in background
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val (oldContent, newContent) = fetchFileContents(change)
                
                // Update UI on EDT
                ApplicationManager.getApplication().invokeLater {
                    displayDiff(filePath, oldContent, newContent, change.changeType ?: "edit")
                }
            } catch (e: Exception) {
                logger.error("Failed to load diff for file: $filePath", e)
                ApplicationManager.getApplication().invokeLater {
                    showError("Failed to load diff: ${e.message}")
                }
            }
        }
    }

    /**
     * Fetch file contents (old and new versions)
     */
    private fun fetchFileContents(change: PullRequestChange): Pair<String, String> {
        val filePath = change.item?.path ?: ""
        val changeType = change.changeType?.lowercase() ?: "edit"
        
        // Get PR to access commit IDs (cache it)
        if (cachedPullRequest == null) {
            cachedPullRequest = apiClient.getPullRequest(pullRequestId)
        }
        val pr = cachedPullRequest!!
        
        val sourceCommit = pr.lastMergeSourceCommit?.commitId
        val targetCommit = pr.lastMergeTargetCommit?.commitId
        
        return when (changeType) {
            "add" -> {
                // New file - no old content
                val newContent = if (sourceCommit != null) {
                    try {
                        apiClient.getFileContent(sourceCommit, filePath)
                    } catch (e: Exception) {
                        logger.info("Failed to get new content: ${e.message}")
                        ""
                    }
                } else ""
                "" to newContent
            }
            "delete" -> {
                // Deleted file - no new content
                val oldContent = if (targetCommit != null) {
                    try {
                        apiClient.getFileContent(targetCommit, filePath)
                    } catch (e: Exception) {
                        logger.info("Failed to get old content: ${e.message}")
                        ""
                    }
                } else ""
                oldContent to ""
            }
            "edit", "rename" -> {
                // Modified file - fetch both versions
                val oldPath = change.originalPath ?: filePath
                
                val oldContent = if (targetCommit != null) {
                    try {
                        apiClient.getFileContent(targetCommit, oldPath)
                    } catch (e: Exception) {
                        logger.info("File is new (doesn't exist in base): ${e.message}")
                        ""
                    }
                } else ""
                
                val newContent = if (sourceCommit != null) {
                    try {
                        apiClient.getFileContent(sourceCommit, filePath)
                    } catch (e: Exception) {
                        logger.info("Failed to get new content: ${e.message}")
                        ""
                    }
                } else ""
                
                oldContent to newContent
            }
            else -> {
                logger.warn("Unknown change type: $changeType")
                "" to ""
            }
        }
    }

    /**
     * Display the diff using JetBrains Diff API
     */
    private fun displayDiff(filePath: String, oldContent: String, newContent: String, changeType: String) {
        removeAll()
        
        // Determine file type for syntax highlighting
        val fileName = filePath.substringAfterLast('/')
        val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
        
        // Create diff contents
        val content1 = diffContentFactory.create(project, oldContent, fileType)
        val content2 = diffContentFactory.create(project, newContent, fileType)
        
        // Get branch names for titles
        val pr = cachedPullRequest
        val targetBranch = pr?.targetRefName?.substringAfterLast('/') ?: "Base"
        val sourceBranch = pr?.sourceRefName?.substringAfterLast('/') ?: "Changes"
        
        // Create diff request with appropriate titles
        val diffRequest = SimpleDiffRequest(
            "PR #$pullRequestId: $fileName",
            content1,
            content2,
            "Base ($targetBranch)",
            "Changes ($sourceBranch)"
        )
        
        // Create diff panel
        val diffManager = DiffManager.getInstance()
        currentDiffPanel = diffManager.createRequestPanel(project, this, null).apply {
            setRequest(diffRequest)
        }
        
        add(currentDiffPanel!!.component, BorderLayout.CENTER)
        revalidate()
        repaint()
        
        logger.info("Displayed diff for: $filePath (type: $changeType)")
    }

    /**
     * Show loading state
     */
    private fun showLoading(fileName: String) {
        removeAll()
        val loadingLabel = JBLabel("Loading diff for $fileName...").apply {
            horizontalAlignment = JBLabel.CENTER
            border = JBUI.Borders.empty(20)
        }
        add(loadingLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    /**
     * Show error message
     */
    private fun showError(message: String) {
        removeAll()
        val errorLabel = JBLabel("❌ $message").apply {
            horizontalAlignment = JBLabel.CENTER
            border = JBUI.Borders.empty(20)
        }
        add(errorLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    /**
     * Clear the current diff
     */
    fun clearDiff() {
        currentDiffPanel?.let { panel ->
            Disposer.dispose(panel)
            currentDiffPanel = null
        }
        currentChange = null
        
        removeAll()
        add(placeholderLabel, BorderLayout.CENTER)
        revalidate()
        repaint()
    }

    /**
     * Get the current file change being displayed
     */
    fun getCurrentChange(): PullRequestChange? = currentChange

    /**
     * Cleanup resources (implementation of Disposable interface)
     */
    override fun dispose() {
        clearDiff()
    }
}
