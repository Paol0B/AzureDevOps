package paol0b.azuredevops.decorators

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.packageDependencies.ui.PackageDependenciesNode
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import paol0b.azuredevops.services.PullRequestCommentsTracker
import java.awt.Color

/**
 * Decorator per mostrare badge con numero di commenti nei file e cartelle
 * Stile Visual Studio/Rider: badge arancioni nell'esplora soluzioni
 */
class FileWithCommentsDecorator(private val project: Project) : ProjectViewNodeDecorator {

    private val commentsTracker: PullRequestCommentsTracker
        get() = project.service()

    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        val file = node.virtualFile ?: return
        
        if (file.isDirectory) {
            // Per le cartelle, conta ricorsivamente i commenti nei file figli
            val (totalComments, activeComments) = countCommentsInDirectory(file)
            
            if (totalComments > 0) {
                // Badge arancione con numero per cartelle
                val badgeColor = if (activeComments > 0) {
                    JBColor(Color(255, 140, 0), Color(255, 160, 50)) // Arancione
                } else {
                    JBColor(Color(100, 200, 100), Color(80, 150, 80)) // Verde se tutti risolti
                }
                
                val badge = if (activeComments > 0) " 💬$activeComments" else " ✓$totalComments"
                
                data.addText(badge, SimpleTextAttributes(
                    SimpleTextAttributes.STYLE_BOLD,
                    badgeColor
                ))
                
                data.tooltip = buildFolderTooltip(totalComments, activeComments)
            }
        } else {
            // Per i file singoli
            val commentCount = commentsTracker.getCommentCount(file)
            val activeCount = commentsTracker.getActiveCommentCount(file)
            
            if (commentCount > 0) {
                // Badge arancione/verde con numero
                val badgeColor = if (activeCount > 0) {
                    JBColor(Color(255, 140, 0), Color(255, 160, 50)) // Arancione
                } else {
                    JBColor(Color(100, 200, 100), Color(80, 150, 80)) // Verde
                }
                
                val badge = if (activeCount > 0) " 💬$activeCount" else " ✓$commentCount"
                
                data.addText(badge, SimpleTextAttributes(
                    SimpleTextAttributes.STYLE_BOLD,
                    badgeColor
                ))
                
                data.tooltip = buildFileTooltip(commentCount, activeCount)
            }
        }
    }

    /**
     * Conta ricorsivamente i commenti in una directory
     */
    private fun countCommentsInDirectory(dir: VirtualFile): Pair<Int, Int> {
        var totalComments = 0
        var activeComments = 0
        
        dir.children.forEach { child ->
            if (child.isDirectory) {
                val (childTotal, childActive) = countCommentsInDirectory(child)
                totalComments += childTotal
                activeComments += childActive
            } else {
                val count = commentsTracker.getCommentCount(child)
                val active = commentsTracker.getActiveCommentCount(child)
                totalComments += count
                activeComments += active
            }
        }
        
        return Pair(totalComments, activeComments)
    }

    private fun buildFileTooltip(total: Int, active: Int): String {
        return if (active > 0) {
            "$active active PR comment${if (active != 1) "s" else ""}" +
                    if (total > active) " (${total - active} resolved)" else ""
        } else {
            "$total resolved PR comment${if (total != 1) "s" else ""}"
        }
    }

    private fun buildFolderTooltip(total: Int, active: Int): String {
        return if (active > 0) {
            "Folder contains $active active PR comment${if (active != 1) "s" else ""}" +
                    if (total > active) " and ${total - active} resolved" else ""
        } else {
            "Folder contains $total resolved PR comment${if (total != 1) "s" else ""}"
        }
    }

    @Deprecated("Deprecated in base class")
    override fun decorate(node: PackageDependenciesNode, cellRenderer: ColoredTreeCellRenderer) {
        // Non necessario per questo use case
    }
}
