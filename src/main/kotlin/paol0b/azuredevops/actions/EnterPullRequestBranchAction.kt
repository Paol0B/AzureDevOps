package paol0b.azuredevops.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.PullRequestBranchService

/**
 * Action to enter (checkout) a Pull Request branch
 */
class EnterPullRequestBranchAction(
    private val pullRequest: PullRequest
) : AnAction("Enter This Branch", "Checkout the source branch of this Pull Request", null) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        val branchService = PullRequestBranchService.getInstance(project)
        branchService.enterPullRequestBranch(pullRequest)
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null && pullRequest.isActive()
    }
}
