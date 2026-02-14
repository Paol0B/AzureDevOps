package paol0b.azuredevops.services

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Service for validating Personal Access Tokens against Azure DevOps.
 *
 * Checks that the PAT has the minimum required permissions:
 *  - Clone: ability to list repositories (Code → Read)
 *  - Pull Requests: ability to list pull requests (Code → Read)
 *
 * All URLs are built from the provided [serverUrl] — no hardcoded Azure DevOps
 * domain — so the same logic works for dev.azure.com, visualstudio.com, and
 * future self-hosted instances.
 */
@Service(Service.Level.APP)
class PatValidationService {

    private val logger = Logger.getInstance(PatValidationService::class.java)
    private val gson = Gson()

    companion object {
        private const val API_VERSION = "7.0"
        private const val CONNECT_TIMEOUT = 10_000
        private const val READ_TIMEOUT = 10_000

        fun getInstance(): PatValidationService {
            return ApplicationManager.getApplication().getService(PatValidationService::class.java)
        }
    }

    /**
     * Result of PAT validation.
     */
    data class ValidationResult(
        val valid: Boolean,
        val canListProjects: Boolean = false,
        val canListRepos: Boolean = false,
        val canListPullRequests: Boolean = false,
        val message: String
    )

    /**
     * Validates a PAT against the given organization URL and returns a
     * [ValidationResult] describing which permissions were verified.
     *
     * @param serverUrl Organisation base URL, e.g. `https://dev.azure.com/myorg`
     * @param pat       The Personal Access Token to validate
     */
    fun validate(serverUrl: String, pat: String): ValidationResult {
        val normalizedUrl = serverUrl.trimEnd('/')
        val authHeader = createBasicAuthHeader(pat)

        // 1. List projects — basic connectivity + project read permission
        val projectsUrl = "$normalizedUrl/_apis/projects?\$top=1&api-version=$API_VERSION"
        val projectsResult = executeGet(projectsUrl, authHeader)
        if (!projectsResult.success) {
            return ValidationResult(
                valid = false,
                message = describeHttpError(projectsResult.statusCode, "list projects")
            )
        }

        // Extract first project name for further checks
        val firstProject = extractFirstProjectName(projectsResult.body)
        if (firstProject == null) {
            return ValidationResult(
                valid = true,
                canListProjects = true,
                message = "PAT is valid but no projects found in the organization."
            )
        }

        val encodedProject = encodePathSegment(firstProject)

        // 2. List repositories — Code (Read) permission
        val reposUrl = "$normalizedUrl/$encodedProject/_apis/git/repositories?\$top=1&api-version=$API_VERSION"
        val reposResult = executeGet(reposUrl, authHeader)
        val canListRepos = reposResult.success

        // 3. List pull requests — also Code (Read) but on a different endpoint
        val prsUrl = "$normalizedUrl/$encodedProject/_apis/git/pullrequests?\$top=1&api-version=$API_VERSION"
        val prsResult = executeGet(prsUrl, authHeader)
        val canListPrs = prsResult.success

        val issues = mutableListOf<String>()
        if (!canListRepos) issues.add("Code (Read) — required for Clone")
        if (!canListPrs) issues.add("Code (Read) — required for Pull Requests")

        return if (issues.isEmpty()) {
            ValidationResult(
                valid = true,
                canListProjects = true,
                canListRepos = true,
                canListPullRequests = true,
                message = "PAT validated — Clone and Pull Request access confirmed."
            )
        } else {
            ValidationResult(
                valid = false,
                canListProjects = true,
                canListRepos = canListRepos,
                canListPullRequests = canListPrs,
                message = "PAT is missing permissions: ${issues.joinToString("; ")}"
            )
        }
    }

    // ---- internal helpers ----

    private data class HttpResult(val success: Boolean, val statusCode: Int, val body: String)

    private fun executeGet(url: String, authHeader: String): HttpResult {
        return try {
            val connection = URI.create(url).toURL().openConnection() as java.net.HttpURLConnection
            try {
                connection.requestMethod = "GET"
                connection.setRequestProperty("Authorization", authHeader)
                connection.setRequestProperty("Accept", "application/json")
                connection.connectTimeout = CONNECT_TIMEOUT
                connection.readTimeout = READ_TIMEOUT

                val code = connection.responseCode
                val body = if (code in 200..299) {
                    connection.inputStream.bufferedReader().use { it.readText() }
                } else {
                    connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                }
                HttpResult(code in 200..299, code, body)
            } finally {
                connection.disconnect()
            }
        } catch (e: Exception) {
            logger.warn("PAT validation request failed: ${e.message}")
            HttpResult(false, -1, e.message ?: "Connection error")
        }
    }

    private fun extractFirstProjectName(json: String): String? {
        return try {
            val obj = gson.fromJson(json, JsonObject::class.java)
            val arr = obj.getAsJsonArray("value")
            if (arr != null && arr.size() > 0) {
                arr[0].asJsonObject.get("name")?.asString
            } else null
        } catch (_: Exception) {
            null
        }
    }

    private fun createBasicAuthHeader(pat: String): String {
        val credentials = ":$pat"
        val encoded = Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
        return "Basic $encoded"
    }

    private fun encodePathSegment(value: String): String {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")
    }

    private fun describeHttpError(statusCode: Int, action: String): String {
        return when (statusCode) {
            401 -> "Authentication failed (401). The PAT is invalid or revoked."
            403 -> "Insufficient permissions (403) to $action."
            404 -> "Organization not found (404). Please check the URL."
            -1  -> "Could not connect to Azure DevOps. Please check the URL and your network."
            else -> "HTTP $statusCode while trying to $action."
        }
    }
}
