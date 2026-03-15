package paol0b.azuredevops.toolwindow.workitem.board

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Provider that creates a [WorkItemBoardFileEditor] for [WorkItemBoardVirtualFile] instances.
 */
class WorkItemBoardFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file is WorkItemBoardVirtualFile
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return WorkItemBoardFileEditor(project, file as WorkItemBoardVirtualFile)
    }

    override fun getEditorTypeId(): String = "azuredevops-workitem-board"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
