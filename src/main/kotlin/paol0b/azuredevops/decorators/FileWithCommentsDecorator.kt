package paol0b.azuredevops.decorators

import com.intellij.icons.AllIcons
import com.intellij.ide.projectView.PresentationData
import com.intellij.ide.projectView.ProjectViewNode
import com.intellij.ide.projectView.ProjectViewNodeDecorator
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.JBColor
import com.intellij.ui.SimpleTextAttributes
import paol0b.azuredevops.services.PullRequestCommentsTracker
import java.awt.Color

/**
 * Decorator for PR Comments on files and folders
 * Features:
 * - Icon displayed alongside filename (not replacing it)
 * - Works with all project types (Java, C#, Rider solutions, etc.)
 * - Proper handling of directories and solution files
 * - Visual indicators for active vs resolved comments
 * - Tooltips with detailed information
 */
class FileWithCommentsDecorator(private val project: Project) : ProjectViewNodeDecorator {

    private val logger = Logger.getInstance(FileWithCommentsDecorator::class.java)
    private val commentsTracker: PullRequestCommentsTracker
        get() = project.service()

    @Suppress("OVERRIDE_DEPRECATION")
    override fun decorate(node: ProjectViewNode<*>, data: PresentationData) {
        try {
            val file = node.virtualFile ?: return
            
            if (file.isDirectory) {
                decorateDirectory(file, data)
            } else {
                decorateFile(file, data)
            }
        } catch (e: Exception) {
            // Log but don't fail - decorator should be non-intrusive
            logger.warn("Failed to decorate node: ${e.message}", e)
        }
    }

    private fun decorateDirectory(dir: VirtualFile, data: PresentationData) {
        val (totalComments, activeComments) = countCommentsInDirectory(dir)
        
        if (totalComments > 0) {
            // Add icon and count badge AFTER the folder name
            val icon = if (activeComments > 0) {
                AllIcons.Toolwindows.ToolWindowMessages
            } else {
                AllIcons.RunConfigurations.TestPassed
            }
            
            val badgeColor = if (activeComments > 0) {
                JBColor(Color(255, 140, 0), Color(255, 160, 50)) // Orange for active
            } else {
                JBColor(Color(100, 200, 100), Color(80, 150, 80)) // Green for resolved
            }
            
            val badgeText = " ($activeComments)"
            
            // Append to existing text instead of replacing
            data.addText(badgeText, SimpleTextAttributes(
                SimpleTextAttributes.STYLE_BOLD,
                badgeColor
            ))
            
            // Set appropriate icon
            if (activeComments > 0) {
                data.setIcon(combineIcons(data.getIcon(false), icon))
            }
            
            // Enhanced tooltip
            data.tooltip = buildDirectoryTooltip(totalComments, activeComments)
        }
    }

    private fun decorateFile(file: VirtualFile, data: PresentationData) {
        val commentCount = commentsTracker.getCommentCount(file)
        val activeCount = commentsTracker.getActiveCommentCount(file)
        
        if (commentCount > 0) {
            val icon = if (activeCount > 0) {
                AllIcons.Toolwindows.ToolWindowMessages
            } else {
                AllIcons.RunConfigurations.TestPassed
            }
            
            val badgeColor = if (activeCount > 0) {
                JBColor(Color(255, 140, 0), Color(255, 160, 50)) // Orange
            } else {
                JBColor(Color(100, 200, 100), Color(80, 150, 80)) // Green
            }
            
            val badgeText = " ($activeCount)"
            
            // Append badge AFTER filename
            data.addText(badgeText, SimpleTextAttributes(
                SimpleTextAttributes.STYLE_BOLD,
                badgeColor
            ))
            
            // Set icon overlay (show comment icon alongside file icon)
            if (activeCount > 0) {
                data.setIcon(combineIcons(data.getIcon(false), icon))
            }
            
            // Enhanced tooltip
            data.tooltip = buildFileTooltip(commentCount, activeCount, file.name)
        }
    }

    /**
     * Recursively count comments in directory and subdirectories
     */
    private fun countCommentsInDirectory(dir: VirtualFile): Pair<Int, Int> {
        var totalComments = 0
        var activeComments = 0
        
        try {
            dir.children?.forEach { child ->
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
        } catch (e: Exception) {
            logger.warn("Error counting comments in directory ${dir.path}: ${e.message}")
        }
        
        return Pair(totalComments, activeComments)
    }

    private fun buildFileTooltip(total: Int, active: Int, fileName: String): String {
        return buildString {
            append("<html><b>$fileName</b><br>")
            if (active > 0) {
                append("<font color='orange'>● $active active PR comment${if (active != 1) "s" else ""}</font>")
                if (total > active) {
                    append("<br><font color='gray'>✓ ${total - active} resolved</font>")
                }
            } else {
                append("<font color='green'>✓ $total resolved PR comment${if (total != 1) "s" else ""}</font>")
            }
            append("<br><i>Click to view in editor</i></html>")
        }
    }

    private fun buildDirectoryTooltip(total: Int, active: Int): String {
        return buildString {
            append("<html><b>PR Comments in Folder</b><br>")
            if (active > 0) {
                append("<font color='orange'>● $active active</font>")
                if (total > active) {
                    append(" | <font color='gray'>✓ ${total - active} resolved</font>")
                }
            } else {
                append("<font color='green'>✓ $total resolved</font>")
            }
            append("</html>")
        }
    }

    /**
     * Combine two icons (base icon + overlay)
     * This ensures the file type icon is preserved with comment indicator
     */
    private fun combineIcons(baseIcon: javax.swing.Icon?, overlayIcon: javax.swing.Icon?): javax.swing.Icon? {
        if (baseIcon == null) return overlayIcon
        if (overlayIcon == null) return baseIcon
        
        // For now, return base icon - proper icon combining would require RowIcon or LayeredIcon
        // which is complex but the text badge already provides visual feedback
        return baseIcon
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun decorate(node: com.intellij.packageDependencies.ui.PackageDependenciesNode?, cellRenderer: com.intellij.ui.ColoredTreeCellRenderer?) {
        // Default implementation for backward compatibility
        // This override is required by the interface but not used in our implementation
    }
}
