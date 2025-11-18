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
    private val fileListPanel: JPanel
    private val selectedFiles = mutableSetOf<Int>() // Indici dei file selezionati
    private var currentFileIndex = 0

    init {
        title = "Review PR #${pullRequest.pullRequestId}: ${pullRequest.title}"
        
        // Crea il panel con checkbox per ogni file
        fileListPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(5)
        }
        
        // Aggiungi checkbox per ogni file
        fileChanges.forEachIndexed { index, change ->
            val checkbox = JCheckBox().apply {
                isSelected = true // Tutti selezionati di default
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
            selectedFiles.add(index) // Tutti selezionati di default
        }
        
        init()
        
        // Nessuna selezione iniziale automatica - l'utente clicca sul file
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel(BorderLayout())
        
        // Lista file a sinistra con checkbox
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
        
        // Pulsanti Select All / Deselect All
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
        
        // Pannello centrale con info
        val centerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(10)
        }
        
        val infoLabel = JBLabel(
            "<html><b>Review Mode</b><br><br>" +
                    "1. <b>Seleziona i file</b> con le checkbox (selezionati per default)<br>" +
                    "2. <b>Clicca sul nome del file</b> per aprire il diff<br>" +
                    "3. Usa il pulsante <b>'Show Combined Diff'</b> per vedere tutti i file selezionati<br>" +
                    "4. Usa 'All'/'None' per selezionare/deselezionare tutti<br><br>" +
                    "<i>Solo i file con checkbox selezionata saranno inclusi nel diff combinato</i></html>"
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
     * Action per mostrare il diff combinato dei file selezionati
     */
    private inner class ShowCombinedDiffAction : AbstractAction("Show Combined Diff") {
        init {
            putValue(Action.MNEMONIC_KEY, 'D'.code)
        }
        
        override fun actionPerformed(e: java.awt.event.ActionEvent?) {
            if (selectedFiles.isEmpty()) {
                JOptionPane.showMessageDialog(
                    contentPane,
                    "Seleziona almeno un file con la checkbox",
                    "Nessun File Selezionato",
                    JOptionPane.WARNING_MESSAGE
                )
                return
            }
            
            // Apri i diff dei file selezionati in sequenza
            val selectedChanges = selectedFiles.sorted().map { fileChanges[it] }
            selectedChanges.forEach { change ->
                openFileDiff(change)
            }
        }
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
}
