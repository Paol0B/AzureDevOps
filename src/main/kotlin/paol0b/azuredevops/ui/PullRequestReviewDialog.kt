package paol0b.azuredevops.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.model.PullRequestChange
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/**
 * Dialog for a complete PR review with file list and diff viewer
 */
class PullRequestReviewDialog(
    private val project: Project,
    private val pullRequest: PullRequest,
    private val fileChanges: List<PullRequestChange>
) : DialogWrapper(project, true) {

    private val logger = Logger.getInstance(PullRequestReviewDialog::class.java)
    private val fileListPanel: JPanel
    private val selectedFiles = mutableSetOf<Int>() // Indices of selected files
    private var currentFileIndex = 0

    init {
        title = "Review PR #${pullRequest.pullRequestId}: ${pullRequest.title}"

        // Create the panel with a checkbox for each file
        fileListPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(5)
        }
        
        // Add checkbox for each file
        fileChanges.forEachIndexed { index, change ->
            val checkbox = JCheckBox().apply {
                isSelected = true // All selected by default
                addActionListener {
                    if (isSelected) {
                        selectedFiles.add(index)
                    } else {
                        selectedFiles.remove(index)
                    }
                }
            }
            
            val fileItem = FileChangeItem(
                index = index,
                path = change.item?.path ?: "Unknown",
                changeType = change.changeType ?: "unknown",
                change = change
            )
            
            val fileLabel = JLabel().apply {
                val changeIcon = when (fileItem.changeType.lowercase()) {
                    "add" -> "+ "
                    "edit" -> "~ "
                    "delete" -> "- "
                    "rename" -> "→ "
                    else -> "• "
                }
                
                val changeColor = when (fileItem.changeType.lowercase()) {
                    "add" -> "<font color='#00AA00'>"
                    "edit" -> "<font color='#0066CC'>"
                    "delete" -> "<font color='#AA0000'>"
                    else -> "<font color='#888888'>"
                }
                
                text = "<html><b>$changeColor$changeIcon</font></b>${fileItem.fileName}" +
                        if (fileItem.folderPath.isNotEmpty()) 
                            "<br><small><font color='#888888'>${fileItem.folderPath}</font></small>" 
                        else ""
                
                cursor = java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                
                addMouseListener(object : java.awt.event.MouseAdapter() {
                    override fun mouseClicked(e: java.awt.event.MouseEvent?) {
                        openFileDiff(fileItem.change)
                    }
                })
            }
            
            val itemPanel = JPanel(BorderLayout()).apply {
                add(checkbox, BorderLayout.WEST)
                add(fileLabel, BorderLayout.CENTER)
                border = JBUI.Borders.empty(3, 5)
                maximumSize = Dimension(Int.MAX_VALUE, 60)
            }
            
            fileListPanel.add(itemPanel)
            selectedFiles.add(index) // All selected by default
        }
        
        init()
        
        // No initial automatic selection - user clicks the file
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        
        // File list on the left with checkboxes
        val leftPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(350, 500)
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 0, 1)
        }
        
        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(5, 10)
        }
        
        val titleLabel = JBLabel("Files Changed (${fileChanges.size})").apply {
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
        }
        headerPanel.add(titleLabel, BorderLayout.WEST)
        
        // Buttons Select All / Deselect All
        val buttonsPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            
            val selectAllBtn = JButton("All").apply {
                toolTipText = "Select all files"
                addActionListener {
                    fileListPanel.components.forEach { comp ->
                        if (comp is JPanel) {
                            val checkbox = comp.components.firstOrNull { it is JCheckBox } as? JCheckBox
                            checkbox?.isSelected = true
                        }
                    }
                }
            }
            
            val deselectAllBtn = JButton("None").apply {
                toolTipText = "Deselect all files"
                addActionListener {
                    fileListPanel.components.forEach { comp ->
                        if (comp is JPanel) {
                            val checkbox = comp.components.firstOrNull { it is JCheckBox } as? JCheckBox
                            checkbox?.isSelected = false
                        }
                    }
                }
            }
            
            add(selectAllBtn)
            add(Box.createHorizontalStrut(5))
            add(deselectAllBtn)
        }
        headerPanel.add(buttonsPanel, BorderLayout.EAST)
        
        leftPanel.add(headerPanel, BorderLayout.NORTH)
        leftPanel.add(JBScrollPane(fileListPanel), BorderLayout.CENTER)
        
        panel.add(leftPanel, BorderLayout.WEST)
        
        // Central panel with info
        val centerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
        }
        
        val infoLabel = JBLabel(
            "<html><b>Review Mode</b><br><br>" +
                    "1. <b>Select files</b> with the checkboxes (selected by default)<br>" +
                    "2. <b>Click the file name</b> to open the diff<br>" +
                    "3. Use the <b>'Show Combined Diff'</b> button to see all selected files<br>" +
                    "4. Use 'All'/'None' to select/deselect all<br><br>" +
                    "<i>Only files with a selected checkbox will be included in the combined diff</i></html>"
        )
        centerPanel.add(infoLabel, BorderLayout.NORTH)
        
        panel.add(centerPanel, BorderLayout.CENTER)
        
        panel.preferredSize = Dimension(800, 500)
        
        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(
            ShowCombinedDiffAction(),
            okAction
        )
    }
    
    /**
     * Action to show the combined diff of selected files
     */
    private inner class ShowCombinedDiffAction : AbstractAction("Show Combined Diff") {
        init {
            putValue(Action.MNEMONIC_KEY, 'D'.code)
        }
        
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
            if (selectedFiles.isEmpty()) {
                JOptionPane.showMessageDialog(
                    contentPane,
                    "Select at least one file with the checkbox",
                    "No File Selected",
                    JOptionPane.WARNING_MESSAGE
                )
                return
            }
            // Open the diffs of the selected files in sequence
            val selectedChanges = selectedFiles.sorted().map { fileChanges[it] }
            selectedChanges.forEach { change ->
                openFileDiff(change)
            }
        }
    }

    /**
     * Opens the diff for a file
     */
    private fun openFileDiff(change: PullRequestChange) {
        val path = change.item?.path ?: return
        
        logger.info("Opening diff for: $path")
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                
                // Get new content (source commit)
                val sourceCommit = pullRequest.lastMergeSourceCommit?.commitId
                val newContent = if (sourceCommit != null && change.changeType?.lowercase() != "delete") {
                    apiClient.getFileContent(sourceCommit, path)
                } else ""
                
                // Get old content (target commit)
                val targetCommit = pullRequest.lastMergeTargetCommit?.commitId
                val oldContent = if (targetCommit != null && change.changeType?.lowercase() != "add") {
                    try {
                        apiClient.getFileContent(targetCommit, path)
                    } catch (e: Exception) {
                        logger.info("File is new (doesn't exist in base): ${e.message}")
                        ""
                    }
                } else ""
                
                // Open diff in the UI thread
                ApplicationManager.getApplication().invokeLater {
                    showDiffViewer(path, oldContent, newContent)
                }
                
            } catch (e: Exception) {
                logger.error("Failed to load file diff", e)
            }
        }
    }

    /**
     * Shows the diff viewer
     */
    private fun showDiffViewer(filePath: String, oldContent: String, newContent: String) {
        val contentFactory = DiffContentFactory.getInstance()
        val fileTypeManager = FileTypeManager.getInstance()
        
        val fileName = filePath.substringAfterLast('/')
        val fileType = fileTypeManager.getFileTypeByFileName(fileName)
        
        val oldDiffContent = contentFactory.create(oldContent, fileType)
        val newDiffContent = contentFactory.create(newContent, fileType)
        
        val diffRequest = SimpleDiffRequest(
            "PR #${pullRequest.pullRequestId}: $fileName",
            oldDiffContent,
            newDiffContent,
            "Base (${pullRequest.targetRefName?.substringAfterLast('/')})",
            "Changes (${pullRequest.sourceRefName?.substringAfterLast('/')})"
        )
        
        DiffManager.getInstance().showDiff(project, diffRequest)
    }

    /**
     * File list item
     */
    data class FileChangeItem(
        val index: Int,
        val path: String,
        val changeType: String,
        val change: PullRequestChange
    ) {
        val fileName: String = path.substringAfterLast('/')
        val folderPath: String = path.substringBeforeLast('/', "")
    }
}
