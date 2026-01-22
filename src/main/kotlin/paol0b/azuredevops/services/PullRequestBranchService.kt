package paol0b.azuredevops.services

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.wm.ToolWindowManager
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRemote
import git4idea.repo.GitRepository
import paol0b.azuredevops.model.PullRequest

/**
 * Service to handle Pull Request branch operations:
 * - Fetching remote branches
 * - Creating local branches for PRs
 * - Setting up tracking
 * - Checking out PR branches
 */
@Service(Service.Level.PROJECT)
class PullRequestBranchService(private val project: Project) {

    private val logger = Logger.getInstance(PullRequestBranchService::class.java)
    private val git = Git.getInstance()

    companion object {
        fun getInstance(project: Project): PullRequestBranchService {
            return project.getService(PullRequestBranchService::class.java)
        }
    }

    /**
     * Enter the branch for a Pull Request
     * This will:
     * 1. Fetch the remote that holds the PR source branch
     * 2. Create a safe local branch name
     * 3. Set up tracking to the remote branch
     * 4. Checkout the branch
     */
    fun enterPullRequestBranch(pullRequest: PullRequest, onSuccess: (() -> Unit)? = null) {
        val gitService = GitRepositoryService.getInstance(project)
        val repository = gitService.getCurrentRepository()

        if (repository == null) {
            showError("No Git repository found in this project")
            return
        }

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project,
            "Checking out PR #${pullRequest.pullRequestId}...",
            true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    indicator.text = "Fetching branch information..."
                    
                    val sourceBranchName = pullRequest.getSourceBranchName()
                    val prId = pullRequest.pullRequestId
                    
                    // Determine remote name
                    val remoteName = findRemoteForBranch(repository, pullRequest)
                    
                    if (remoteName == null) {
                        showError("Could not find remote for PR source branch. The PR may be from a fork.")
                        return
                    }

                    indicator.text = "Fetching from remote '$remoteName'..."
                    logger.info("Fetching branch '$sourceBranchName' from remote '$remoteName'")

                    // Fetch the branch from remote
                    val fetchHandler = GitLineHandler(project, repository.root, GitCommand.FETCH)
                    fetchHandler.addParameters(remoteName, sourceBranchName)
                    
                    val fetchResult = git.runCommand(fetchHandler)
                    if (!fetchResult.success()) {
                        showError("Failed to fetch branch: ${fetchResult.errorOutputAsJoinedString}")
                        return
                    }

                    indicator.text = "Creating local branch..."
                    
                    // Create safe local branch name
                    val localBranchName = createSafeBranchName(repository, prId, sourceBranchName)
                    logger.info("Creating local branch '$localBranchName'")

                    // Check if branch already exists
                    val existingBranch = repository.branches.localBranches.find { it.name == localBranchName }
                    
                    if (existingBranch != null) {
                        // Branch exists, just checkout
                        indicator.text = "Checking out existing branch..."
                        checkoutBranch(repository, localBranchName)
                    } else {
                        // Create new branch tracking the remote
                        val remoteBranchRef = "refs/remotes/$remoteName/$sourceBranchName"
                        
                        val checkoutHandler = GitLineHandler(project, repository.root, GitCommand.CHECKOUT)
                        checkoutHandler.addParameters("-b", localBranchName)
                        checkoutHandler.addParameters("--track", remoteBranchRef)
                        
                        val checkoutResult = git.runCommand(checkoutHandler)
                        if (!checkoutResult.success()) {
                            showError("Failed to create branch: ${checkoutResult.errorOutputAsJoinedString}")
                            return
                        }
                    }

                    // Refresh repository state
                    repository.update()

                    // Show success notification
                    showSuccess("Successfully checked out PR #$prId to branch '$localBranchName'")
                    
                    // Open VCS toolwindow
                    openVcsToolWindow()
                    
                    onSuccess?.invoke()
                    
                } catch (e: Exception) {
                    logger.error("Failed to checkout PR branch", e)
                    showError("Error checking out PR branch: ${e.message}")
                }
            }
        })
    }

    /**
     * Find the appropriate remote for the PR branch
     */
    private fun findRemoteForBranch(repository: GitRepository, pullRequest: PullRequest): String? {
        val remotes = repository.remotes
        
        if (remotes.isEmpty()) {
            return null
        }

        // Check if PR is from the same repository
        val prRepoUrl = pullRequest.repository?.remoteUrl
        
        if (prRepoUrl != null) {
            // Try to find matching remote by URL
            val matchingRemote = remotes.find { remote ->
                remote.urls.any { url -> normalizeUrl(url) == normalizeUrl(prRepoUrl) }
            }
            
            if (matchingRemote != null) {
                return matchingRemote.name
            }
        }

        // Default to 'origin' if it exists
        val origin = remotes.find { it.name == "origin" }
        if (origin != null) {
            return origin.name
        }

        // Return first remote as fallback
        return remotes.firstOrNull()?.name
    }

    /**
     * Normalize Git URL for comparison
     */
    private fun normalizeUrl(url: String): String {
        return url
            .replace(Regex("^https?://"), "")
            .replace(Regex("^git@"), "")
            .replace(Regex("\\.git$"), "")
            .replace(":", "/")
            .lowercase()
    }

    /**
     * Create a safe, unique local branch name
     */
    private fun createSafeBranchName(repository: GitRepository, prId: Int, sourceBranch: String): String {
        // Try pattern: pr/{id}-{short-source-branch}
        val shortBranchName = sourceBranch.split("/").last().take(30)
        var branchName = "pr/$prId-$shortBranchName"
        
        // Sanitize branch name
        branchName = branchName.replace(Regex("[^a-zA-Z0-9/_-]"), "-")
        
        // Check for collision and add suffix if needed
        var suffix = 0
        var finalBranchName = branchName
        
        while (repository.branches.localBranches.any { it.name == finalBranchName }) {
            suffix++
            finalBranchName = "$branchName-$suffix"
        }
        
        return finalBranchName
    }

    /**
     * Checkout an existing branch
     */
    private fun checkoutBranch(repository: GitRepository, branchName: String) {
        val handler = GitLineHandler(project, repository.root, GitCommand.CHECKOUT)
        handler.addParameters(branchName)
        
        val result = git.runCommand(handler)
        if (!result.success()) {
            throw Exception("Failed to checkout branch: ${result.errorOutputAsJoinedString}")
        }
    }

    /**
     * Open the VCS log toolwindow
     */
    private fun openVcsToolWindow() {
        val toolWindowManager = ToolWindowManager.getInstance(project)
        toolWindowManager.getToolWindow("Version Control")?.show()
    }

    /**
     * Show success notification
     */
    private fun showSuccess(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Azure DevOps Notifications")
            .createNotification(
                "Pull Request",
                message,
                NotificationType.INFORMATION
            )
            .notify(project)
    }

    /**
     * Show error notification
     */
    private fun showError(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Azure DevOps Notifications")
            .createNotification(
                "Pull Request",
                message,
                NotificationType.ERROR
            )
            .notify(project)
    }
}
