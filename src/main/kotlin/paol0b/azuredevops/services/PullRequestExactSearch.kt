package paol0b.azuredevops.services

import paol0b.azuredevops.model.PullRequest

internal object PullRequestExactSearch {
    fun resolve(query: String, fetch: (PullRequestLookup) -> PullRequest): PullRequest? {
        val lookup = PullRequestLookup.parse(query) ?: return null
        return try { fetch(lookup) } catch (error: AzureDevOpsApiException) {
            val notFound = generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<AzureDevOpsApiException>().any { it.statusCode == 404 }
            if (query.trim().matches(Regex("\\d+")) && notFound) null else throw error
        }
    }
}
