package paol0b.azuredevops.toolwindow.review

import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DataContext
import com.intellij.openapi.actionSystem.KeyboardShortcut
import javax.swing.KeyStroke

internal fun invokeReviewShortcut(input: ReviewCommentInput, stroke: String) {
    val shortcut = KeyboardShortcut(KeyStroke.getKeyStroke(stroke), null)
    val action = ActionUtil.getActions(input).single { shortcut in it.shortcutSet.shortcuts }
    action.actionPerformed(AnActionEvent.createFromAnAction(action, null, "test", DataContext.EMPTY_CONTEXT))
}
