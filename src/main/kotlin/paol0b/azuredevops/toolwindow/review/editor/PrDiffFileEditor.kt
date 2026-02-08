package paol0b.azuredevops.toolwindow.review.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.model.PullRequestChange
import paol0b.azuredevops.services.PrReviewTabService
import paol0b.azuredevops.toolwindow.review.DiffViewerPanel
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * Editor that wraps [DiffViewerPanel] to show a single-file diff with inline comments.
 */
class PrDiffFileEditor(
    private val project: Project,
    private val file: VirtualFile,
    private val pullRequest: PullRequest,
    private var change: PullRequestChange
) : UserDataHolderBase(), FileEditor {

    private val diffPanel = DiffViewerPanel(
        project,
        pullRequest.pullRequestId,
        pullRequest.repository?.project?.name,
        pullRequest.repository?.id
    ).apply {
        loadDiff(change)
    }

    private var isDisposed = false

    override fun getComponent(): JComponent = diffPanel
    override fun getPreferredFocusedComponent(): JComponent? = diffPanel
    override fun getName(): String = change.item?.path?.substringAfterLast('/') ?: "Diff"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = !isDisposed
    override fun selectNotify() {}
    override fun deselectNotify() {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getCurrentLocation(): FileEditorLocation? = null

    /**
     * Update the displayed change and reload the diff viewer.
     * Called when the same tab is reused for a different file in the same PR.
     */
    fun updateChange(newChange: PullRequestChange) {
        change = newChange
        diffPanel.loadDiff(newChange)
    }

    override fun dispose() {
        if (!isDisposed) {
            isDisposed = true
            diffPanel.dispose()
            PrReviewTabService.getInstance(project).unregisterFile(file)
        }
    }
}
