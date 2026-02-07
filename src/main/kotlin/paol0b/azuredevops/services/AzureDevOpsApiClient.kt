package paol0b.azuredevops.services

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import paol0b.azuredevops.model.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Client for communicating with Azure DevOps REST API
 * API documentation: https://learn.microsoft.com/en-us/rest/api/azure/devops/
 */
@Service(Service.Level.PROJECT)
class AzureDevOpsApiClient(private val project: Project) {

    private val gson = Gson()
    private val logger = Logger.getInstance(AzureDevOpsApiClient::class.java)
    private val httpClient = OkHttpClient()

    // Cache for current user ID to avoid repeated API calls
    @Volatile
    private var cachedUserId: String? = null

    companion object {
        private const val API_VERSION = "7.0"
        
        private const val AUTH_ERROR_MESSAGE = """Authentication required. Please login:
1. Go to File → Settings → Tools → Azure DevOps Accounts
2. Click 'Add' button to add your account  
3. Complete the authentication in your browser

The plugin will automatically use your authenticated account for this repository."""
        
        fun getInstance(project: Project): AzureDevOpsApiClient {
            return project.getService(AzureDevOpsApiClient::class.java)
        }
    }

    // region HTTP Methods

    /**
     * Executes a GET request using OkHttp
     */
    @Throws(IOException::class, AzureDevOpsApiException::class)
    private fun executeGet(urlString: String, token: String): String {
        val request = Request.Builder()
            .url(urlString)
            .get()
            .addHeader("Authorization", createAuthHeader(token))
            .addHeader("Accept", "application/json")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                responseBody
            } else {
                throw handleErrorResponse(response.code, responseBody)
            }
        }
    }

    /**
     * Executes a POST request using OkHttp
     */
    @Throws(IOException::class, AzureDevOpsApiException::class)
    private fun executePost(urlString: String, body: Any, token: String): String {
        val mediaType = "application/json; charset=UTF-8".toMediaType()
        val jsonBody = gson.toJson(body)
        val requestBody = jsonBody.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(urlString)
            .post(requestBody)
            .addHeader("Authorization", createAuthHeader(token))
            .addHeader("Content-Type", "application/json; charset=UTF-8")
            .addHeader("Accept", "application/json")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                responseBody
            } else {
                throw handleErrorResponse(response.code, responseBody)
            }
        }
    }

    /**
     * Executes a PUT request using OkHttp
     */
    @Throws(IOException::class, AzureDevOpsApiException::class)
    private fun executePut(urlString: String, body: Any, token: String): String {
        val mediaType = "application/json; charset=UTF-8".toMediaType()
        val jsonBody = gson.toJson(body)
        val requestBody = jsonBody.toRequestBody(mediaType)

        val request = Request.Builder()
            .url(urlString)
            .put(requestBody)
            .addHeader("Authorization", createAuthHeader(token))
            .addHeader("Content-Type", "application/json; charset=UTF-8")
            .addHeader("Accept", "application/json")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                responseBody
            } else {
                throw handleErrorResponse(response.code, responseBody)
            }
        }
    }

    /**
     * Executes a PATCH request using OkHttp
     */
    @Throws(IOException::class, AzureDevOpsApiException::class)
    private fun executePatch(urlString: String, body: String, token: String): String {
        val mediaType = "application/json; charset=UTF-8".toMediaType()
        val requestBody = body.toRequestBody(mediaType)

        // Build URL properly with query parameters using HttpUrl
        val httpUrl = urlString.toHttpUrl().newBuilder()
            .addQueryParameter("api-version", "7.1")
            .build()

        val request = Request.Builder()
            .url(httpUrl)
            .patch(requestBody)
            .addHeader("Authorization", createAuthHeader(token))
            .addHeader("Accept", "application/json")
            .addHeader("Content-Type", "application/json; charset=UTF-8")
            .build()

        logger.info("Executing PATCH request to: $httpUrl")

        return httpClient.newCall(request).execute().use { response ->
            val responseBody = response.body?.string() ?: ""

            if (response.isSuccessful) {
                responseBody
            } else {
                logger.warn("PATCH request failed - Status: ${response.code}, Body: $responseBody")
                throw handleErrorResponse(response.code, responseBody)
            }
        }
    }

    /**
     * Creates the Basic Auth header with the PAT
     */
    private fun createAuthHeader(token: String): String {
        val credentials = ":$token"
        val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
        return "Basic $encodedCredentials"
    }

    /**
     * Handles error responses from Azure DevOps
     */
    private fun handleErrorResponse(statusCode: Int, errorBody: String): AzureDevOpsApiException {
        logger.warn("Azure DevOps API error - Status: $statusCode, Body: $errorBody")
        
        // Try to parse the error
        val errorMessage = try {
            val error = gson.fromJson(errorBody, AzureDevOpsErrorResponse::class.java)
            error?.message ?: "Unknown error"
        } catch (e: Exception) {
            logger.warn("Failed to parse error response", e)
            errorBody.ifEmpty { "HTTP Error $statusCode" }
        }

        return when (statusCode) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> 
                AzureDevOpsApiException("Authentication failed (401). Please login:\n" +
                    "1. Go to File → Settings → Tools → Azure DevOps Accounts\n" +
                    "2. Click 'Add' to login with your Microsoft account\n" +
                    "3. Complete the authentication in your browser")
            HttpURLConnection.HTTP_FORBIDDEN ->
                AzureDevOpsApiException("Insufficient permissions (403). Your account doesn't have access to this resource.\n" +
                    "Please check that you have the required permissions in Azure DevOps.")
            HttpURLConnection.HTTP_NOT_FOUND ->
                AzureDevOpsApiException("Resource not found (404).\n" +
                    "Please verify that the Organization, Project, and Repository names are correct\n" +
                    "and that you have access to them in Azure DevOps.")
            HttpURLConnection.HTTP_CONFLICT ->
                AzureDevOpsApiException("Conflict: $errorMessage (409)")
            HttpURLConnection.HTTP_BAD_REQUEST ->
                AzureDevOpsApiException("Invalid request: $errorMessage (400)")
            else ->
                AzureDevOpsApiException("HTTP Error $statusCode: $errorMessage")
        }
    }

    // endregion
    
    /**
     * Helper function to build API URLs with proper URL encoding for project and repository names
     * This handles special characters like accented letters (à, è, ì, etc.) and spaces
     */
    fun buildApiUrl(project: String, repository: String, endpoint: String): String {
        val configService = AzureDevOpsConfigService.getInstance(this.project)
        val encodedProject = encodePathSegment(project)
        val encodedRepo = encodePathSegment(repository)

        return "${configService.getApiBaseUrl()}/$encodedProject/_apis/git/repositories/$encodedRepo$endpoint"
    }

    /**
     * Helper function to build organization-level API URLs
     * Used for endpoints that don't require project/repository scope
     */
    fun buildOrgApiUrl(endpoint: String): String {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val baseUrl = configService.getApiBaseUrl()
        return "$baseUrl/_apis$endpoint"
    }

    fun buildRepositoryWebUrl(projectName: String, repositoryName: String): String {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val baseUrl = configService.getApiBaseUrl()
        val encodedProject = encodePathSegment(projectName)
        val encodedRepo = encodePathSegment(repositoryName)
        return "$baseUrl/$encodedProject/_git/$encodedRepo"
    }

    fun buildPullRequestWebUrl(projectName: String, repositoryName: String, pullRequestId: Int): String {
        return "${buildRepositoryWebUrl(projectName, repositoryName)}/pullrequest/$pullRequestId"
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, StandardCharsets.UTF_8.toString()).replace("+", "%20")
    }

    /**
     * Creates a Pull Request on Azure DevOps
     * @param sourceBranch Source branch (e.g., "refs/heads/feature/xyz")
     * @param targetBranch Target branch (e.g., "refs/heads/main")
     * @param title PR title
     * @param description PR description (optional)
     * @param requiredReviewers List of required reviewers
     * @param optionalReviewers List of optional reviewers
     * @return PullRequestResponse if successful, otherwise throws an exception
     */
    @Throws(AzureDevOpsApiException::class)
    fun createPullRequest(
        sourceBranch: String,
        targetBranch: String,
        title: String,
        description: String = "",
        requiredReviewers: List<Identity> = emptyList(),
        optionalReviewers: List<Identity> = emptyList()
    ): PullRequestResponse {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        // Create the list of reviewers in the format required by the API
        val reviewers = mutableListOf<ReviewerRequest>()
        requiredReviewers.forEach { identity ->
            identity.id?.let { id ->
                reviewers.add(ReviewerRequest(id = id, isRequired = true))
            }
        }
        optionalReviewers.forEach { identity ->
            identity.id?.let { id ->
                reviewers.add(ReviewerRequest(id = id, isRequired = false))
            }
        }

        val request = CreatePullRequestRequest(
            sourceRefName = sourceBranch,
            targetRefName = targetBranch,
            title = title,
            description = description,
            reviewers = if (reviewers.isNotEmpty()) reviewers else null
        )

        // URL: https://dev.azure.com/{organization}/{project}/_apis/git/repositories/{repositoryId}/pullrequests?api-version=7.0
        val url = buildApiUrl(config.project, config.repository, "/pullrequests?api-version=$API_VERSION")
        
        logger.info("Creating Pull Request: $sourceBranch -> $targetBranch")
        logger.info("Reviewers: ${reviewers.size} (${requiredReviewers.size} required, ${optionalReviewers.size} optional)")
        
        return try {
            val response = executePost(url, request, config.personalAccessToken)
            gson.fromJson(response, PullRequestResponse::class.java)
        } catch (e: Exception) {
            logger.error("Failed to create pull request", e)
            throw AzureDevOpsApiException("Error while creating Pull Request: ${e.message}", e)
        }
    }

    /**
     * Retrieves the list of Pull Requests for the repository
     * @param status Filter by status (e.g., "active", "completed", "abandoned", "all")
     * @param top Maximum number of PRs to retrieve
     * @return List of Pull Requests
     */
    @Throws(AzureDevOpsApiException::class)
    fun getPullRequests(
        status: String = "active",
        top: Int = 100
    ): List<PullRequest> {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val statusParam = if (status == "all") "all" else status
        val url = buildApiUrl(config.project, config.repository, 
            "/pullrequests?searchCriteria.status=$statusParam&\$top=$top&api-version=$API_VERSION")
        
        logger.info("Fetching Pull Requests (status: $statusParam, top: $top)")
        
        return try {
            val response = executeGet(url, config.personalAccessToken)
            val listResponse = gson.fromJson(response, PullRequestListResponse::class.java)
            listResponse.value
        } catch (e: Exception) {
            logger.error("Failed to fetch pull requests", e)
            throw AzureDevOpsApiException("Error while retrieving Pull Requests: ${e.message}", e)
        }
    }

    /**
     * Retrieves Pull Requests from all projects in the organization
     * Uses the organization-level API to get PRs across all repositories
     * @param status Filter by status (e.g., "active", "completed", "abandoned", "all")
     * @param top Maximum number of PRs to retrieve
     * @return List of Pull Requests from all organization projects
     */
    @Throws(AzureDevOpsApiException::class)
    fun getAllOrganizationPullRequests(
        status: String = "active",
        top: Int = 100
    ): List<PullRequest> {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val statusParam = if (status == "all") "all" else status
        val url = buildOrgApiUrl("/git/pullrequests?searchCriteria.status=$statusParam&\$top=$top&api-version=$API_VERSION")

        logger.info("Fetching organization-wide Pull Requests (status: $statusParam, top: $top)")

        return try {
            val response = executeGet(url, config.personalAccessToken)
            val listResponse = gson.fromJson(response, PullRequestListResponse::class.java)
            listResponse.value
        } catch (e: Exception) {
            logger.error("Failed to fetch organization pull requests", e)
            throw AzureDevOpsApiException("Error while retrieving organization Pull Requests: ${e.message}", e)
        }
    }

    /**
     * Retrieves a single Pull Request with all details
     * @param pullRequestId PR ID
     * @return Complete Pull Request
     */
    @Throws(AzureDevOpsApiException::class)
    fun getPullRequest(pullRequestId: Int): PullRequest {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val url = buildApiUrl(config.project, config.repository, "/pullrequests/$pullRequestId?api-version=$API_VERSION")
        
        logger.info("Fetching Pull Request #$pullRequestId")
        
        return try {
            val response = executeGet(url, config.personalAccessToken)
            gson.fromJson(response, PullRequest::class.java)
        } catch (e: Exception) {
            logger.error("Failed to fetch pull request #$pullRequestId", e)
            throw AzureDevOpsApiException("Error while retrieving Pull Request: ${e.message}", e)
        }
    }

    /**
     * Retrieves a single Pull Request with all details from a specific project/repository
     * Used when accessing PRs from other repositories in the organization
     * @param pullRequestId PR ID
     * @param projectName Project name (can be null to use current project)
     * @param repositoryId Repository ID or name (can be null to use current repository)
     * @return Complete Pull Request
     */
    @Throws(AzureDevOpsApiException::class)
    fun getPullRequest(pullRequestId: Int, projectName: String?, repositoryId: String?): PullRequest {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val effectiveProject = projectName ?: config.project
        val effectiveRepo = repositoryId ?: config.repository

        val url = buildApiUrl(effectiveProject, effectiveRepo, "/pullrequests/$pullRequestId?api-version=$API_VERSION")

        logger.info("Fetching Pull Request #$pullRequestId from $effectiveProject/$effectiveRepo")

        return try {
            val response = executeGet(url, config.personalAccessToken)
            gson.fromJson(response, PullRequest::class.java)
        } catch (e: Exception) {
            logger.error("Failed to fetch pull request #$pullRequestId from $effectiveProject/$effectiveRepo", e)
            throw AzureDevOpsApiException("Error while retrieving Pull Request: ${e.message}", e)
        }
    }

    /**
     * Searches for an active Pull Request between two specific branches
     * @param sourceBranch Source branch (e.g., "refs/heads/feature")
     * @param targetBranch Target branch (e.g., "refs/heads/main")
     * @return The existing PR or null if not found
     */
    @Throws(AzureDevOpsApiException::class)
    fun findActivePullRequest(sourceBranch: String, targetBranch: String): PullRequest? {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val url = buildApiUrl(
            config.project, 
            config.repository, 
            "/pullrequests?searchCriteria.status=active&searchCriteria.sourceRefName=$sourceBranch&searchCriteria.targetRefName=$targetBranch&api-version=$API_VERSION"
        )
        
        logger.info("Searching for active PR from $sourceBranch to $targetBranch")
        
        return try {
            val response = executeGet(url, config.personalAccessToken)
            val prList = gson.fromJson(response, PullRequestListResponse::class.java)
            prList.value.firstOrNull()
        } catch (e: Exception) {
            logger.error("Failed to search for PR", e)
            null
        }
    }

    /**
     * Retrieves all comment threads for a Pull Request
     * @param pullRequestId PR ID
     * @return List of comment threads
     */
    @Throws(AzureDevOpsApiException::class)
    fun getCommentThreads(pullRequestId: Int): List<CommentThread> {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val url = buildApiUrl(config.project, config.repository, "/pullRequests/$pullRequestId/threads?api-version=$API_VERSION")
        
        logger.info("Fetching comment threads for PR #$pullRequestId")
        logger.info("URL: $url")
        
        return try {
            val response = executeGet(url, config.personalAccessToken)
            
            // Raw response log for debugging
            logger.info("=== RAW RESPONSE (first 2000 chars) ===")
            logger.info(response.take(2000))
            logger.info("=== END RAW RESPONSE ===")
            
            val listResponse = gson.fromJson(response, CommentThreadListResponse::class.java)
            val threads = listResponse.value.filter { it.isDeleted != true }
            
            // Detailed log of each thread
            threads.forEachIndexed { index, thread ->
                logger.info("Thread $index: id=${thread.id}")
                logger.info("  - pullRequestThreadContext: ${thread.pullRequestThreadContext}")
                logger.info("  - threadContext: ${thread.threadContext}")
                logger.info("  - comments count: ${thread.comments?.size}")
                logger.info("  - status: ${thread.status}")
            }
            
            threads
        } catch (e: Exception) {
            logger.error("Failed to fetch comment threads", e)
            throw AzureDevOpsApiException("Error while retrieving comments: ${e.message}", e)
        }
    }

    /**
     * Retrieves all comment threads for a Pull Request from a specific project/repository
     * Used when accessing PRs from other repositories in the organization
     * @param pullRequestId PR ID
     * @param projectName Project name (can be null to use current project)
     * @param repositoryId Repository ID or name (can be null to use current repository)
     * @return List of comment threads
     */
    @Throws(AzureDevOpsApiException::class)
    fun getCommentThreads(pullRequestId: Int, projectName: String?, repositoryId: String?): List<CommentThread> {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val effectiveProject = projectName ?: config.project
        val effectiveRepo = repositoryId ?: config.repository

        val url = buildApiUrl(effectiveProject, effectiveRepo, "/pullRequests/$pullRequestId/threads?api-version=$API_VERSION")

        logger.info("Fetching comment threads for PR #$pullRequestId from $effectiveProject/$effectiveRepo")

        return try {
            val response = executeGet(url, config.personalAccessToken)
            val listResponse = gson.fromJson(response, CommentThreadListResponse::class.java)
            listResponse.value.filter { it.isDeleted != true }
        } catch (e: Exception) {
            logger.error("Failed to fetch comment threads from external repo", e)
            throw AzureDevOpsApiException("Error while retrieving comments: ${e.message}", e)
        }
    }

    /**
     * Adds a comment to an existing thread
     * @param pullRequestId PR ID
     * @param threadId Thread ID
     * @param content Comment content
     * @return The created comment
     */
    @Throws(AzureDevOpsApiException::class)
    fun addCommentToThread(pullRequestId: Int, threadId: Int, content: String): Comment {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val request = CreateCommentRequest(content = content)
        val url = buildApiUrl(config.project, config.repository, "/pullRequests/$pullRequestId/threads/$threadId/comments?api-version=$API_VERSION")
        
        logger.info("Adding comment to thread #$threadId in PR #$pullRequestId")
        
        return try {
            val response = executePost(url, request, config.personalAccessToken)
            gson.fromJson(response, Comment::class.java)
        } catch (e: Exception) {
            logger.error("Failed to add comment", e)
            throw AzureDevOpsApiException("Error while adding comment: ${e.message}", e)
        }
    }

    /**
     * Updates the status of a comment thread (e.g., resolves or reopens)
     * @param pullRequestId PR ID
     * @param threadId Thread ID
     * @param status New status (e.g., "active", "fixed", "closed")
     * @return Updated thread
     */
    @Throws(AzureDevOpsApiException::class)
    fun updateThreadStatus(pullRequestId: Int, threadId: Int, status: ThreadStatus) {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        // Create the request with status and empty comments array
        val request = UpdateThreadStatusRequest(status)
        val jsonBody = gson.toJson(request)
        
        // Build URL without api-version (will be in header)
        val baseUrl = buildApiUrl(config.project, config.repository, "/pullRequests/$pullRequestId/threads/$threadId?")
        
        logger.info("Updating thread #$threadId status to ${status.getDisplayName()} (API value: ${status.toApiValue()}) in PR #$pullRequestId")
        logger.info("PATCH URL: $baseUrl")
        logger.info("Request body: $jsonBody")
        
        try {
            val response = executePatch(baseUrl, jsonBody, config.personalAccessToken)
            logger.info("Thread status updated successfully. Response: $response")
        } catch (e: Exception) {
            logger.error("Failed to update thread status", e)
            throw AzureDevOpsApiException("Error while updating thread status: ${e.message}", e)
        }
    }



    /**
     * Finds the Pull Request associated with a specific branch
     * @param branchName Branch name (without refs/heads/)
     * @return The active PR associated with the branch, or null if it does not exist
     */
    @Throws(AzureDevOpsApiException::class)
    fun findPullRequestForBranch(branchName: String): PullRequest? {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val refName = "refs/heads/$branchName"
        val url = buildApiUrl(config.project, config.repository, 
            "/pullrequests?searchCriteria.status=active&searchCriteria.sourceRefName=$refName&api-version=$API_VERSION")
        
        logger.info("Searching for active PR with source branch: $branchName")
        
        return try {
            val response = executeGet(url, config.personalAccessToken)
            val listResponse = gson.fromJson(response, PullRequestListResponse::class.java)
            listResponse.value.firstOrNull()
        } catch (e: Exception) {
            logger.error("Failed to find PR for branch $branchName", e)
            null // Returns null instead of throwing exception
        }
    }

    /**
     * Gets all file changes of a Pull Request
     * @param pullRequestId PR ID
     * @return List of changes
     */
    @Throws(AzureDevOpsApiException::class)
    fun getPullRequestChanges(pullRequestId: Int): List<PullRequestChange> {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()
        
        // First get the PR to have the last iteration
        getPullRequest(pullRequestId)
        
        // Now get the changes of the last iteration
        val url = buildApiUrl(
            config.project,
            config.repository,
            "/pullRequests/$pullRequestId/iterations/1/changes"
        ) + "?api-version=$API_VERSION"

        logger.info("Getting PR changes from: $url")

        val response = executeGet(url, config.personalAccessToken)

        return try {
            val changesResponse = gson.fromJson(response, PullRequestChanges::class.java)
            changesResponse.changeEntries ?: emptyList()
        } catch (e: JsonSyntaxException) {
            logger.error("Failed to parse PR changes response", e)
            emptyList()
        }
    }

    /**
     * Retrieves the changes in a Pull Request from a specific project/repository
     * Used when accessing PRs from other repositories in the organization
     * @param pullRequestId PR ID
     * @param projectName Project name (can be null to use current project)
     * @param repositoryId Repository ID or name (can be null to use current repository)
     * @return List of changes
     */
    @Throws(AzureDevOpsApiException::class)
    fun getPullRequestChanges(pullRequestId: Int, projectName: String?, repositoryId: String?): List<PullRequestChange> {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        val effectiveProject = projectName ?: config.project
        val effectiveRepo = repositoryId ?: config.repository

        // Now get the changes of the last iteration
        val url = buildApiUrl(
            effectiveProject,
            effectiveRepo,
            "/pullRequests/$pullRequestId/iterations/1/changes"
        ) + "?api-version=$API_VERSION"

        logger.info("Getting PR changes from: $url")
        
        val response = executeGet(url, config.personalAccessToken)
        
        return try {
            val changesResponse = gson.fromJson(response, PullRequestChanges::class.java)
            changesResponse.changeEntries ?: emptyList()
        } catch (e: JsonSyntaxException) {
            logger.error("Failed to parse PR changes response", e)
            emptyList()
        }
    }

    /**
     * Retrieves the content of a file at a specific commit
     * @param commitId SHA of the commit
     * @param filePath Path of the file (e.g., "/src/main/Program.cs")
     * @return Content of the file as a string
     */
    @Throws(AzureDevOpsApiException::class)
    fun getFileContent(commitId: String, filePath: String): String {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()
        
        // Encode the path for URL
        val encodedPath = URLEncoder.encode(filePath, StandardCharsets.UTF_8.toString())
        
        // Endpoint to get the file content
        // includeContent=true returns the content in the "content" field
        val url = buildApiUrl(
            config.project,
            config.repository,
            "/items?path=$encodedPath&versionDescriptor.version=$commitId&versionDescriptor.versionType=commit&includeContent=true"
        ) + "&api-version=$API_VERSION"
        
        logger.info("Getting file content from: $url")
        
        val response = executeGet(url, config.personalAccessToken)
        
        // Parse the JSON response to extract the content
        return try {
            val jsonObject = gson.fromJson(response, com.google.gson.JsonObject::class.java)
            // The content is in the "content" field
            val content = jsonObject.get("content")?.asString ?: ""
            logger.info("Extracted content: ${content.length} characters")
            content
        } catch (e: Exception) {
            logger.error("Failed to parse file content response", e)
            logger.error("Response was: $response")
            ""
        }
    }

    /**
     * Retrieves the content of a file at a specific commit from a specific project/repository
     * Used when accessing files from other repositories in the organization
     * @param commitId SHA of the commit
     * @param filePath Path of the file (e.g., "/src/main/Program.cs")
     * @param projectName Project name (can be null to use current project)
     * @param repositoryId Repository ID or name (can be null to use current repository)
     * @return Content of the file as a string
     */
    @Throws(AzureDevOpsApiException::class)
    fun getFileContent(commitId: String, filePath: String, projectName: String?, repositoryId: String?): String {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        val effectiveProject = projectName ?: config.project
        val effectiveRepo = repositoryId ?: config.repository

        // Encode the path for URL
        val encodedPath = URLEncoder.encode(filePath, StandardCharsets.UTF_8.toString())

        // Endpoint to get the file content
        val url = buildApiUrl(
            effectiveProject,
            effectiveRepo,
            "/items?path=$encodedPath&versionDescriptor.version=$commitId&versionDescriptor.versionType=commit&includeContent=true"
        ) + "&api-version=$API_VERSION"

        logger.info("Getting file content from: $url")

        val response = executeGet(url, config.personalAccessToken)

        // Parse the JSON response to extract the content
        return try {
            val jsonObject = gson.fromJson(response, com.google.gson.JsonObject::class.java)
            val content = jsonObject.get("content")?.asString ?: ""
            logger.info("Extracted content: ${content.length} characters")
            content
        } catch (e: Exception) {
            logger.error("Failed to parse file content response", e)
            logger.error("Response was: $response")
            ""
        }
    }

    /**
     * Searches identities (users/groups) to add as reviewers to the PR
     * Strategy: gets users from recent PRs in the repository
     * This approach does not require special permissions, only those already used for PRs
     * @param searchText Search text (name, email, etc.)
     * @return List of found identities
     */
    @Throws(AzureDevOpsApiException::class)
    fun searchIdentities(searchText: String): List<Identity> {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            logger.error("Config not valid for searchIdentities")
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        logger.info("=== SEARCHING IDENTITIES ===")
        logger.info("Search text: '$searchText'")
        logger.info("Organization: ${config.organization}")
        logger.info("Project: ${config.project}")
        
        return try {
            // Strategy: get users from recent PRs (createdBy + reviewers)
            // This uses the same permissions already working for getPullRequests
            logger.info("Getting identities from recent pull requests...")
            
            val pullRequests = getPullRequests(status = "all", top = 100)
            logger.info("Retrieved ${pullRequests.size} pull requests")
            
            val identitiesMap = mutableMapOf<String, Identity>()
            
            // Collect all unique users from createdBy and reviewers
            pullRequests.forEach { pr ->
                // Add the PR creator
                pr.createdBy?.let { creator ->
                    creator.id?.let { id ->
                        if (!identitiesMap.containsKey(id)) {
                            identitiesMap[id] = Identity(
                                id = creator.id,
                                displayName = creator.displayName,
                                uniqueName = creator.uniqueName,
                                imageUrl = creator.imageUrl,
                                descriptor = null
                            )
                        }
                    }
                }
                
                // Add reviewers
                pr.reviewers?.forEach { reviewer ->
                    reviewer.id?.let { id ->
                        if (!identitiesMap.containsKey(id)) {
                            identitiesMap[id] = Identity(
                                id = reviewer.id,
                                displayName = reviewer.displayName,
                                uniqueName = reviewer.uniqueName,
                                imageUrl = reviewer.imageUrl,
                                descriptor = null
                            )
                        }
                    }
                }
            }
            
            logger.info("Collected ${identitiesMap.size} unique users from pull requests")
            
            // Filter by search text
            val filtered = identitiesMap.values.filter { identity ->
                val displayName = identity.displayName ?: ""
                val uniqueName = identity.uniqueName ?: ""
                (displayName.contains(searchText, ignoreCase = true) || 
                 uniqueName.contains(searchText, ignoreCase = true)) &&
                displayName.isNotBlank()
            }.sortedBy { it.displayName }
            
            logger.info("Found ${filtered.size} matching users after filtering")
            filtered.forEachIndexed { index, identity ->
                logger.info("Match $index: displayName=${identity.displayName}, uniqueName=${identity.uniqueName}")
            }
            // Return only the first 10 matches
            filtered.take(10)
            
        } catch (e: Exception) {
            logger.error("Failed to search identities from pull requests", e)
            logger.error("Exception type: ${e.javaClass.name}")
            logger.error("Exception message: ${e.message}")
            emptyList()
        }
    }

    /**
     * Completes (merges) a Pull Request
     * API: PATCH https://dev.azure.com/{organization}/{project}/_apis/git/repositories/{repositoryId}/pullrequests/{pullRequestId}?api-version=7.0
     *
     * @param pullRequestId The ID of the Pull Request to complete
     * @param completionOptions Options for completing the PR (merge strategy, delete source branch, etc.)
     * @param comment Optional comment to add when completing
     * @return Updated Pull Request
     */
    @Throws(AzureDevOpsApiException::class)
    fun completePullRequest(
        pullRequestId: Int,
        completionOptions: paol0b.azuredevops.model.CompletionOptions,
        comment: String? = null
    ): PullRequest {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val url = buildApiUrl(config.project, config.repository, "/pullrequests/$pullRequestId?api-version=$API_VERSION")

        logger.info("Completing Pull Request #$pullRequestId with strategy: ${completionOptions.mergeStrategy}")

        return try {
            // Get the PR to obtain the lastMergeSourceCommit
            val pr = getPullRequest(pullRequestId)

            if (pr.lastMergeSourceCommit == null) {
                throw AzureDevOpsApiException("Cannot complete PR: missing source commit information")
            }

            val requestBody = paol0b.azuredevops.model.CompletePullRequestRequest(
                status = "completed",
                lastMergeSourceCommit = pr.lastMergeSourceCommit,
                completionOptions = completionOptions
            )

            val jsonBody = gson.toJson(requestBody)
            logger.info("Request body: $jsonBody")

            val response = executePatch(url, jsonBody, config.personalAccessToken)
            val completedPr = gson.fromJson(response, PullRequest::class.java)

            // Add comment if provided
            if (!comment.isNullOrBlank()) {
                try {
                    addPullRequestComment(pullRequestId, comment)
                } catch (e: Exception) {
                    logger.warn("Failed to add comment after completing PR", e)
                }
            }

            logger.info("Successfully completed Pull Request #$pullRequestId")
            completedPr
        } catch (e: Exception) {
            logger.error("Failed to complete pull request #$pullRequestId", e)
            throw AzureDevOpsApiException("Error completing Pull Request: ${e.message}", e)
        }
    }

    /**
     * Sets auto-complete on a Pull Request
     * API: PATCH https://dev.azure.com/{organization}/{project}/_apis/git/repositories/{repositoryId}/pullrequests/{pullRequestId}?api-version=7.0
     *
     * @param pullRequestId The ID of the Pull Request
     * @param completionOptions Options for when the PR is auto-completed
     * @param comment Optional comment to add when setting auto-complete
     * @return Updated Pull Request
     */
    @Throws(AzureDevOpsApiException::class)
    fun setAutoComplete(
        pullRequestId: Int,
        completionOptions: paol0b.azuredevops.model.CompletionOptions,
        comment: String? = null
    ): PullRequest {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val url = buildApiUrl(config.project, config.repository, "/pullrequests/$pullRequestId?api-version=$API_VERSION")

        logger.info("Setting auto-complete on Pull Request #$pullRequestId")

        return try {
            // Get current user identity
            val currentUser = getCurrentUser()
            val userId = currentUser.id ?: throw AzureDevOpsApiException("Unable to determine current user ID")

            val requestBody = paol0b.azuredevops.model.SetAutoCompleteRequest(
                autoCompleteSetBy = paol0b.azuredevops.model.AutoCompleteSetBy(id = userId),
                completionOptions = completionOptions
            )

            val jsonBody = gson.toJson(requestBody)
            logger.info("Request body: $jsonBody")

            val response = executePatch(url, jsonBody, config.personalAccessToken)
            val updatedPr = gson.fromJson(response, PullRequest::class.java)

            // Add comment if provided
            if (!comment.isNullOrBlank()) {
                try {
                    addPullRequestComment(pullRequestId, comment)
                } catch (e: Exception) {
                    logger.warn("Failed to add comment after setting auto-complete", e)
                }
            }

            logger.info("Successfully set auto-complete on Pull Request #$pullRequestId")
            updatedPr
        } catch (e: Exception) {
            logger.error("Failed to set auto-complete on pull request #$pullRequestId", e)
            throw AzureDevOpsApiException("Error setting auto-complete: ${e.message}", e)
        }
    }

    /**
     * Gets the current authenticated user
     * Uses the connectionData endpoint to get the identity ID that Azure DevOps Git API expects
     * API: GET https://dev.azure.com/{organization}/_apis/connectionData
     */
    @Throws(AzureDevOpsApiException::class)
    private fun getCurrentUser(): User {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        // Use connectionData endpoint to get the authenticated user's identity ID
        // This returns the correct ID that the Git API expects for reviewers
        val url = buildOrgApiUrl("/connectionData")

        return try {
            val response = executeGet(url, config.personalAccessToken)
            val connectionData = gson.fromJson(response, com.google.gson.JsonObject::class.java)

            val authenticatedUser = connectionData.getAsJsonObject("authenticatedUser")
                ?: throw AzureDevOpsApiException("No authenticatedUser in connectionData")

            val id = authenticatedUser.get("id")?.asString
                ?: throw AzureDevOpsApiException("No user ID in connectionData")
            val displayName = authenticatedUser.get("providerDisplayName")?.asString ?: "Unknown"

            // Get uniqueName from properties if available
            val properties = authenticatedUser.getAsJsonObject("properties")
            val uniqueName = properties?.getAsJsonObject("Account")?.get("\$value")?.asString

            // Cache the user ID
            cachedUserId = id

            logger.info("Current user identity: id=$id, displayName=$displayName, uniqueName=$uniqueName")

            User(
                id = id,
                displayName = displayName,
                uniqueName = uniqueName,
                imageUrl = null
            )
        } catch (e: Exception) {
            logger.error("Failed to get current user from connectionData", e)
            throw AzureDevOpsApiException("Error retrieving user identity: ${e.message}", e)
        }
    }

    /**
     * Gets the current user ID with caching to avoid repeated API calls
     * Returns null if user cannot be retrieved
     */
    fun getCurrentUserIdCached(): String? {
        // Return cached value if available
        cachedUserId?.let { return it }

        return try {
            getCurrentUser().id
        } catch (e: Exception) {
            logger.warn("Could not get current user ID", e)
            null
        }
    }

    /**
     * Adds a comment to a Pull Request
     * API: POST https://dev.azure.com/{organization}/{project}/_apis/git/repositories/{repositoryId}/pullRequests/{pullRequestId}/threads?api-version=7.0
     */
    @Throws(AzureDevOpsApiException::class)
    private fun addPullRequestComment(pullRequestId: Int, comment: String) {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val url = buildApiUrl(config.project, config.repository, "/pullRequests/$pullRequestId/threads?api-version=$API_VERSION")

        val commentData = mapOf(
            "comments" to listOf(
                mapOf("content" to comment, "commentType" to 1)
            ),
            "status" to 1
        )

        logger.info("Adding general comment to PR #$pullRequestId")

        try {
            executePost(url, commentData, config.personalAccessToken)
            logger.info("Comment added successfully")
        } catch (e: Exception) {
            logger.error("Failed to add PR comment", e)
            throw AzureDevOpsApiException("Error while adding comment: ${e.message}", e)
        }
    }

    /**
     * Creates a threaded comment on a specific file and line range (FILE-SCOPED)
     * Azure DevOps requires BOTH threadContext and pullRequestThreadContext.
     *
     * @param pullRequestId The PR ID
     * @param filePath The path of the file (e.g., /src/styles.css)
     * @param content The comment text
     * @param startLine The starting line number (1-based)
     * @param endLine The ending line number (1-based), defaults to startLine
     * @param isLeft True if commenting on the original file (base), False for the modified file
     * @param projectName Optional project name for cross-repository PRs
     * @param repositoryId Optional repository ID for cross-repository PRs
     * @param changeTrackingId Optional changeTrackingId from iteration changes
     */
    @Throws(AzureDevOpsApiException::class)
    fun createThread(
        pullRequestId: Int,
        filePath: String,
        content: String,
        startLine: Int,
        endLine: Int = startLine,
        isLeft: Boolean,
        projectName: String? = null,
        repositoryId: String? = null,
        changeTrackingId: Int? = null
    ) {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        if (filePath.isBlank()) {
            throw AzureDevOpsApiException("File path is required to create a file-scoped comment")
        }

        val normalizedPath = if (filePath.startsWith("/")) filePath else "/$filePath"

        val effectiveProject = projectName ?: config.project
        val effectiveRepo = repositoryId ?: config.repository

        val url = buildApiUrl(effectiveProject, effectiveRepo, "/pullRequests/$pullRequestId/threads?api-version=$API_VERSION")

        val validStartLine = startLine.coerceAtLeast(1)
        val validEndLine = endLine.coerceAtLeast(validStartLine)

        // CommentPosition: line (1-based), offset (1-based character position)
        // Azure DevOps rejects offset=0
        val startPosition = mapOf("line" to validStartLine, "offset" to 1)
        val endPosition = mapOf("line" to validEndLine, "offset" to 1)

        // threadContext: file path + line location (required for file-scoped comments)
        val threadContextMap = mutableMapOf<String, Any>("filePath" to normalizedPath)
        if (isLeft) {
            threadContextMap["leftFileStart"] = startPosition
            threadContextMap["leftFileEnd"] = endPosition
        } else {
            threadContextMap["rightFileStart"] = startPosition
            threadContextMap["rightFileEnd"] = endPosition
        }

        val latestIterationId = try {
            getLatestIterationId(pullRequestId, effectiveProject, effectiveRepo)
        } catch (e: Exception) {
            logger.warn("Failed to resolve latest iteration id: ${e.message}. Falling back to 1")
            1
        }

        // pullRequestThreadContext: iteration tracking (required for file-scoped comments)
        val pullRequestContextMap = mutableMapOf<String, Any>(
            "iterationContext" to mapOf(
                "firstComparingIteration" to 1,
                "secondComparingIteration" to latestIterationId
            )
        )
        changeTrackingId?.let { pullRequestContextMap["changeTrackingId"] = it }

        val commentData = mapOf(
            "comments" to listOf(
                mapOf(
                    "parentCommentId" to 0,
                    "content" to content,
                    "commentType" to 1
                )
            ),
            "status" to 1,
            "threadContext" to threadContextMap,
            "pullRequestThreadContext" to pullRequestContextMap
        )

        try {
            executePost(url, commentData, config.personalAccessToken)
            logger.info("File-scoped comment thread created successfully")
        } catch (e: Exception) {
            logger.error("Failed to create file-scoped comment thread", e)
            throw AzureDevOpsApiException("Error while creating file-scoped comment thread: ${e.message}", e)
        }
    }

    /**
     * Resolve the latest iteration id for a PR
     */
    @Throws(AzureDevOpsApiException::class)
    private fun getLatestIterationId(pullRequestId: Int, projectName: String, repositoryId: String): Int {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val url = buildApiUrl(projectName, repositoryId, "/pullRequests/$pullRequestId/iterations?api-version=$API_VERSION")
        val response = executeGet(url, config.personalAccessToken)
        val listResponse = gson.fromJson(response, PullRequestIterationListResponse::class.java)
        val iterations = listResponse.value ?: emptyList()
        return iterations.maxOfOrNull { it.id ?: 0 }?.coerceAtLeast(1) ?: 1
    }

    /**
     * Alias for getCommentThreads to match the naming convention used in the review tool
     */
    @Throws(AzureDevOpsApiException::class)
    fun getPullRequestThreads(pullRequestId: Int): List<CommentThread> {
        return getCommentThreads(pullRequestId)
    }

    /**
     * Retrieves policy evaluations for a Pull Request
     * API: GET https://dev.azure.com/{org}/{project}/_apis/policy/evaluations?artifactId=vstfs:///CodeReview/CodeReviewId/{projectId}/{pullRequestId}&api-version=7.0
     *
     * @param pullRequestId PR ID
     * @param projectName Optional project name for cross-repo PRs
     * @param repositoryId Optional repository ID for cross-repo PRs
     * @return List of policy evaluations
     */
    @Throws(AzureDevOpsApiException::class)
    fun getPolicyEvaluations(pullRequestId: Int, projectName: String? = null, repositoryId: String? = null): List<PolicyEvaluation> {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val effectiveProject = projectName ?: config.project

        // We need the project ID. If accessible from the PR, use it. Otherwise, we use the project name in the URL.
        // The artifactId format: vstfs:///CodeReview/CodeReviewId/{projectId}/{pullRequestId}
        // Since we may not have projectId directly, we use the project-scoped policy evaluations endpoint instead
        val encodedProject = encodePathSegment(effectiveProject)
        val baseUrl = configService.getApiBaseUrl()
        val artifactId = "vstfs:///CodeReview/CodeReviewId/$encodedProject/$pullRequestId"
        val encodedArtifactId = URLEncoder.encode(artifactId, StandardCharsets.UTF_8.toString())
        val url = "$baseUrl/$encodedProject/_apis/policy/evaluations?artifactId=$encodedArtifactId&api-version=$API_VERSION"

        logger.info("Fetching policy evaluations for PR #$pullRequestId")

        return try {
            val response = executeGet(url, config.personalAccessToken)
            val listResponse = gson.fromJson(response, PolicyEvaluationListResponse::class.java)
            listResponse.value?.filter { it.configuration?.isEnabled == true } ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to fetch policy evaluations for PR #$pullRequestId: ${e.message}")
            emptyList()
        }
    }

    /**
     * Retrieves commits in a Pull Request
     * API: GET https://dev.azure.com/{org}/{project}/_apis/git/repositories/{repo}/pullRequests/{pullRequestId}/commits?api-version=7.0
     *
     * @param pullRequestId PR ID
     * @param projectName Optional project name for cross-repo PRs
     * @param repositoryId Optional repository ID for cross-repo PRs
     * @return List of commits
     */
    @Throws(AzureDevOpsApiException::class)
    fun getPullRequestCommits(pullRequestId: Int, projectName: String? = null, repositoryId: String? = null): List<GitCommitRef> {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val effectiveProject = projectName ?: config.project
        val effectiveRepo = repositoryId ?: config.repository

        val url = buildApiUrl(effectiveProject, effectiveRepo,
            "/pullRequests/$pullRequestId/commits?api-version=$API_VERSION")

        logger.info("Fetching commits for PR #$pullRequestId")

        return try {
            val response = executeGet(url, config.personalAccessToken)
            val listResponse = gson.fromJson(response, GitCommitListResponse::class.java)
            listResponse.value ?: emptyList()
        } catch (e: Exception) {
            logger.warn("Failed to fetch commits for PR #$pullRequestId: ${e.message}")
            emptyList()
        }
    }

    /**
     * Vote on a Pull Request
     * Vote values: 10 = Approved, 5 = Approved with suggestions, 0 = No vote, -5 = Waiting for author, -10 = Rejected
     * API: PUT https://dev.azure.com/{organization}/{project}/_apis/git/repositories/{repositoryId}/pullRequests/{pullRequestId}/reviewers/{reviewerId}?api-version=7.0
     *
     * Automatically adds the current user as a reviewer if not already present.
     */
    @Throws(AzureDevOpsApiException::class)
    fun voteOnPullRequest(pullRequest: PullRequest, vote: Int) {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        // Use repository info from PR if available (for cross-repo PRs)
        val effectiveProject = pullRequest.repository?.project?.name ?: config.project
        val effectiveRepo = pullRequest.repository?.id ?: config.repository

        // Get current user's unique name from profile
        val currentUser = getCurrentUser()
        val currentUserUniqueName = currentUser.uniqueName?.lowercase()
        val currentUserId = currentUser.id

        // Find the current user in the PR's reviewers list
        var reviewer = pullRequest.reviewers?.find { reviewer ->
            reviewer.uniqueName?.lowercase() == currentUserUniqueName ||
            reviewer.displayName?.lowercase() == currentUser.displayName?.lowercase() ||
            reviewer.id == currentUserId
        }

        // Se l'utente non è un reviewer, aggiungilo automaticamente
        if (reviewer == null) {
            logger.info("Current user is not a reviewer, adding automatically...")

            if (currentUserId == null) {
                throw AzureDevOpsApiException("Unable to get current user ID")
            }

            // Step 1: Aggiungi l'utente come reviewer senza voto (Azure DevOps non permette di votare quando ci si aggiunge)
            val addReviewerUrl = buildApiUrl(effectiveProject, effectiveRepo,
                "/pullRequests/${pullRequest.pullRequestId}/reviewers/$currentUserId?api-version=$API_VERSION")

            val addReviewerRequest = mapOf(
                "id" to currentUserId,  // ID dell'utente da aggiungere
                "vote" to 0,  // No vote quando ci si aggiunge
                "isRequired" to false
            )

            try {
                val response = executePut(addReviewerUrl, addReviewerRequest, config.personalAccessToken)
                logger.info("User added as reviewer successfully")

                // Parse the response to get the actual reviewer ID that was created
                val reviewerData = gson.fromJson(response, com.google.gson.JsonObject::class.java)
                val addedReviewerId = reviewerData.get("id")?.asString ?: currentUserId

                // Step 2: Ora imposta il voto in una seconda chiamata
                val voteUrl = buildApiUrl(effectiveProject, effectiveRepo,
                    "/pullRequests/${pullRequest.pullRequestId}/reviewers/$addedReviewerId?api-version=$API_VERSION")

                val voteRequest = mapOf("vote" to vote)

                executePut(voteUrl, voteRequest, config.personalAccessToken)
                logger.info("Vote submitted successfully after adding as reviewer")
                return
            } catch (e: Exception) {
                logger.error("Failed to add user as reviewer or vote", e)
                throw AzureDevOpsApiException("Error while adding you as a reviewer and voting: ${e.message}", e)
            }
        }

        // L'utente è già un reviewer, procedi con il voto
        val reviewerId = reviewer.id ?: throw AzureDevOpsApiException(
            "Unable to get reviewer ID for current user"
        )

        val url = buildApiUrl(effectiveProject, effectiveRepo,
            "/pullRequests/${pullRequest.pullRequestId}/reviewers/$reviewerId?api-version=$API_VERSION")

        val voteRequest = mapOf("vote" to vote)

        logger.info("Voting on PR #${pullRequest.pullRequestId} with vote: $vote (reviewer: $reviewerId)")

        try {
            val response = executePut(url, voteRequest, config.personalAccessToken)
            logger.info("Vote submitted successfully: $response")
        } catch (e: Exception) {
            logger.error("Failed to vote on pull request", e)
            throw AzureDevOpsApiException("Error while voting on Pull Request: ${e.message}", e)
        }
    }


    // region Pipelines / Builds

    /**
     * Helper to build Build API URLs (build APIs are project-scoped, not repo-scoped).
     * Format: {baseUrl}/{project}/_apis/build{endpoint}
     */
    private fun buildBuildApiUrl(projectName: String, endpoint: String): String {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val encodedProject = encodePathSegment(projectName)
        return "${configService.getApiBaseUrl()}/$encodedProject/_apis/build$endpoint"
    }

    /**
     * Retrieves a list of builds (pipeline runs) for the current project.
     * API: GET https://dev.azure.com/{org}/{project}/_apis/build/builds
     *
     * @param definitionId Optional: filter by pipeline definition ID
     * @param requestedFor Optional: filter by user display name or unique name
     * @param branchName Optional: filter by branch (e.g., "refs/heads/main")
     * @param statusFilter Optional: filter by build status
     * @param resultFilter Optional: filter by build result
     * @param top Maximum number of builds to return
     * @return List of pipeline builds
     */
    @Throws(AzureDevOpsApiException::class)
    fun getBuilds(
        definitionId: Int? = null,
        requestedFor: String? = null,
        branchName: String? = null,
        statusFilter: String? = null,
        resultFilter: String? = null,
        top: Int = 50
    ): List<PipelineBuild> {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val params = mutableListOf("\$top=$top", "api-version=$API_VERSION")
        definitionId?.let { params.add("definitions=$it") }
        requestedFor?.let { params.add("requestedFor=${encodePathSegment(it)}") }
        branchName?.let {
            val ref = if (it.startsWith("refs/")) it else "refs/heads/$it"
            params.add("branchName=$ref")
        }
        statusFilter?.let { if (it != "all") params.add("statusFilter=$it") }
        resultFilter?.let { if (it != "all") params.add("resultFilter=$it") }

        val url = buildBuildApiUrl(config.project, "/builds?${params.joinToString("&")}")

        logger.info("Fetching builds: $url")

        return try {
            val response = executeGet(url, config.personalAccessToken)
            val listResponse = gson.fromJson(response, BuildListResponse::class.java)
            listResponse.value
        } catch (e: Exception) {
            logger.error("Failed to fetch builds", e)
            throw AzureDevOpsApiException("Error while retrieving builds: ${e.message}", e)
        }
    }

    /**
     * Retrieves a single build by ID.
     * API: GET https://dev.azure.com/{org}/{project}/_apis/build/builds/{buildId}
     */
    @Throws(AzureDevOpsApiException::class)
    fun getBuild(buildId: Int): PipelineBuild {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val url = buildBuildApiUrl(config.project, "/builds/$buildId?api-version=$API_VERSION")

        logger.info("Fetching build #$buildId")

        return try {
            val response = executeGet(url, config.personalAccessToken)
            gson.fromJson(response, PipelineBuild::class.java)
        } catch (e: Exception) {
            logger.error("Failed to fetch build #$buildId", e)
            throw AzureDevOpsApiException("Error while retrieving build: ${e.message}", e)
        }
    }

    /**
     * Retrieves the timeline (stages, jobs, tasks) for a build.
     * API: GET https://dev.azure.com/{org}/{project}/_apis/build/builds/{buildId}/timeline
     */
    @Throws(AzureDevOpsApiException::class)
    fun getBuildTimeline(buildId: Int): BuildTimeline {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val url = buildBuildApiUrl(config.project, "/builds/$buildId/timeline?api-version=$API_VERSION")

        logger.info("Fetching timeline for build #$buildId")

        return try {
            val response = executeGet(url, config.personalAccessToken)
            gson.fromJson(response, BuildTimeline::class.java)
        } catch (e: Exception) {
            logger.error("Failed to fetch timeline for build #$buildId", e)
            throw AzureDevOpsApiException("Error while retrieving build timeline: ${e.message}", e)
        }
    }

    /**
     * Retrieves the full log content for a specific log of a build.
     * API: GET https://dev.azure.com/{org}/{project}/_apis/build/builds/{buildId}/logs/{logId}
     * Returns plain text log lines.
     */
    @Throws(AzureDevOpsApiException::class)
    fun getBuildLogText(buildId: Int, logId: Int): String {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val url = buildBuildApiUrl(config.project, "/builds/$buildId/logs/$logId?api-version=$API_VERSION")

        logger.info("Fetching log #$logId for build #$buildId")

        return try {
            // The log endpoint returns plain text, not JSON
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", createAuthHeader(config.personalAccessToken))
                .addHeader("Accept", "text/plain")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    responseBody
                } else {
                    throw handleErrorResponse(response.code, responseBody)
                }
            }
        } catch (e: AzureDevOpsApiException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to fetch log #$logId for build #$buildId", e)
            throw AzureDevOpsApiException("Error while retrieving build log: ${e.message}", e)
        }
    }

    /**
     * Retrieves log content starting from a specific line number (0-based).
     * Uses the \$startLine query parameter to fetch only new lines.
     * Returns plain text of the new lines only.
     */
    @Throws(AzureDevOpsApiException::class)
    fun getBuildLogTextFromLine(buildId: Int, logId: Int, startLine: Int): String {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val url = buildBuildApiUrl(config.project,
            "/builds/$buildId/logs/$logId?startLine=$startLine&api-version=$API_VERSION")

        return try {
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("Authorization", createAuthHeader(config.personalAccessToken))
                .addHeader("Accept", "text/plain")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                if (response.isSuccessful) {
                    responseBody
                } else {
                    throw handleErrorResponse(response.code, responseBody)
                }
            }
        } catch (e: AzureDevOpsApiException) {
            throw e
        } catch (e: Exception) {
            logger.error("Failed to fetch log delta #$logId for build #$buildId from line $startLine", e)
            throw AzureDevOpsApiException("Error while retrieving build log delta: ${e.message}", e)
        }
    }

    /**
     * Retrieves all build definitions (pipelines) for the current project.
     * API: GET https://dev.azure.com/{org}/{project}/_apis/build/definitions
     */
    @Throws(AzureDevOpsApiException::class)
    fun getBuildDefinitions(): List<BuildDefinition> {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val url = buildBuildApiUrl(config.project, "/definitions?api-version=$API_VERSION")

        logger.info("Fetching build definitions")

        return try {
            val response = executeGet(url, config.personalAccessToken)
            val listResponse = gson.fromJson(response, BuildDefinitionListResponse::class.java)
            listResponse.value
        } catch (e: Exception) {
            logger.error("Failed to fetch build definitions", e)
            throw AzureDevOpsApiException("Error while retrieving build definitions: ${e.message}", e)
        }
    }

    /**
     * Queues (runs) a new build.
     * API: POST https://dev.azure.com/{org}/{project}/_apis/build/builds
     *
     * @param definitionId The ID of the pipeline definition to run
     * @param sourceBranch Optional: branch to build (e.g., "refs/heads/main")
     * @param parameters Optional: JSON string of parameters
     * @return The queued build
     */
    @Throws(AzureDevOpsApiException::class)
    fun queueBuild(
        definitionId: Int,
        sourceBranch: String? = null,
        parameters: String? = null
    ): PipelineBuild {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException(AUTH_ERROR_MESSAGE)
        }

        val request = QueueBuildRequest(
            definition = QueueBuildDefinitionRef(id = definitionId),
            sourceBranch = sourceBranch,
            parameters = parameters
        )

        val url = buildBuildApiUrl(config.project, "/builds?api-version=$API_VERSION")

        logger.info("Queueing build for definition #$definitionId on branch: $sourceBranch")

        return try {
            val response = executePost(url, request, config.personalAccessToken)
            gson.fromJson(response, PipelineBuild::class.java)
        } catch (e: Exception) {
            logger.error("Failed to queue build", e)
            throw AzureDevOpsApiException("Error while queueing build: ${e.message}", e)
        }
    }

    // endregion

}

/**
 * Custom exception for Azure DevOps API errors
 */
class AzureDevOpsApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
