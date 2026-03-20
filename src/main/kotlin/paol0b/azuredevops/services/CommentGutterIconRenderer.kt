package paol0b.azuredevops.services

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.ui.CommentThreadDialog
import javax.swing.Icon

/**
 * Renderer for the comment icon in the editor gutter
 * Visual Studio style: icons visible in the left margin with informative tooltips
 */
class CommentGutterIconRenderer(
    private val thread: CommentThread,
    private val pullRequest: PullRequest,
    private val icon: Icon,
    private val commentsService: PullRequestCommentsService
) : GutterIconRenderer() {

    override fun getIcon(): Icon = icon

    override fun getTooltipText(): String {
        val comments = thread.comments ?: emptyList()
        val firstComment = comments.firstOrNull()
        val status = if (thread.isResolved()) "✓ Resolved" else "⚠ Active"
        val count = comments.size
        
        val sb = StringBuilder()
        sb.append("<html><b>PR Comment - $status</b>")

        if (firstComment != null) {
            sb.append("<br><b>Author:</b> ${escapeHtml(firstComment.author?.displayName ?: "Unknown")}")

            val content = firstComment.content ?: ""
            if (content.isNotEmpty()) {
                sb.append("<br><hr>")
                val escaped = escapeHtml(content)
                val preview: String = if (escaped.length > 150) escaped.take(150) + "..." else escaped
                sb.append(preview)
            }
        }

        if (count > 1) {
            sb.append("<br><hr><i>+${count - 1} ${if (count == 2) "reply" else "replies"}</i>")
        }

        sb.append("<br><br><font size='-2' color='gray'>Click to view and reply</font>")
        sb.append("</html>")
        return sb.toString()
    }

    override fun getClickAction(): AnAction {
        return object : AnAction("View Comment Thread") {
            override fun actionPerformed(e: AnActionEvent) {
                val project = e.project ?: return
                
                // Open dialog to view and manage the thread
                val dialog = CommentThreadDialog(project, thread, pullRequest, commentsService)
                dialog.show()
            }
        }
    }
    
    override fun getAlignment(): Alignment {
        // Align the icon to the right in the gutter for better visibility
        return Alignment.RIGHT
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CommentGutterIconRenderer) return false
        return thread.id == other.thread.id
    }

    override fun hashCode(): Int {
        return thread.id?.hashCode() ?: 0
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
    }
}
