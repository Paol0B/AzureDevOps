package paol0b.azuredevops.toolwindow.pipeline.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.PipelineTabService
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Editor that displays the full log output of a single build task.
 * Uses IntelliJ's EditorFactory for proper monospaced rendering.
 */
class PipelineLogFileEditor(
    private val project: Project,
    private val file: PipelineLogVirtualFile
) : UserDataHolderBase(), FileEditor {

    private val logger = Logger.getInstance(PipelineLogFileEditor::class.java)
    private val mainPanel: JPanel
    private var editorComponent: com.intellij.openapi.editor.Editor? = null

    init {
        mainPanel = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
        }

        // Header
        val headerPanel = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(8, 12)
        }
        headerPanel.add(JBLabel("<html><b>${escapeHtml(file.taskName)}</b> — Build #${file.buildId}, Log #${file.logId}</html>").apply {
            icon = AllIcons.FileTypes.Text
        }, BorderLayout.CENTER)
        mainPanel.add(headerPanel, BorderLayout.NORTH)

        // Loading placeholder
        val loadingLabel = JBLabel("Loading log...").apply {
            icon = AllIcons.Process.Step_1
            foreground = JBColor.GRAY
            border = JBUI.Borders.empty(20)
            horizontalAlignment = JBLabel.CENTER
        }
        mainPanel.add(loadingLabel, BorderLayout.CENTER)

        // Load log asynchronously
        loadLog()
    }

    private fun loadLog() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val logText = apiClient.getBuildLogText(file.buildId, file.logId)

                ApplicationManager.getApplication().invokeLater {
                    // Remove loading placeholder
                    mainPanel.remove(mainPanel.getComponent(1))

                    // Create read-only editor with monospaced font
                    val document = EditorFactory.getInstance().createDocument(logText)
                    val editor = EditorFactory.getInstance().createViewer(document, project)

                    (editor as? EditorEx)?.let { editorEx ->
                        editorEx.settings.apply {
                            isLineNumbersShown = true
                            isFoldingOutlineShown = false
                            isRightMarginShown = false
                            additionalLinesCount = 0
                            isUseSoftWraps = true
                        }
                        editorEx.colorsScheme = EditorColorsManager.getInstance().globalScheme
                    }

                    editorComponent = editor
                    mainPanel.add(editor.component, BorderLayout.CENTER)
                    mainPanel.revalidate()
                    mainPanel.repaint()
                }
            } catch (e: Exception) {
                logger.error("Failed to load log", e)
                ApplicationManager.getApplication().invokeLater {
                    mainPanel.remove(mainPanel.getComponent(1))
                    mainPanel.add(JBLabel("Failed to load log: ${e.message}").apply {
                        icon = AllIcons.General.Error
                        foreground = JBColor.RED
                        border = JBUI.Borders.empty(20)
                        horizontalAlignment = JBLabel.CENTER
                    }, BorderLayout.CENTER)
                    mainPanel.revalidate()
                    mainPanel.repaint()
                }
            }
        }
    }

    override fun getComponent(): JComponent = mainPanel
    override fun getPreferredFocusedComponent(): JComponent? = editorComponent?.contentComponent
    override fun getName(): String = file.taskName
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun getFile() = file

    override fun dispose() {
        editorComponent?.let { EditorFactory.getInstance().releaseEditor(it) }
        editorComponent = null
        PipelineTabService.getInstance(project).unregisterFile(file)
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
