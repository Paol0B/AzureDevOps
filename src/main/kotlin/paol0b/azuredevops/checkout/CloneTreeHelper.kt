package paol0b.azuredevops.checkout

import com.intellij.ui.treeStructure.Tree
import javax.swing.tree.DefaultMutableTreeNode
import javax.swing.tree.DefaultTreeModel
import javax.swing.tree.TreePath

/**
 * Shared helper for building and filtering the repository tree used by
 * [AzureDevOpsCloneDialog] and [AzureDevOpsCloneDialogComponent].
 */
object CloneTreeHelper {

    /**
     * Converts an API [AzureDevOpsCloneApiClient.Repository] into the domain
     * [AzureDevOpsRepository] model used throughout the clone dialogs.
     */
    fun toAzureDevOpsRepository(
        repo: AzureDevOpsCloneApiClient.Repository,
        projectName: String
    ): AzureDevOpsRepository = AzureDevOpsRepository(
        id = repo.id,
        name = repo.name,
        projectName = projectName,
        remoteUrl = repo.remoteUrl,
        webUrl = repo.webUrl
    )

    /**
     * Populates the tree from [data], sorting projects and repositories alphabetically
     * and expanding all project nodes.
     */
    fun populateTree(
        rootNode: DefaultMutableTreeNode,
        treeModel: DefaultTreeModel,
        tree: Tree,
        data: ProjectsData
    ) {
        rootNode.removeAllChildren()

        if (data.projects.isEmpty()) {
            rootNode.add(DefaultMutableTreeNode("No projects found for this account"))
            treeModel.reload()
            return
        }

        val sortedProjects = data.projects.sortedBy { it.name.lowercase() }

        sortedProjects.forEach { proj ->
            val projectNode = DefaultMutableTreeNode(proj)
            rootNode.add(projectNode)

            val repos = data.repositories[proj.id] ?: emptyList()
            repos.sortedBy { it.name.lowercase() }.forEach { repo ->
                val repoNode = DefaultMutableTreeNode(toAzureDevOpsRepository(repo, proj.name))
                projectNode.add(repoNode)
            }
        }

        treeModel.reload()

        // Expand all projects
        for (i in 0 until rootNode.childCount) {
            tree.expandPath(TreePath(arrayOf(rootNode, rootNode.getChildAt(i))))
        }
    }

    /**
     * Filters the tree to show only projects/repos matching [searchText].
     * If [searchText] is blank, delegates to [populateTree] to restore the full tree.
     */
    fun filterTree(
        rootNode: DefaultMutableTreeNode,
        treeModel: DefaultTreeModel,
        tree: Tree,
        data: ProjectsData,
        searchText: String
    ) {
        val query = searchText.trim().lowercase()

        if (query.isEmpty()) {
            populateTree(rootNode, treeModel, tree, data)
            return
        }

        rootNode.removeAllChildren()

        data.projects.sortedBy { it.name.lowercase() }.forEach { proj ->
            val repos = data.repositories[proj.id] ?: emptyList()
            val matchingRepos = repos.filter { repo ->
                repo.name.lowercase().contains(query) ||
                    proj.name.lowercase().contains(query)
            }.sortedBy { it.name.lowercase() }

            if (matchingRepos.isNotEmpty()) {
                val projectNode = DefaultMutableTreeNode(proj)
                rootNode.add(projectNode)

                matchingRepos.forEach { repo ->
                    projectNode.add(DefaultMutableTreeNode(toAzureDevOpsRepository(repo, proj.name)))
                }
            }
        }

        treeModel.reload()

        // Expand all matching projects
        for (i in 0 until rootNode.childCount) {
            tree.expandPath(TreePath(arrayOf(rootNode, rootNode.getChildAt(i))))
        }
    }

    /**
     * Replaces all tree content with a single message node (e.g. "Loading..." or an error).
     */
    fun showEmptyState(
        rootNode: DefaultMutableTreeNode,
        treeModel: DefaultTreeModel,
        message: String
    ) {
        rootNode.removeAllChildren()
        rootNode.add(DefaultMutableTreeNode(message))
        treeModel.reload()
    }
}
