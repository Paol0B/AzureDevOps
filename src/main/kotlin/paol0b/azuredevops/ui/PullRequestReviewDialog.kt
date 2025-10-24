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
 * Dialog per la review completa di una PR con lista file e diff viewer
 */
class PullRequestReviewDialog(
    private val project: Project,
    private val pullRequest: PullRequest,
    private val fileChanges: List<PullRequestChange>
) : DialogWrapper(project, true) {

    private val logger = Logger.getInstance(PullRequestReviewDialog::class.java)
    private val fileList: JBList<FileChangeItem>
    private var currentFileIndex = 0

    init {
        title = "Review PR #${pullRequest.pullRequestId}: ${pullRequest.title}"
        
        // Crea gli item per la lista
        val items = fileChanges.mapIndexed { index, change ->
            FileChangeItem(
                index = index,
                path = change.item?.path ?: "Unknown",
                changeType = change.changeType ?: "unknown",
                change = change
            )
        }
        
        fileList = JBList(items).apply {
            cellRenderer = FileChangeListRenderer()
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            
            addListSelectionListener { e ->
                if (!e.valueIsAdjusting) {
                    val selected = selectedValue
                    if (selected != null) {
                        currentFileIndex = selected.index
                        openFileDiff(selected.change)
                    }
                }
            }
        }
        
        init()
        
        // Seleziona il primo file
        if (items.isNotEmpty()) {
            fileList.selectedIndex = 0
        }
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        
        // Lista file a sinistra
        val leftPanel = JPanel(BorderLayout()).apply {
            preferredSize = Dimension(300, 500)
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 0, 1)
        }
        
        val titleLabel = JBLabel("Files Changed (${fileChanges.size})").apply {
            border = JBUI.Borders.empty(5, 10)
            font = font.deriveFont(font.style or java.awt.Font.BOLD)
        }
        leftPanel.add(titleLabel, BorderLayout.NORTH)
        leftPanel.add(JBScrollPane(fileList), BorderLayout.CENTER)
        
        panel.add(leftPanel, BorderLayout.WEST)
        
        // Pannello centrale con info
        val centerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
        }
        
        val infoLabel = JBLabel(
            "<html><b>Review Mode</b><br><br>" +
                    "1. Seleziona un file dalla lista a sinistra<br>" +
                    "2. Il diff verrà aperto in una finestra separata<br>" +
                    "3. Puoi vedere e aggiungere commenti nel diff<br><br>" +
                    "<i>Usa i pulsanti Next/Previous per navigare</i></html>"
        )
        centerPanel.add(infoLabel, BorderLayout.NORTH)
        
        panel.add(centerPanel, BorderLayout.CENTER)
        
        panel.preferredSize = Dimension(800, 500)
        
        return panel
    }

    override fun createActions(): Array<Action> {
        return arrayOf(
            PreviousFileAction(),
            NextFileAction(),
            okAction
        )
    }

    /**
     * Apre il diff per un file
     */
    private fun openFileDiff(change: PullRequestChange) {
        val path = change.item?.path ?: return
        
        logger.info("Opening diff for: $path")
        
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                
                // Ottieni contenuto nuovo (source commit)
                val sourceCommit = pullRequest.lastMergeSourceCommit?.commitId
                val newContent = if (sourceCommit != null && change.changeType?.lowercase() != "delete") {
                    apiClient.getFileContent(sourceCommit, path)
                } else ""
                
                // Ottieni contenuto vecchio (target commit)
                val targetCommit = pullRequest.lastMergeTargetCommit?.commitId
                val oldContent = if (targetCommit != null && change.changeType?.lowercase() != "add") {
                    try {
                        apiClient.getFileContent(targetCommit, path)
                    } catch (e: Exception) {
                        logger.info("File is new (doesn't exist in base): ${e.message}")
                        ""
                    }
                } else ""
                
                // Apri diff nella UI thread
                ApplicationManager.getApplication().invokeLater {
                    showDiffViewer(path, oldContent, newContent)
                }
                
            } catch (e: Exception) {
                logger.error("Failed to load file diff", e)
            }
        }
    }

    /**
     * Mostra il diff viewer
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
     * Action per navigare al file precedente
     */
    private inner class PreviousFileAction : AbstractAction("< Previous") {
        init {
            putValue(Action.MNEMONIC_KEY, 'P'.code)
        }
        
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
            if (currentFileIndex > 0) {
                currentFileIndex--
                fileList.selectedIndex = currentFileIndex
            }
        }
    }

    /**
     * Action per navigare al file successivo
     */
    private inner class NextFileAction : AbstractAction("Next >") {
        init {
            putValue(Action.MNEMONIC_KEY, 'N'.code)
        }
        
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
            if (currentFileIndex < fileChanges.size - 1) {
                currentFileIndex++
                fileList.selectedIndex = currentFileIndex
            }
        }
    }

    /**
     * Item della lista file
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

    /**
     * Renderer per la lista file con icone colorate
     */
    private class FileChangeListRenderer : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>?,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): java.awt.Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
            
            if (value is FileChangeItem) {
                val changeIcon = when (value.changeType.lowercase()) {
                    "add" -> "+ "
                    "edit" -> "~ "
                    "delete" -> "- "
                    "rename" -> "→ "
                    else -> "• "
                }
                
                val changeColor = when (value.changeType.lowercase()) {
                    "add" -> "<font color='#00AA00'>"
                    "edit" -> "<font color='#0066CC'>"
                    "delete" -> "<font color='#AA0000'>"
                    else -> "<font color='#888888'>"
                }
                
                text = "<html><b>$changeColor$changeIcon</font></b>${value.fileName}" +
                        if (value.folderPath.isNotEmpty()) "<br><small><font color='#888888'>${value.folderPath}</font></small>" else ""
                
                border = JBUI.Borders.empty(5, 10)
            }
            
            return component
        }
    }
}
