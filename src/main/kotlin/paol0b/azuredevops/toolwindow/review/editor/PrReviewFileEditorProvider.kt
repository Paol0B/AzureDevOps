package paol0b.azuredevops.toolwindow.review.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import paol0b.azuredevops.services.PrReviewTabService

class PrReviewFileEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        return file is PrReviewVirtualFile
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val pullRequest = PrReviewTabService.getInstance(project).getPullRequest(file)
            ?: throw IllegalStateException("Missing pull request data for review tab")
        return PrReviewFileEditor(project, file, pullRequest)
    }

    override fun getEditorTypeId(): String = "azuredevops-pr-review"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}
