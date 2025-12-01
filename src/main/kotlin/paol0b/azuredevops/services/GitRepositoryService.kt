package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.changes.Change
import git4idea.GitLocalBranch
import git4idea.GitRemoteBranch
import git4idea.history.GitHistoryUtils
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import paol0b.azuredevops.model.GitBranch

/**
 * Service to interact with the local Git repository
 */
@Service(Service.Level.PROJECT)
class GitRepositoryService(private val project: Project) {

    private val logger = Logger.getInstance(GitRepositoryService::class.java)

    companion object {
        fun getInstance(project: Project): GitRepositoryService {
            return project.getService(GitRepositoryService::class.java)
        }
    }

    /**
     * Gets the current Git repository of the project
     */
    fun getCurrentRepository(): GitRepository? {
        val manager = GitRepositoryManager.getInstance(project)
        val repositories = manager.repositories
        
        if (repositories.isEmpty()) {
            logger.warn("No Git repositories found in project")
            return null
        }
        
        // Returns the first repository (in multi-repo projects, this could be extended)
        return repositories.firstOrNull()
    }

    /**
     * Gets the current branch
     */
    fun getCurrentBranch(): GitBranch? {
        val repository = getCurrentRepository() ?: return null
        val currentBranch = repository.currentBranch ?: return null
        
        return GitBranch(
            name = "refs/heads/${currentBranch.name}",
            displayName = currentBranch.name
        )
    }

    /**
     * Gets all local branches
     */
    fun getLocalBranches(): List<GitBranch> {
        val repository = getCurrentRepository() ?: return emptyList()
        
        return repository.branches.localBranches.map { branch ->
            GitBranch(
                name = "refs/heads/${branch.name}",
                displayName = branch.name
            )
        }
    }

    /**
     * Gets all remote branches
     */
    fun getRemoteBranches(): List<GitBranch> {
        val repository = getCurrentRepository() ?: return emptyList()
        
        return repository.branches.remoteBranches.map { branch ->
            GitBranch(
                name = "refs/heads/${branch.nameForRemoteOperations}",
                displayName = branch.nameForRemoteOperations
            )
        }
    }

    /**
     * Determines the target branch (main or master) for the Pull Request
     * Priority: main > master > null
     */
    fun getDefaultTargetBranch(): GitBranch? {
        val repository = getCurrentRepository() ?: return null
        
        // First, look in remote branches
        val remoteBranches = repository.branches.remoteBranches
        val mainBranch = remoteBranches.firstOrNull { it.nameForRemoteOperations == "main" }
        if (mainBranch != null) {
            return GitBranch("refs/heads/main", "main")
        }
        
        val masterBranch = remoteBranches.firstOrNull { it.nameForRemoteOperations == "master" }
        if (masterBranch != null) {
            return GitBranch("refs/heads/master", "master")
        }
        
        // Fallback: look in local branches
        val localBranches = repository.branches.localBranches
        val localMain = localBranches.firstOrNull { it.name == "main" }
        if (localMain != null) {
            return GitBranch("refs/heads/main", "main")
        }
        
        val localMaster = localBranches.firstOrNull { it.name == "master" }
        if (localMaster != null) {
            return GitBranch("refs/heads/master", "master")
        }
        
        logger.warn("No default branch (main/master) found")
        return null
    }

    /**
     * Checks if the project has a Git repository
     */
    fun hasGitRepository(): Boolean {
        return getCurrentRepository() != null
    }

    /**
     * Gets the name of the Git repository (from the remote URL if available)
     */
    fun getRepositoryName(): String? {
        val repository = getCurrentRepository() ?: return null
        
        // Try to get the name from the remote
        val remotes = repository.remotes
        if (remotes.isNotEmpty()) {
            val firstRemoteUrl = remotes.first().firstUrl ?: return null
            // Extract the repository name from the URL
            // E.g., https://dev.azure.com/org/project/_git/repo -> repo
            // E.g., git@ssh.dev.azure.com:v3/org/project/repo -> repo
            return extractRepositoryNameFromUrl(firstRemoteUrl)
        }
        
        return null
    }

    /**
     * Extracts the repository name from a Git URL
     */
    private fun extractRepositoryNameFromUrl(url: String): String {
        // Remove .git at the end if present
        val cleanUrl = url.removeSuffix(".git")
        
        // Take the last segment of the path
        val segments = cleanUrl.split('/', '\\')
        return segments.lastOrNull()?.takeIf { it.isNotBlank() } ?: cleanUrl
    }

    /**
     * Checks if the current branch is main or master
     */
    fun isOnMainBranch(): Boolean {
        val currentBranch = getCurrentBranch() ?: return false
        return currentBranch.displayName == "main" || currentBranch.displayName == "master"
    }

    /**
     * Gets all available branches (local and remote combined, without duplicates)
     */
    fun getAllBranches(): List<GitBranch> {
        val repository = getCurrentRepository() ?: return emptyList()
        
        val branchNames = mutableSetOf<String>()
        val branches = mutableListOf<GitBranch>()
        
        // Add local branches
        repository.branches.localBranches.forEach { branch ->
            if (branchNames.add(branch.name)) {
                branches.add(GitBranch("refs/heads/${branch.name}", branch.name))
            }
        }
        
        // Add remote branches not already present
        repository.branches.remoteBranches.forEach { branch ->
            val name = branch.nameForRemoteOperations
            if (branchNames.add(name)) {
                branches.add(GitBranch("refs/heads/$name", name))
            }
        }
        
        return branches.sortedBy { it.displayName }
    }
    
    /**
     * Gets the remote Git URL (origin)
     */
    fun getRemoteUrl(): String? {
        val repository = getCurrentRepository() ?: return null
        
        // Look for the "origin" remote
        val originRemote = repository.remotes.firstOrNull { it.name == "origin" }
        if (originRemote != null) {
            return originRemote.firstUrl
        }
        
        // If no origin, return the first available remote
        val firstRemote = repository.remotes.firstOrNull()
        return firstRemote?.firstUrl
    }

    /**
     * Gets the Git repository for internal use
     */
    fun getRepository(): GitRepository? = getCurrentRepository()

    /**
     * Gets the changes between two branches
     */
    fun getChangesBetweenBranches(sourceBranch: String, targetBranch: String): List<Change> {
        val repository = getCurrentRepository() ?: return emptyList()
        
        try {
            // Remove the refs/heads/ prefix if present
            val sourceRef = sourceBranch.removePrefix("refs/heads/")
            val targetRef = targetBranch.removePrefix("refs/heads/")
            
            // Get the commits between the two branches
            val commits = GitHistoryUtils.history(project, repository.root, "$targetRef..$sourceRef")
            
            // Collect all changes from the commits
            val changes = mutableListOf<Change>()
            commits.forEach { commit ->
                changes.addAll(commit.changes)
            }
            
            return changes.distinctBy { it.afterRevision?.file?.path ?: it.beforeRevision?.file?.path }
        } catch (e: Exception) {
            logger.error("Error getting changes between branches", e)
            return emptyList()
        }
    }

    /**
     * Gets the commits between two branches
     */
    fun getCommitsBetweenBranches(sourceBranch: String, targetBranch: String): List<git4idea.GitCommit> {
        val repository = getCurrentRepository() ?: return emptyList()
        
        try {
            // Remove the refs/heads/ prefix if present
            val sourceRef = sourceBranch.removePrefix("refs/heads/")
            val targetRef = targetBranch.removePrefix("refs/heads/")
            
            // Get the commits between the two branches (from target to source)
            return GitHistoryUtils.history(project, repository.root, "$targetRef..$sourceRef")
        } catch (e: Exception) {
            logger.error("Error getting commits between branches", e)
            return emptyList()
        }
    }
}
