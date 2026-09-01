package paol0b.azuredevops.model

/** Pull-request ids are unique only within a repository, not across an organization. */
internal fun PullRequest.stableOrganizationKey(): String {
    val projectKey = repository?.project?.id ?: repository?.project?.name.orEmpty()
    val repositoryKey = repository?.id ?: repository?.name.orEmpty()
    return "$projectKey/$repositoryKey/$pullRequestId"
}
