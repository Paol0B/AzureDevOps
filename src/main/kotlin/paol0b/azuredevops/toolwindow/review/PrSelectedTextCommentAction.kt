package paol0b.azuredevops.toolwindow.review

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.util.Key

/** Diff popup action enabled only by the current PR editor's request-scoped callbacks. */
class PrSelectedTextCommentAction : AnAction() {
    override fun getActionUpdateThread() = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        e.presentation.isEnabledAndVisible = editor?.getUserData(CAN_COMMENT)?.invoke() == true
    }

    override fun actionPerformed(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR) ?: return
        if (editor.getUserData(CAN_COMMENT)?.invoke() == true) editor.getUserData(COMMENT)?.invoke()
    }

    companion object {
        val CAN_COMMENT = Key.create<() -> Boolean>("azuredevops.pr.selection.canComment")
        val COMMENT = Key.create<() -> Unit>("azuredevops.pr.selection.comment")
    }
}
