package paol0b.azuredevops.toolwindow

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.ProjectLevelVcsManager
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener

/**
 * Calls [load] on the EDT each time [content] is selected, once the project's Git repositories are known.
 * Call it before adding [content]: the content manager selects its first content while adding it.
 */
fun loadWhenSelected(project: Project, contentManager: ContentManager, content: Content, load: () -> Unit) {
    contentManager.addContentManagerListener(object : ContentManagerListener {
        override fun selectionChanged(event: ContentManagerEvent) {
            if (event.content != content || event.operation != ContentManagerEvent.ContentOperation.add) return
            ProjectLevelVcsManager.getInstance(project).runAfterInitialization {
                ApplicationManager.getApplication().invokeLater({ load() }, project.disposed)
            }
        }
    })
}
