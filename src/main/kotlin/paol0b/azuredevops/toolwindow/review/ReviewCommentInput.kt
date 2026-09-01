package paol0b.azuredevops.toolwindow.review

import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.fileTypes.FileTypes
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.ui.EditorTextField
import java.awt.event.InputEvent
import java.awt.event.KeyEvent
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * IntelliJ-native input for review comments. EditorTextField keeps the IDE's
 * input-method handling active while a Windows IME is composing inside a popup.
 */
internal class ReviewCommentInput(
    project: Project,
    oneLineMode: Boolean,
    placeholder: String
) : EditorTextField(
    EditorFactory.getInstance().createDocument(""),
    project,
    FileTypes.PLAIN_TEXT,
    false,
    oneLineMode
) {

    private val ownerProject = project
    private val multiline = !oneLineMode

    init {
        setPlaceholder(placeholder)
        enableInputMethods(true)
    }

    override fun createEditor(): EditorEx = super.createEditor().apply {
        contentComponent.enableInputMethods(true)
        if (multiline) settings.isUseSoftWraps = true
    }

    fun registerSubmitShortcut(action: () -> Unit) {
        DumbAwareAction.create { action() }.registerCustomShortcutSet(
            CustomShortcutSet(
                KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK)
            ),
            this
        )
    }

    fun requestEditorFocus() {
        SwingUtilities.invokeLater {
            val target = editor?.contentComponent ?: this@ReviewCommentInput.focusTarget
            target.enableInputMethods(true)
            if (!target.requestFocusInWindow()) {
                IdeFocusManager.getInstance(ownerProject).requestFocus(target, true)
            }
        }
    }
}
