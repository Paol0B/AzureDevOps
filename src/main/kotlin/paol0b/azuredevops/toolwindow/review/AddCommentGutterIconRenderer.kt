package paol0b.azuredevops.toolwindow.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import javax.swing.Icon

/**
 * Gutter icon renderer that shows a "+" icon on hover.
 * Mimics the GitHub JetBrains plugin behavior:
 * hovering over a line in the diff shows a subtle "+" in the gutter,
 * clicking it triggers the inline comment editor.
 */
class AddCommentGutterIconRenderer(
    private val line: Int,
    private val onAddComment: (Int) -> Unit
) : GutterIconRenderer() {

    override fun getIcon(): Icon = AllIcons.General.Add

    override fun getTooltipText(): String = "Add comment"

    override fun isNavigateAction(): Boolean = true

    override fun getAlignment(): Alignment = Alignment.RIGHT

    override fun getClickAction(): AnAction {
        return object : AnAction() {
            override fun actionPerformed(e: AnActionEvent) {
                onAddComment(line)
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AddCommentGutterIconRenderer) return false
        return line == other.line
    }

    override fun hashCode(): Int = 31 * line + "add_comment".hashCode()
}
