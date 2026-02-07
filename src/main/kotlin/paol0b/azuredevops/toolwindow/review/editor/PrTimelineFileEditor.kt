package paol0b.azuredevops.toolwindow.review.editor

import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.PrReviewTabService
import paol0b.azuredevops.toolwindow.review.TimelinePanel
import java.beans.PropertyChangeListener
import javax.swing.JComponent

/**
 * Editor that wraps [TimelinePanel] to show the PR timeline.
 */
class PrTimelineFileEditor(
    private val project: Project,
    private val file: VirtualFile,
    private val pullRequest: PullRequest
) : UserDataHolderBase(), FileEditor {

    private val timelinePanel = TimelinePanel(project, pullRequest)
    private var isDisposed = false

    override fun getComponent(): JComponent = timelinePanel
    override fun getPreferredFocusedComponent(): JComponent? = timelinePanel
    override fun getName(): String = "Timeline"
    override fun getFile(): VirtualFile = file
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = !isDisposed
    override fun selectNotify() {}
    override fun deselectNotify() {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getCurrentLocation(): FileEditorLocation? = null

    override fun dispose() {
        if (!isDisposed) {
            isDisposed = true
            PrReviewTabService.getInstance(project).unregisterFile(file)
        }
    }
}
