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
     * Rebuilds the tree from the given snapshot.
     *
     * @param projects every project known for the current account
     * @param repos repos indexed by project id (a project missing here is either still
     *              loading — see [loadingProjectIds] — or simply has no repos)
     * @param selectedProjectIds project ids the user wants visible. `null` means "all".
     * @param loadingProjectIds project ids whose repo fetch is in flight; these get a
     *                          "Loading repositories..." placeholder child node.
     * @param searchText filter applied to repo names (and project names) within the
     *                   already-resolved projects.
     */
    fun render(
        rootNode: DefaultMutableTreeNode,
        treeModel: DefaultTreeModel,
        tree: Tree,
        projects: List<AzureDevOpsCloneApiClient.Project>,
        repos: Map<String, List<AzureDevOpsCloneApiClient.Repository>>,
        selectedProjectIds: Set<String>?,
        loadingProjectIds: Set<String>,
        searchText: String
    ) {
        rootNode.removeAllChildren()

        if (projects.isEmpty()) {
            rootNode.add(DefaultMutableTreeNode("No projects found for this account"))
            treeModel.reload()
            return
        }

        // Empty [selectedProjectIds] is treated the same as null: show everything. An empty
        // filter is "no filter applied", not "show nothing" — the latter would be confusing
        // since the user has no other UI cue for why the tree is blank.
        val activeFilter = selectedProjectIds?.takeIf { it.isNotEmpty() }
        val visibleProjects = projects
            .filter { activeFilter == null || it.id in activeFilter }
            .sortedBy { it.name.lowercase() }

        if (visibleProjects.isEmpty()) {
            // Only reachable if [selectedProjectIds] contains ids that no longer exist.
            rootNode.add(DefaultMutableTreeNode("No matching projects"))
            treeModel.reload()
            return
        }

        val query = searchText.trim().lowercase()

        visibleProjects.forEach { proj ->
            val projectNameMatches = query.isEmpty() || proj.name.lowercase().contains(query)
            val loading = proj.id in loadingProjectIds
            val projectRepos = repos[proj.id].orEmpty()

            val matchingRepos = projectRepos
                .filter { repo ->
                    query.isEmpty() ||
                        projectNameMatches ||
                        repo.name.lowercase().contains(query)
                }
                .sortedBy { it.name.lowercase() }

            // Hide projects that contribute nothing under the current search (unless they're
            // still loading — we want users to see them filling in).
            if (!loading && matchingRepos.isEmpty() && query.isNotEmpty() && !projectNameMatches) {
                return@forEach
            }

            val projectNode = DefaultMutableTreeNode(proj)
            rootNode.add(projectNode)

            if (loading) {
                projectNode.add(DefaultMutableTreeNode("Loading repositories…"))
            } else if (projectRepos.isEmpty()) {
                projectNode.add(DefaultMutableTreeNode("No repositories"))
            } else if (matchingRepos.isEmpty()) {
                projectNode.add(DefaultMutableTreeNode("No matching repositories"))
            } else {
                matchingRepos.forEach { repo ->
                    projectNode.add(DefaultMutableTreeNode(toAzureDevOpsRepository(repo, proj.name)))
                }
            }
        }

        if (rootNode.childCount == 0) {
            rootNode.add(DefaultMutableTreeNode("No matches"))
        }

        treeModel.reload()
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
