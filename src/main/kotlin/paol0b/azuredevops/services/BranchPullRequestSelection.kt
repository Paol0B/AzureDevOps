package paol0b.azuredevops.services

import paol0b.azuredevops.model.PullRequest

/** A shared source branch is insufficient to identify the intended review target. */
internal object BranchPullRequestSelection {
    fun unambiguous(requests: List<PullRequest>): PullRequest? {
        check(requests.size <= 1) { "Multiple active PRs use this branch. Open the intended PR from the PR list." }
        return requests.singleOrNull()
    }

    fun choose(requests: List<PullRequest>, pick: (List<String>) -> Int): PullRequest? {
        if (requests.size <= 1) return requests.singleOrNull()
        val labels = requests.map { "PR #${it.pullRequestId}: ${it.title} → ${it.getTargetBranchName()}" }
        return requests.getOrNull(pick(labels))
    }
}
