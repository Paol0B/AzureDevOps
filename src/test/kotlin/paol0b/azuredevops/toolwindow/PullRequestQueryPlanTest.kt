package paol0b.azuredevops.toolwindow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import paol0b.azuredevops.model.Project
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.model.PullRequestStatus
import paol0b.azuredevops.model.Repository
import paol0b.azuredevops.model.Reviewer
import paol0b.azuredevops.model.stableOrganizationKey
import paol0b.azuredevops.services.PullRequestQueryCriteria
import paol0b.azuredevops.toolwindow.filters.PullRequestSearchValue

class PullRequestQueryPlanTest {

    @Test
    fun `relevant to me uses creator OR reviewer queries`() {
        val value = PullRequestSearchValue(
            involvement = PullRequestSearchValue.Involvement.RELEVANT_TO_ME
        )

        assertEquals(
            listOf(
                PullRequestQueryCriteria(creatorId = "me"),
                PullRequestQueryCriteria(reviewerId = "me")
            ),
            value.serverQueryCriteria("me")
        )
    }

    @Test
    fun `assigned to me is reviewer scoped regardless of vote`() {
        val value = PullRequestSearchValue(
            involvement = PullRequestSearchValue.Involvement.ASSIGNED_TO_ME
        )

        assertEquals(
            listOf(PullRequestQueryCriteria(reviewerId = "me")),
            value.serverQueryCriteria("me")
        )
    }

    @Test
    fun `needs review includes zero vote and excludes completed vote`() {
        assertTrue(pullRequest(reviewers = listOf(reviewer("me", 0))).isAwaitingReviewFrom("me"))
        assertTrue(pullRequest(reviewers = listOf(reviewer("me", null))).isAwaitingReviewFrom("me"))
        assertFalse(pullRequest(reviewers = listOf(reviewer("me", 10))).isAwaitingReviewFrom("me"))
        assertFalse(pullRequest(reviewers = listOf(reviewer("me", -5))).isAwaitingReviewFrom("me"))
    }

    @Test
    fun `needs review keeps reviewer-group assignments`() {
        // reviewerId was already matched by Azure DevOps; no direct identity means the
        // assignment may have come through a reviewer group.
        assertTrue(pullRequest(reviewers = listOf(reviewer("a-group", 0))).isAwaitingReviewFrom("me"))
    }

    @Test
    fun `organization key includes repository identity`() {
        val first = pullRequest(id = 42, repositoryId = "repo-a")
        val second = pullRequest(id = 42, repositoryId = "repo-b")

        assertFalse(first.stableOrganizationKey() == second.stableOrganizationKey())
    }

    private fun reviewer(id: String, vote: Int?) = Reviewer(
        id = id,
        displayName = id,
        uniqueName = null,
        imageUrl = null,
        vote = vote,
        isRequired = false
    )

    private fun pullRequest(
        id: Int = 1,
        repositoryId: String = "repo",
        reviewers: List<Reviewer> = emptyList()
    ) = PullRequest(
        pullRequestId = id,
        title = "PR $id",
        description = null,
        sourceRefName = "refs/heads/source",
        targetRefName = "refs/heads/main",
        status = PullRequestStatus.Active,
        createdBy = null,
        creationDate = null,
        closedDate = null,
        mergeStatus = null,
        isDraft = false,
        reviewers = reviewers,
        labels = null,
        url = null,
        repository = Repository(
            id = repositoryId,
            name = repositoryId,
            project = Project(id = "project", name = "Project"),
            remoteUrl = null
        ),
        lastMergeSourceCommit = null,
        lastMergeTargetCommit = null,
        autoCompleteSetBy = null
    )
}
