package paol0b.azuredevops.toolwindow.pipeline.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.PipelineTabService
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer

/**
 * Editor that displays the full log output of a single build task.
 * Uses IntelliJ's EditorFactory for proper monospaced rendering.
 *
 * Features:
 * - Initial load with loading placeholder
 * - Real-time delta log streaming (appends only new lines)
 * - Exponential backoff retry on failure
 * - Hash-based change detection to avoid flicker
 */
class PipelineLogFileEditor(
    private val project: Project,
    private val file: PipelineLogVirtualFile
) : UserDataHolderBase(), FileEditor {

    private val logger = Logger.getInstance(PipelineLogFileEditor::class.java)
    private val mainPanel: JPanel
    private var editorComponent: com.intellij.openapi.editor.Editor? = null

    // Auto-refresh for live logs
    private var refreshTimer: Timer? = null
    private val REFRESH_INTERVAL = 5_000 // 5 seconds

    // Retry with exponential backoff
    private var retryCount: Int = 0
    private var retryTimer: Timer? = null
    private val MAX_RETRY_DELAY = 30_000

    // Track current line count for delta fetching
    private var currentLineCount: Int = 0
    private var lastContentHash: Int = 0
    private var isInitialLoadDone: Boolean = false
    private var isDisposed: Boolean = false

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
        if (isDisposed) return
        ApplicationManager.getApplication().executeOnPooledThread {
            if (isDisposed) return@executeOnPooledThread
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val logText = apiClient.getBuildLogText(file.buildId, file.logId)
                val normalizedLogText = StringUtil.convertLineSeparators(logText)
                val contentHash = normalizedLogText.hashCode()

                ApplicationManager.getApplication().invokeLater {
                    if (isDisposed) return@invokeLater
                    retryCount = 0
                    retryTimer?.stop()
                    retryTimer = null

                    // Remove loading placeholder
                    if (mainPanel.componentCount > 1) {
                        mainPanel.remove(mainPanel.getComponent(1))
                    }

                    // Create read-only editor with monospaced font
                    val document = EditorFactory.getInstance().createDocument(normalizedLogText)
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
                    currentLineCount = document.lineCount
                    lastContentHash = contentHash
                    isInitialLoadDone = true

                    // Save line count to service for other consumers
                    PipelineTabService.getInstance(project).setLogLineCount(file.buildId, file.logId, currentLineCount)

                    mainPanel.add(editor.component, BorderLayout.CENTER)
                    mainPanel.revalidate()
                    mainPanel.repaint()

                    // Scroll to end
                    scrollToEnd(editor)

                    // Start auto-refresh for live log streaming
                    startAutoRefresh()
                }
            } catch (e: Exception) {
                logger.error("Failed to load log", e)
                ApplicationManager.getApplication().invokeLater {
                    if (isDisposed) return@invokeLater
                    if (!isInitialLoadDone) {
                        if (mainPanel.componentCount > 1) {
                            mainPanel.remove(mainPanel.getComponent(1))
                        }
                        mainPanel.add(JBLabel("Failed to load log: ${e.message}").apply {
                            icon = AllIcons.General.Error
                            foreground = JBColor.RED
                            border = JBUI.Borders.empty(20)
                            horizontalAlignment = JBLabel.CENTER
                        }, BorderLayout.CENTER)
                        mainPanel.revalidate()
                        mainPanel.repaint()
                    }
                    scheduleRetry { loadLog() }
                }
            }
        }
    }

    /**
     * Fetches only new log lines since [currentLineCount] and appends them.
     */
    private fun fetchDeltaAndAppend() {
        if (isDisposed || !isInitialLoadDone) return
        val editor = editorComponent ?: return

        ApplicationManager.getApplication().executeOnPooledThread {
            if (isDisposed) return@executeOnPooledThread
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                // Azure DevOps log API uses 1-based line numbers for startLine
                val deltaText = apiClient.getBuildLogTextFromLine(file.buildId, file.logId, currentLineCount + 1)
                val normalizedDelta = StringUtil.convertLineSeparators(deltaText)

                if (normalizedDelta.isBlank()) return@executeOnPooledThread

                val deltaHash = normalizedDelta.hashCode()
                if (deltaHash == 0) return@executeOnPooledThread

                ApplicationManager.getApplication().invokeLater {
                    if (isDisposed) return@invokeLater
                    retryCount = 0
                    retryTimer?.stop()
                    retryTimer = null

                    val document = editor.document
                    val wasAtEnd = isScrolledToEnd(editor)

                    // Append new lines to existing document
                    WriteCommandAction.runWriteCommandAction(project) {
                        val textToAppend = if (document.textLength > 0 && !normalizedDelta.startsWith("\n")) {
                            "\n$normalizedDelta"
                        } else {
                            normalizedDelta
                        }
                        document.insertString(document.textLength, textToAppend)
                    }

                    val newLineCount = document.lineCount
                    currentLineCount = newLineCount
                    PipelineTabService.getInstance(project).setLogLineCount(file.buildId, file.logId, newLineCount)

                    // Auto-scroll to end if user was at the bottom
                    if (wasAtEnd) {
                        scrollToEnd(editor)
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to fetch log delta: ${e.message}")
                scheduleRetry { fetchDeltaAndAppend() }
            }
        }
    }

    // ========================
    //  Auto-refresh
    // ========================

    private fun startAutoRefresh() {
        if (refreshTimer != null) return
        refreshTimer = Timer(REFRESH_INTERVAL) {
            if (!isDisposed) {
                fetchDeltaAndAppend()
            }
        }.apply {
            isRepeats = true
            start()
        }
    }

    private fun scheduleRetry(action: () -> Unit) {
        if (isDisposed || retryTimer != null) return
        val delay = minOf((1000L * (1 shl retryCount)).toInt(), MAX_RETRY_DELAY)
        retryCount++
        logger.info("Scheduling log retry in ${delay}ms (attempt $retryCount)")
        retryTimer = Timer(delay) {
            retryTimer = null
            if (!isDisposed) action()
        }.apply {
            isRepeats = false
            start()
        }
    }

    // ========================
    //  Scroll helpers
    // ========================

    private fun scrollToEnd(editor: com.intellij.openapi.editor.Editor) {
        val offset = editor.document.textLength
        editor.caretModel.moveToOffset(offset)
        editor.scrollingModel.scrollToCaret(com.intellij.openapi.editor.ScrollType.MAKE_VISIBLE)
    }

    private fun isScrolledToEnd(editor: com.intellij.openapi.editor.Editor): Boolean {
        val scrollingModel = editor.scrollingModel
        val visibleArea = scrollingModel.visibleArea
        val contentHeight = editor.contentComponent.height
        return (visibleArea.y + visibleArea.height) >= (contentHeight - 50)
    }

    // ========================
    //  FileEditor interface
    // ========================

    override fun getComponent(): JComponent = mainPanel
    override fun getPreferredFocusedComponent(): JComponent? = editorComponent?.contentComponent
    override fun getName(): String = file.taskName
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = !isDisposed
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun getFile() = file

    override fun dispose() {
        isDisposed = true
        refreshTimer?.stop()
        refreshTimer = null
        retryTimer?.stop()
        retryTimer = null
        editorComponent?.let { EditorFactory.getInstance().releaseEditor(it) }
        editorComponent = null
        PipelineTabService.getInstance(project).unregisterFile(file)
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}
