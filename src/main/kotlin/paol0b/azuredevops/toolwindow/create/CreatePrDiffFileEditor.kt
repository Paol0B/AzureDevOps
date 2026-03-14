package paol0b.azuredevops.toolwindow.create

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.DiffRequestPanel
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel

/**
 * Editor that wraps a [DiffRequestPanel] for showing Create-PR file diffs.
 * The same instance is reused for every file click — only the diff content changes.
 */
class CreatePrDiffFileEditor(
    private val project: Project,
    private val file: CreatePrDiffVirtualFile
) : UserDataHolderBase(), FileEditor {

    private val wrapper = JPanel(BorderLayout())
    private var diffPanel: DiffRequestPanel? = null

    /** Called by [CreatePullRequestPanel] to update the diff shown in this tab. */
    fun showChange(change: Change, sourceBranch: String, targetBranch: String) {
        val factory = DiffContentFactory.getInstance()

        val left = change.beforeRevision?.content?.let {
            factory.create(project, it)
        } ?: factory.create(project, "")

        val right = change.afterRevision?.content?.let {
            factory.create(project, it)
        } ?: factory.create(project, "")

        val fileName = change.afterRevision?.file?.name ?: change.beforeRevision?.file?.name ?: "file"
        file.displayFileName = fileName

        val request = SimpleDiffRequest(fileName, left, right, targetBranch, sourceBranch)

        if (diffPanel == null) {
            val panel = DiffManager.getInstance().createRequestPanel(project, this, null)
            diffPanel = panel
            wrapper.add(panel.component, BorderLayout.CENTER)
        }

        diffPanel!!.setRequest(request)
        wrapper.revalidate()
        wrapper.repaint()
    }

    override fun getComponent(): JComponent = wrapper
    override fun getPreferredFocusedComponent(): JComponent? = diffPanel?.preferredFocusedComponent
    override fun getName(): String = "Create PR Diff"
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun setState(state: FileEditorState) {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getFile(): VirtualFile = file

    override fun dispose() {
        diffPanel?.let { Disposer.dispose(it) }
        diffPanel = null
    }
}
