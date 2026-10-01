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
    val repositoryId: String? = null
) {
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

data class PullRequestSearchProgress(val scanned: Int, val complete: Boolean)
data class PullRequestSearchResult(val pullRequests: List<PullRequest>, val progress: PullRequestSearchProgress)

/** The result cap counts matches, not the number of PRs examined for a text search. */
object PullRequestSearchPager {
    fun search(
        query: String,
        pageSize: Int,
        maxResults: Int,
        fetchPage: (Int, Int) -> List<PullRequest>,
        isCancelled: () -> Boolean = { false },
        accept: (PullRequest) -> Boolean = { true },
        onPage: (List<PullRequest>, PullRequestSearchProgress) -> Unit = { _, _ -> }
    ): PullRequestSearchResult {
        require(pageSize > 0 && maxResults > 0)
        val matches = mutableListOf<PullRequest>()
        val seen = mutableSetOf<Int>()
        val text = query.trim()
        var skip = 0
        while (matches.size < maxResults) {
            if (isCancelled()) throw CancellationException()
            val page = fetchPage(pageSize, skip)
            if (isCancelled()) throw CancellationException()
            if (page.isEmpty()) return PullRequestSearchResult(matches.toList(), PullRequestSearchProgress(skip, true))
            skip += page.size
            val fresh = page.filter { seen.add(it.pullRequestId) }
            check(fresh.isNotEmpty()) { "Azure DevOps repeated a PR page. Search results are incomplete; please narrow the project or repository." }
            matches.addAll(fresh.filter {
                accept(it) && (text.isEmpty() || it.title.contains(text, true) ||
                    it.createdBy?.displayName?.contains(text, true) == true ||
                    it.createdBy?.uniqueName?.contains(text, true) == true)
            }.take(maxResults - matches.size))
            onPage(matches.toList(), PullRequestSearchProgress(skip, false))
        }
        return PullRequestSearchResult(matches.toList(), PullRequestSearchProgress(skip, false))
    }
}
