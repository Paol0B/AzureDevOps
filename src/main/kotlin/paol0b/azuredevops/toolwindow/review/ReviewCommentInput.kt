package paol0b.azuredevops.toolwindow.review

import com.intellij.openapi.actionSystem.CustomShortcutSet
import com.intellij.openapi.actionSystem.KeyboardShortcut
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
    project: Project?,
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
    private var isComposing = false
    private val compositionListener = object : java.awt.event.InputMethodListener {
        override fun inputMethodTextChanged(event: java.awt.event.InputMethodEvent) {
            val text = event.text
            isComposing = text != null && text.endIndex - text.beginIndex > event.committedCharacterCount
        }
        override fun caretPositionChanged(event: java.awt.event.InputMethodEvent) = Unit
    }

    init {
        setPlaceholder(placeholder)
        enableInputMethods(true)
    }

    override fun createEditor(): EditorEx = super.createEditor().apply {
        contentComponent.enableInputMethods(true)
        contentComponent.addInputMethodListener(compositionListener)
        if (multiline) settings.isUseSoftWraps = true
    }

    fun registerSubmitShortcut(action: () -> Unit) {
        registerShortcut(CustomShortcutSet(
            KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), null),
            KeyboardShortcut(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.META_DOWN_MASK), null)
        ), action)
    }

    fun registerCancelShortcut(action: () -> Unit) {
        registerShortcut(CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0)), action)
    }

    private fun registerShortcut(shortcuts: CustomShortcutSet, action: () -> Unit) {
        DumbAwareAction.create { if (!isComposing) action() }.registerCustomShortcutSet(
            shortcuts,
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
