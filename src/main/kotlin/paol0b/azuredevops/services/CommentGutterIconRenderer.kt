package paol0b.azuredevops.services

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.ui.Messages
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.ui.CommentThreadDialog
import javax.swing.Icon

/**
 * Renderer per l'icona dei commenti nella gutter dell'editor
 * Stile Visual Studio: icone visibili nel margine sinistro con tooltip informativi
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
        
        return buildString {
            append("<html><b>PR Comment - $status</b>")
            
            if (firstComment != null) {
                append("<br><b>Author:</b> ${firstComment.author?.displayName ?: "Unknown"}")
                
                val content = firstComment.content ?: ""
                if (content.isNotEmpty()) {
                    append("<br><hr>")
                    val preview = if (content.length > 150) {
                        content.take(150).replace("\n", "<br>") + "..."
                    } else {
                        content.replace("\n", "<br>")
                    }
                    append(preview)
                }
            }
            
            if (count > 1) {
                append("<br><hr><i>+${count - 1} ${if (count == 2) "reply" else "replies"}</i>")
            }
            
            append("<br><br><font size='-2' color='gray'>Click to view and reply</font>")
            append("</html>")
        }
    }

    override fun getClickAction(): AnAction {
        return object : AnAction("View Comment Thread") {
            override fun actionPerformed(e: AnActionEvent) {
                val project = e.project ?: return
                
                // Apri dialog per visualizzare e gestire il thread
                val dialog = CommentThreadDialog(project, thread, pullRequest, commentsService)
                dialog.show()
            }
        }
    }
    
    override fun getAlignment(): Alignment {
        // Allinea l'icona a destra nella gutter per maggiore visibilità
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
}
