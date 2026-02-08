package paol0b.azuredevops.toolwindow.review.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.PrReviewTabService
import paol0b.azuredevops.toolwindow.review.PrReviewToolWindow
import java.beans.PropertyChangeListener
import javax.swing.JComponent

class PrReviewFileEditor(
    private val project: Project,
    private val file: VirtualFile,
    pullRequest: PullRequest
) : UserDataHolderBase(), FileEditor {

    private val reviewPanel = PrReviewToolWindow(project, showSelector = false).apply {
        openPullRequest(pullRequest)
    }

    private var isDisposed = false

    override fun getComponent(): JComponent = reviewPanel

    override fun getPreferredFocusedComponent(): JComponent? = reviewPanel

    override fun getName(): String = "PR Review"

    override fun getFile(): VirtualFile = file

    override fun setState(state: FileEditorState) {
        // No-op
    }

    override fun isModified(): Boolean = false

    override fun isValid(): Boolean = !isDisposed

    override fun selectNotify() {
        // No-op
    }

    override fun deselectNotify() {
        // No-op
    }

    override fun addPropertyChangeListener(listener: PropertyChangeListener) {
        // No-op
    }

    override fun removePropertyChangeListener(listener: PropertyChangeListener) {
        // No-op
    }

    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun dispose() {
        if (!isDisposed) {
            isDisposed = true
            reviewPanel.dispose()
            PrReviewTabService.getInstance(project).unregisterFile(file)
        }
    }
}
