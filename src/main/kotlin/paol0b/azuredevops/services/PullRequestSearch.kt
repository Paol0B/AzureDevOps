package paol0b.azuredevops.services

import paol0b.azuredevops.model.PullRequest
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.CancellationException

/** An exact lookup never depends on the list's filters or pagination cap. */
data class PullRequestLookup(val id: Int, val project: String? = null, val repository: String? = null, val serverBase: String? = null) {
    fun requireSameServer(baseUrl: String) {
        val supplied = serverBase ?: return
        require(serverIdentity(URI(supplied)) == serverIdentity(URI(baseUrl))) {
            "This PR URL belongs to another organization or server. Select that Azure DevOps account first."
        }
    }

    private data class ServerIdentity(val scheme: String, val host: String, val port: Int, val path: String)

    private fun serverIdentity(uri: URI): ServerIdentity {
        val scheme = uri.scheme.lowercase()
        val host = uri.host.lowercase()
        val port = if (uri.port >= 0) uri.port else if (scheme == "https") 443 else 80
        val path = uri.rawPath.trim('/')
        val cloudOrg = when {
            host == "dev.azure.com" && path.isNotEmpty() && !path.contains('/') ->
                URLDecoder.decode(path.replace("+", "%2B"), StandardCharsets.UTF_8)
            host.endsWith(".visualstudio.com") && (path.isEmpty() || path.equals("DefaultCollection", true)) ->
                host.removeSuffix(".visualstudio.com")
            else -> null
        }
        return if (cloudOrg != null) ServerIdentity(scheme, "dev.azure.com", port, cloudOrg.lowercase())
        else ServerIdentity(scheme, host, port, path.lowercase())
    }

    companion object {
        fun parse(query: String?): PullRequestLookup? {
            val text = query?.trim().orEmpty()
            if (text.isEmpty()) return null
            if (text.matches(Regex("#?\\d+"))) {
                val id = text.removePrefix("#").toIntOrNull()
                require(id != null && id > 0) { "Enter a valid positive PR ID." }
                return PullRequestLookup(id)
            }
            if (!text.startsWith("https://", true) && !text.startsWith("http://", true)) return null
            val uri = try { URI(text) } catch (_: Exception) { throw IllegalArgumentException("Enter a valid PR URL.") }
            require(uri.host != null && uri.userInfo == null) { "Enter a valid PR URL." }
            val parts = uri.rawPath.trim('/').split('/')
            val gitIndex = parts.indexOfFirst { it == "_git" }
            require(gitIndex >= 1 && parts.size == gitIndex + 4 && parts[gitIndex + 2].equals("pullrequest", true)) {
                "Enter an Azure DevOps PR URL ending in /_git/repository/pullrequest/ID."
            }
            val id = parts.last().toIntOrNull()
            require(id != null && id > 0) { "Enter a valid positive PR ID." }
            fun decode(value: String) = URLDecoder.decode(value.replace("+", "%2B"), StandardCharsets.UTF_8)
            val prefix = parts.take(gitIndex - 1).joinToString("/")
            val base = "${uri.scheme}://${uri.rawAuthority}" + if (prefix.isEmpty()) "" else "/$prefix"
            return PullRequestLookup(id, decode(parts[gitIndex - 1]), decode(parts[gitIndex + 1]), base)
        }
    }
}

data class PullRequestSearchCriteria(
    val status: String,
    val creatorId: String? = null,
    val reviewerId: String? = null,
    val repositoryId: String? = null,
    val relevantUserId: String? = null
) {
    /** Azure criteria are ANDed; creator OR reviewer requires two server queries. */
    fun alternatives(): List<PullRequestSearchCriteria> {
        val userId = relevantUserId ?: return listOf(this)
        require(userId.isNotBlank()) { "Unable to identify your Azure DevOps account." }
        return listOf(
            copy(creatorId = userId, reviewerId = null, relevantUserId = null),
            copy(reviewerId = userId, relevantUserId = null)
        )
    }

    fun listUrl(endpoint: String, pageSize: Int, skip: Int): String {
        val parameters = linkedMapOf("searchCriteria.status" to status)
        creatorId?.let { parameters["searchCriteria.creatorId"] = it }
        reviewerId?.let { parameters["searchCriteria.reviewerId"] = it }
        repositoryId?.let { parameters["searchCriteria.repositoryId"] = it }
        parameters["\$top"] = pageSize.toString()
        parameters["\$skip"] = skip.toString()
        parameters["api-version"] = "7.0"
        return endpoint + "?" + parameters.entries.joinToString("&") { (key, value) ->
            "${URLEncoder.encode(key, StandardCharsets.UTF_8)}=${URLEncoder.encode(value, StandardCharsets.UTF_8)}"
        }
    }
}

data class PullRequestSearchProgress(val scanned: Int, val complete: Boolean, val cancelled: Boolean = false)

/** Share one budget across all scopes of a search to bound rare or absent text queries. */
class PullRequestSearchBudget(val maxScanned: Int = 5000, val maxPages: Int = 100) {
    init { require(maxScanned > 0 && maxPages > 0) }
    private var scanned = 0
    private var pages = 0
    val remainingScanned: Int get() = (maxScanned - scanned).coerceAtLeast(0)
    val exhausted: Boolean get() = remainingScanned == 0 || pages >= maxPages

    internal fun recordPage(size: Int) {
        pages++
        scanned += size
    }
}
data class PullRequestSearchResult(val pullRequests: List<PullRequest>, val progress: PullRequestSearchProgress)

/** The result cap counts matches, not the number of PRs examined for a text search. */
object PullRequestSearchPager {
    /** Share the result cap and scan budget across project scopes and OR alternatives. */
    fun searchScopes(
        query: String,
        criteria: PullRequestSearchCriteria,
        endpoints: List<String>,
        pageSize: Int,
        maxResults: Int,
        fetchPage: (String, PullRequestSearchCriteria, Int, Int) -> List<PullRequest>,
        isCancelled: () -> Boolean = { false },
        accept: (PullRequest) -> Boolean = { true },
        onPage: (List<PullRequest>, PullRequestSearchProgress) -> Unit = { _, _ -> },
        budget: PullRequestSearchBudget = PullRequestSearchBudget()
    ): PullRequestSearchResult {
        val searches = endpoints.flatMap { endpoint -> criteria.alternatives().map { endpoint to it } }
        val results = linkedMapOf<Triple<String?, String?, Int>, PullRequest>()
        var scanned = 0
        var complete = true
        for ((index, scope) in searches.withIndex()) {
            val previouslyScanned = scanned
            val scopeResult = search(
                query, pageSize, maxResults - results.size,
                fetchPage = { top, skip -> fetchPage(scope.first, scope.second, top, skip) },
                isCancelled = isCancelled, budget = budget,
                accept = { identity(it) !in results && accept(it) },
                onPage = { matches, progress ->
                    onPage((results.values + matches).distinctBy(::identity),
                        PullRequestSearchProgress(previouslyScanned + progress.scanned, false))
                }
            )
            scopeResult.pullRequests.forEach { results[identity(it)] = it }
            scanned += scopeResult.progress.scanned
            complete = complete && scopeResult.progress.complete
            if (results.size >= maxResults || budget.exhausted) {
                complete = complete && index == searches.lastIndex
                break
            }
        }
        return PullRequestSearchResult(results.values.toList(), PullRequestSearchProgress(scanned, complete))
    }

    private fun identity(pr: PullRequest) = Triple(
        pr.repository?.project?.id ?: pr.repository?.project?.name,
        pr.repository?.id ?: pr.repository?.name,
        pr.pullRequestId
    )

    fun search(
        query: String,
        pageSize: Int,
        maxResults: Int,
        fetchPage: (Int, Int) -> List<PullRequest>,
        isCancelled: () -> Boolean = { false },
        accept: (PullRequest) -> Boolean = { true },
        onPage: (List<PullRequest>, PullRequestSearchProgress) -> Unit = { _, _ -> },
        budget: PullRequestSearchBudget = PullRequestSearchBudget()
    ): PullRequestSearchResult {
        require(pageSize > 0 && maxResults > 0)
        val matches = mutableListOf<PullRequest>()
        val seen = mutableSetOf<Triple<String?, String?, Int>>()
        val text = query.trim()
        var skip = 0
        var scanned = 0
        while (matches.size < maxResults) {
            if (isCancelled()) throw CancellationException()
            if (budget.exhausted) break
            val requestedSize = minOf(pageSize, budget.remainingScanned)
            val fetched = fetchPage(requestedSize, skip)
            budget.recordPage(fetched.size)
            val page = fetched.take(requestedSize)
            if (isCancelled()) throw CancellationException()
            if (page.isEmpty()) return PullRequestSearchResult(matches.toList(), PullRequestSearchProgress(scanned, true))
            // Azure DevOps applies $skip before dropping PRs the caller cannot read, so advance by the requested size
            skip += requestedSize
            scanned += page.size
            val fresh = page.filter { seen.add(identity(it)) }
            check(fresh.isNotEmpty()) { "Azure DevOps repeated a PR page. Search results are incomplete; please narrow the project or repository." }
            matches.addAll(fresh.filter {
                accept(it) && (text.isEmpty() || it.title.contains(text, true) ||
                    it.createdBy?.displayName?.contains(text, true) == true ||
                    it.createdBy?.uniqueName?.contains(text, true) == true)
            }.take(maxResults - matches.size))
            onPage(matches.toList(), PullRequestSearchProgress(scanned, false))
        }
        return PullRequestSearchResult(matches.toList(), PullRequestSearchProgress(scanned, false))
    }
}
