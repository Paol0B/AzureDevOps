package paol0b.azuredevops.services

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
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
    
    /**
     * Helper function to build API URLs with proper URL encoding for project and repository names
     * This handles special characters like accented letters (à, è, ì, etc.) and spaces
     */
    private fun buildApiUrl(project: String, repository: String, endpoint: String): String {
        val configService = AzureDevOpsConfigService.getInstance(this.project)
        val encodedProject = URLEncoder.encode(project, StandardCharsets.UTF_8.toString())
        val encodedRepository = URLEncoder.encode(repository, StandardCharsets.UTF_8.toString())
        return "${configService.getApiBaseUrl()}/$encodedProject/_apis/git/repositories/$encodedRepository$endpoint"
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
     * Verifies connection and credentials with Azure DevOps
     */
    @Throws(AzureDevOpsApiException::class)
    fun testConnection(): Boolean {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException("Invalid configuration")
        }

        val url = buildApiUrl(config.project, config.repository, "?api-version=$API_VERSION")
        
        return try {
            executeGet(url, config.personalAccessToken)
            true
        } catch (e: Exception) {
            logger.error("Connection test failed", e)
            throw AzureDevOpsApiException("Connection test failed: ${e.message}", e)
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
            prList.value?.firstOrNull()
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

        // Create the request with the correct format for the API
        val request = UpdateThreadStatusRequest(status)
        
        val url = buildApiUrl(config.project, config.repository, "/pullRequests/$pullRequestId/threads/$threadId?api-version=$API_VERSION")
        
        logger.info("Updating thread #$threadId status to ${status.getDisplayName()} (API value: ${status.toApiValue()}) in PR #$pullRequestId")
        logger.info("Request body: ${gson.toJson(request)}")
        
        try {
            val response = executePatch(url, request, config.personalAccessToken)
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
     * Executes a GET request
     */
    @Throws(IOException::class, AzureDevOpsApiException::class)
    private fun executeGet(urlString: String, token: String): String {
        val url = java.net.URI(urlString).toURL()
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", createAuthHeader(token))
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            val responseCode = connection.responseCode
            
            if (responseCode == HttpURLConnection.HTTP_OK) {
                return connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw handleErrorResponse(responseCode, errorBody)
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Executes a POST request
     */
    @Throws(IOException::class, AzureDevOpsApiException::class)
    private fun executePost(urlString: String, body: Any, token: String): String {
        val url = java.net.URI(urlString).toURL()
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            connection.requestMethod = "POST"
            connection.setRequestProperty("Authorization", createAuthHeader(token))
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            // Write the body
            val jsonBody = gson.toJson(body)
            connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write(jsonBody)
            }

            val responseCode = connection.responseCode
            
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                return connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw handleErrorResponse(responseCode, errorBody)
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Executes a PATCH request
     * Uses reflection to enable PATCH in HttpURLConnection
     */
    @Throws(IOException::class, AzureDevOpsApiException::class)
    private fun executePatch(urlString: String, body: Any, token: String): String {
        val url = java.net.URI(urlString).toURL()
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            // Workaround for PATCH: use reflection to set the method
            // HttpURLConnection does not support PATCH by default
            try {
                val methodField = HttpURLConnection::class.java.getDeclaredField("method")
                methodField.isAccessible = true
                methodField.set(connection, "PATCH")
            } catch (e: Exception) {
                logger.warn("Failed to set PATCH method via reflection, trying setRequestMethod", e)
                // Fallback: still try (works on some JVMs)
                connection.requestMethod = "PATCH"
            }
            
            connection.setRequestProperty("Authorization", createAuthHeader(token))
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            // Write the body
            val jsonBody = gson.toJson(body)
            logger.info("PATCH request body: $jsonBody")
            
            connection.outputStream.bufferedWriter(StandardCharsets.UTF_8).use { writer ->
                writer.write(jsonBody)
            }

            val responseCode = connection.responseCode
            
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_CREATED) {
                return connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw handleErrorResponse(responseCode, errorBody)
            }
        } finally {
            connection.disconnect()
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
        val pr = getPullRequest(pullRequestId)
        
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
}

/**
 * Custom exception for Azure DevOps API errors
 */
class AzureDevOpsApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
