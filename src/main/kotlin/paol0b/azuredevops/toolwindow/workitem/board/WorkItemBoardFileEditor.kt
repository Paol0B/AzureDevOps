package paol0b.azuredevops.toolwindow.workitem.board

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import paol0b.azuredevops.services.WorkItemTabService
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * Editor that displays the Work Item Kanban Board as a full editor tab.
 * Provides maximum screen space for the board with drag-and-drop columns.
 */
class WorkItemBoardFileEditor(
    private val project: Project,
    private val file: WorkItemBoardVirtualFile
) : UserDataHolderBase(), FileEditor {

    private val boardPanel = BoardViewPanel(project, file.iterationPath, file.iterationName)

    init {
        boardPanel.refresh()
        boardPanel.startAutoRefresh()
    }

    override fun getComponent(): JComponent = boardPanel
    override fun getPreferredFocusedComponent(): JComponent? = boardPanel
    override fun getName(): String = "Work Item Board"
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun getFile() = file

    override fun dispose() {
        boardPanel.dispose()
        WorkItemTabService.getInstance(project).unregisterFile(file)
    }
}
