package paol0b.azuredevops.checkout

import com.intellij.icons.AllIcons
import com.intellij.ui.ColoredTreeCellRenderer
import com.intellij.ui.SimpleTextAttributes
import paol0b.azuredevops.AzureDevOpsIcons
import javax.swing.JTree
import javax.swing.tree.DefaultMutableTreeNode

/**
 * Shared tree cell renderer for Azure DevOps repository trees.
 * Used by both [AzureDevOpsCloneDialog] and [AzureDevOpsCloneDialogComponent].
 *
 * Handles three node types:
 * - [AzureDevOpsCloneApiClient.Project] - project icon, bold name, grayed description (truncated at 50 chars)
 * - [AzureDevOpsRepository] - repository icon, regular name
 * - [String] - status/info messages with appropriate icons and grayed italic text
 */
class RepositoryTreeCellRenderer : ColoredTreeCellRenderer() {

    override fun customizeCellRenderer(
        tree: JTree,
        value: Any?,
        selected: Boolean,
        expanded: Boolean,
        leaf: Boolean,
        row: Int,
        hasFocus: Boolean
    ) {
        val node = value as? DefaultMutableTreeNode ?: return
        val userObject = node.userObject

        when (userObject) {
            is AzureDevOpsCloneApiClient.Project -> {
                icon = AzureDevOpsIcons.Project
                append(userObject.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                userObject.description?.let {
                    if (it.isNotBlank()) {
                        val display = if (it.length > MAX_DESCRIPTION_LENGTH) {
                            it.take(MAX_DESCRIPTION_LENGTH) + "\u2026"
                        } else {
                            it
                        }
                        append("  $display", SimpleTextAttributes.GRAYED_SMALL_ATTRIBUTES)
                    }
                }
            }
            is AzureDevOpsRepository -> {
                icon = AzureDevOpsIcons.Repository
                append(userObject.name, SimpleTextAttributes.REGULAR_ATTRIBUTES)
            }
            is String -> {
                icon = when {
                    userObject.startsWith("Error") || userObject.startsWith("Authentication") -> AllIcons.General.Error
                    userObject.contains("Loading") -> AllIcons.Process.Step_1
                    userObject.contains("No ") -> AllIcons.General.Information
                    else -> AllIcons.General.Information
                }
                append(userObject, SimpleTextAttributes.GRAYED_ITALIC_ATTRIBUTES)
            }
        }
    }

    companion object {
        private const val MAX_DESCRIPTION_LENGTH = 50
    }
}
