package paol0b.azuredevops.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.PullRequestReviewService

/**
 * Action per avviare la review di una Pull Request
 * Apre il diff viewer con tutte le modifiche e permette di aggiungere commenti
 */
class ReviewPullRequestAction(
    private val pullRequest: PullRequest
) : AnAction(
    "Review PR",
    "Review all changes in this Pull Request with integrated diff viewer and code analysis",
    AllIcons.Actions.Diff
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        
        val reviewService = PullRequestReviewService.getInstance(project)
        reviewService.startReview(pullRequest)
    }
}
