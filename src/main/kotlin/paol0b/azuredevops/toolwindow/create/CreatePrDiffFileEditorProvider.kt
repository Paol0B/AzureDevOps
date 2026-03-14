package paol0b.azuredevops.toolwindow.create

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Provider that creates a [CreatePrDiffFileEditor] for [CreatePrDiffVirtualFile] instances.
 */
class CreatePrDiffFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file is CreatePrDiffVirtualFile
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return CreatePrDiffFileEditor(project, file as CreatePrDiffVirtualFile)
    }

    override fun getEditorTypeId(): String = "azuredevops-create-pr-diff"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
