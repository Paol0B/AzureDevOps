package paol0b.azuredevops.toolwindow.pipeline.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile

/**
 * Provider that creates a [PipelineLogFileEditor] for [PipelineLogVirtualFile] instances.
 */
class PipelineLogFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file is PipelineLogVirtualFile
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return PipelineLogFileEditor(project, file as PipelineLogVirtualFile)
    }

    override fun getEditorTypeId(): String = "azuredevops-pipeline-log"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
