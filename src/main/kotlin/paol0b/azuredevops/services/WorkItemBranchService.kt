package paol0b.azuredevops.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import paol0b.azuredevops.model.WorkItem
import paol0b.azuredevops.util.NotificationUtil

/**
 * Service to create Git branches from Work Items.
 * Creates a properly named branch, optionally pushes to remote,
 * and checks out locally.
 */
@Service(Service.Level.PROJECT)
class WorkItemBranchService(private val project: Project) {

    private val logger = Logger.getInstance(WorkItemBranchService::class.java)
    private val git = Git.getInstance()

    companion object {
        fun getInstance(project: Project): WorkItemBranchService {
            return project.getService(WorkItemBranchService::class.java)
        }
    }

    /**
     * Create a branch from a work item and check it out locally.
     *
     * @param workItem The work item to create a branch for
     * @param customBranchName Optional custom branch name (auto-generated if null)
     * @param pushToRemote Whether to push the branch to the remote
     * @param onSuccess Callback on EDT after successful creation
     */
    fun createBranchFromWorkItem(
        workItem: WorkItem,
        customBranchName: String? = null,
        pushToRemote: Boolean = true,
        onSuccess: (() -> Unit)? = null
    ) {
        val gitService = GitRepositoryService.getInstance(project)
        val repository = gitService.getCurrentRepository()

        if (repository == null) {
            NotificationUtil.error(project, "Error", "No Git repository found in this project")
            return
        }

        val branchName = customBranchName ?: generateBranchName(workItem)

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Creating branch from Work Item #${workItem.id}...", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                try {
                    // Check if branch already exists locally
                    val existingBranch = repository.branches.localBranches.find { it.name == branchName }
                    if (existingBranch != null) {
                        indicator.text = "Checking out existing branch..."
                        val handler = GitLineHandler(project, repository.root, GitCommand.CHECKOUT)
                        handler.addParameters(branchName)
                        val result = git.runCommand(handler)
                        if (!result.success()) {
                            NotificationUtil.error(project, "Error",
                                "Failed to checkout branch: ${result.errorOutputAsJoinedString}")
                            return
                        }
                        repository.update()
                        NotificationUtil.info(project, "Branch Checked Out",
                            "Switched to existing branch '$branchName'")
                        ApplicationManager.getApplication().invokeLater { onSuccess?.invoke() }
                        return
                    }

                    if (pushToRemote) {
                        indicator.text = "Creating remote branch..."

                        // Get HEAD SHA
                        val revParseHandler = GitLineHandler(project, repository.root, GitCommand.REV_PARSE)
                        revParseHandler.addParameters("HEAD")
                        val revParseResult = git.runCommand(revParseHandler)
                        if (!revParseResult.success()) {
                            NotificationUtil.error(project, "Error",
                                "Failed to get HEAD commit: ${revParseResult.errorOutputAsJoinedString}")
                            return
                        }
                        val headSha = revParseResult.outputAsJoinedString.trim()

                        // Create remote branch via Azure DevOps API
                        try {
                            val apiClient = AzureDevOpsApiClient.getInstance(project)
                            val result = apiClient.createGitRef(branchName, headSha)
                            if (result.success != true) {
                                logger.warn("Remote branch creation status: ${result.updateStatus}")
                            }
                        } catch (e: Exception) {
                            logger.warn("Failed to create remote branch via API, will push via git: ${e.message}")
                        }

                        // Fetch to get the remote branch
                        indicator.text = "Fetching remote..."
                        val remoteName = repository.remotes.find { it.name == "origin" }?.name
                            ?: repository.remotes.firstOrNull()?.name
                            ?: "origin"

                        val fetchHandler = GitLineHandler(project, repository.root, GitCommand.FETCH)
                        fetchHandler.addParameters(remoteName)
                        git.runCommand(fetchHandler)

                        // Create local tracking branch
                        indicator.text = "Creating local branch..."
                        val checkoutHandler = GitLineHandler(project, repository.root, GitCommand.CHECKOUT)
                        checkoutHandler.addParameters("-b", branchName)
                        checkoutHandler.addParameters("--track", "refs/remotes/$remoteName/$branchName")

                        val checkoutResult = git.runCommand(checkoutHandler)
                        if (!checkoutResult.success()) {
                            // Fallback: create local branch without tracking
                            val fallbackHandler = GitLineHandler(project, repository.root, GitCommand.CHECKOUT)
                            fallbackHandler.addParameters("-b", branchName)
                            val fallbackResult = git.runCommand(fallbackHandler)
                            if (!fallbackResult.success()) {
                                NotificationUtil.error(project, "Error",
                                    "Failed to create branch: ${fallbackResult.errorOutputAsJoinedString}")
                                return
                            }

                            // Push to set up tracking
                            val pushHandler = GitLineHandler(project, repository.root, GitCommand.PUSH)
                            pushHandler.addParameters("-u", remoteName, branchName)
                            git.runCommand(pushHandler)
                        }
                    } else {
                        // Local only
                        indicator.text = "Creating local branch..."
                        val checkoutHandler = GitLineHandler(project, repository.root, GitCommand.CHECKOUT)
                        checkoutHandler.addParameters("-b", branchName)
                        val result = git.runCommand(checkoutHandler)
                        if (!result.success()) {
                            NotificationUtil.error(project, "Error",
                                "Failed to create branch: ${result.errorOutputAsJoinedString}")
                            return
                        }
                    }

                    repository.update()

                    NotificationUtil.info(project, "Branch Created",
                        "Created and checked out '$branchName' from ${workItem.getWorkItemType()} #${workItem.id}")

                    ApplicationManager.getApplication().invokeLater { onSuccess?.invoke() }

                } catch (e: Exception) {
                    logger.error("Failed to create branch from work item", e)
                    NotificationUtil.error(project, "Error",
                        "Failed to create branch: ${e.message}")
                }
            }
        })
    }

    /**
     * Generate a branch name from a work item.
     * Pattern: {prefix}/{id}-{sanitized-title}
     */
    fun generateBranchName(workItem: WorkItem): String {
        val prefix = getBranchPrefix(workItem.getWorkItemType())
        val sanitized = sanitizeBranchName(workItem.getTitle())
        return "$prefix/${workItem.id}-$sanitized"
    }

    private fun getBranchPrefix(workItemType: String): String = when (workItemType.lowercase()) {
        "bug" -> "bugfix"
        "task" -> "task"
        "user story", "product backlog item" -> "feature"
        "feature" -> "feature"
        "epic" -> "feature"
        else -> "work"
    }

    private fun sanitizeBranchName(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9/_\\-]"), "-")
            .replace(Regex("-+"), "-")
            .take(50)
            .trimEnd('-')
    }
}
