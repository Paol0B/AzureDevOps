package paol0b.azuredevops.services

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import paol0b.azuredevops.model.*
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Client per comunicare con Azure DevOps REST API
 * Documentazione API: https://learn.microsoft.com/en-us/rest/api/azure/devops/
 */
@Service(Service.Level.PROJECT)
class AzureDevOpsApiClient(private val project: Project) {

    private val gson = Gson()
    private val logger = Logger.getInstance(AzureDevOpsApiClient::class.java)

    companion object {
        private const val API_VERSION = "7.0"
        
        fun getInstance(project: Project): AzureDevOpsApiClient {
            return project.getService(AzureDevOpsApiClient::class.java)
        }
    }
    
    /**
     * Helper function to build API URLs with proper URL encoding for project and repository names
     * This handles special characters like accented letters (à, è, ì, etc.)
     */
    private fun buildApiUrl(project: String, repository: String, endpoint: String): String {
        val configService = AzureDevOpsConfigService.getInstance(this.project)
        val encodedProject = URLEncoder.encode(project, StandardCharsets.UTF_8.toString())
        val encodedRepository = URLEncoder.encode(repository, StandardCharsets.UTF_8.toString())
        return "${configService.getApiBaseUrl()}/$encodedProject/_apis/git/repositories/$encodedRepository$endpoint"
    }

    /**
     * Crea una Pull Request su Azure DevOps
     * 
     * @param sourceBranch Branch di origine (es: "refs/heads/feature/xyz")
     * @param targetBranch Branch di destinazione (es: "refs/heads/main")
     * @param title Titolo della PR
     * @param description Descrizione della PR (opzionale)
     * @return PullRequestResponse se successo, altrimenti lancia un'eccezione
     */
    @Throws(AzureDevOpsApiException::class)
    fun createPullRequest(
        sourceBranch: String,
        targetBranch: String,
        title: String,
        description: String = ""
    ): PullRequestResponse {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException("Azure DevOps non configurato. Configura Organization, Project, Repository e PAT nelle impostazioni.")
        }

        val request = CreatePullRequestRequest(
            sourceRefName = sourceBranch,
            targetRefName = targetBranch,
            title = title,
            description = description
        )

        // URL: https://dev.azure.com/{organization}/{project}/_apis/git/repositories/{repositoryId}/pullrequests?api-version=7.0
        val url = buildApiUrl(config.project, config.repository, "/pullrequests?api-version=$API_VERSION")
        
        logger.info("Creating Pull Request: $sourceBranch -> $targetBranch")
        
        return try {
            val response = executePost(url, request, config.personalAccessToken)
            gson.fromJson(response, PullRequestResponse::class.java)
        } catch (e: Exception) {
            logger.error("Failed to create pull request", e)
            throw AzureDevOpsApiException("Errore durante la creazione della Pull Request: ${e.message}", e)
        }
    }

    /**
     * Recupera la lista delle Pull Request del repository
     * 
     * @param status Filtra per stato (es: "active", "completed", "abandoned", "all")
     * @param top Numero massimo di PR da recuperare
     * @return Lista di Pull Request
     */
    @Throws(AzureDevOpsApiException::class)
    fun getPullRequests(
        status: String = "active",
        top: Int = 100
    ): List<PullRequest> {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException("Azure DevOps non configurato.")
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
            throw AzureDevOpsApiException("Errore durante il recupero delle Pull Request: ${e.message}", e)
        }
    }

    /**
     * Recupera una singola Pull Request con tutti i dettagli
     * 
     * @param pullRequestId ID della PR
     * @return Pull Request completa
     */
    @Throws(AzureDevOpsApiException::class)
    fun getPullRequest(pullRequestId: Int): PullRequest {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException("Azure DevOps non configurato.")
        }

        val url = buildApiUrl(config.project, config.repository, "/pullrequests/$pullRequestId?api-version=$API_VERSION")
        
        logger.info("Fetching Pull Request #$pullRequestId")
        
        return try {
            val response = executeGet(url, config.personalAccessToken)
            gson.fromJson(response, PullRequest::class.java)
        } catch (e: Exception) {
            logger.error("Failed to fetch pull request #$pullRequestId", e)
            throw AzureDevOpsApiException("Errore durante il recupero della Pull Request: ${e.message}", e)
        }
    }

    /**
     * Verifica la connessione e le credenziali con Azure DevOps
     */
    @Throws(AzureDevOpsApiException::class)
    fun testConnection(): Boolean {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException("Configurazione non valida")
        }

        val url = buildApiUrl(config.project, config.repository, "?api-version=$API_VERSION")
        
        return try {
            executeGet(url, config.personalAccessToken)
            true
        } catch (e: Exception) {
            logger.error("Connection test failed", e)
            throw AzureDevOpsApiException("Test connessione fallito: ${e.message}", e)
        }
    }
    
    /**
     * Cerca una Pull Request attiva tra due branch specifici
     * 
     * @param sourceBranch Branch sorgente (es: "refs/heads/feature")
     * @param targetBranch Branch target (es: "refs/heads/main")
     * @return La PR esistente o null se non trovata
     */
    @Throws(AzureDevOpsApiException::class)
    fun findActivePullRequest(sourceBranch: String, targetBranch: String): PullRequest? {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException("Azure DevOps non configurato.")
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
     * Recupera tutti i thread di commenti per una Pull Request
     * 
     * @param pullRequestId ID della PR
     * @return Lista di thread di commenti
     */
    @Throws(AzureDevOpsApiException::class)
    fun getCommentThreads(pullRequestId: Int): List<CommentThread> {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException("Azure DevOps non configurato.")
        }

        val url = buildApiUrl(config.project, config.repository, "/pullRequests/$pullRequestId/threads?api-version=$API_VERSION")
        
        logger.info("Fetching comment threads for PR #$pullRequestId")
        logger.info("URL: $url")
        
        return try {
            val response = executeGet(url, config.personalAccessToken)
            
            // Log della risposta raw per debug
            logger.info("=== RAW RESPONSE (first 2000 chars) ===")
            logger.info(response.take(2000))
            logger.info("=== END RAW RESPONSE ===")
            
            val listResponse = gson.fromJson(response, CommentThreadListResponse::class.java)
            val threads = listResponse.value.filter { it.isDeleted != true }
            
            // Log dettagliato di ogni thread
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
            throw AzureDevOpsApiException("Errore durante il recupero dei commenti: ${e.message}", e)
        }
    }

    /**
     * Aggiunge un commento a un thread esistente
     * 
     * @param pullRequestId ID della PR
     * @param threadId ID del thread
     * @param content Contenuto del commento
     * @return Il commento creato
     */
    @Throws(AzureDevOpsApiException::class)
    fun addCommentToThread(pullRequestId: Int, threadId: Int, content: String): Comment {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException("Azure DevOps non configurato.")
        }

        val request = CreateCommentRequest(content = content)
        val url = buildApiUrl(config.project, config.repository, "/pullRequests/$pullRequestId/threads/$threadId/comments?api-version=$API_VERSION")
        
        logger.info("Adding comment to thread #$threadId in PR #$pullRequestId")
        
        return try {
            val response = executePost(url, request, config.personalAccessToken)
            gson.fromJson(response, Comment::class.java)
        } catch (e: Exception) {
            logger.error("Failed to add comment", e)
            throw AzureDevOpsApiException("Errore durante l'aggiunta del commento: ${e.message}", e)
        }
    }

    /**
     * Aggiorna lo status di un thread di commenti
     * Usa Azure DevOps API 7.2 con formato corretto
     * 
     * @param pullRequestId ID della PR
     * @param threadId ID del thread
     * @param status Nuovo status (Active, Fixed, etc.)
     */
    @Throws(AzureDevOpsApiException::class)
    fun updateThreadStatus(pullRequestId: Int, threadId: Int, status: ThreadStatus) {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException("Azure DevOps non configurato.")
        }

        // Crea il request con il formato corretto per l'API
        val request = UpdateThreadStatusRequest(status)
        
        val url = buildApiUrl(config.project, config.repository, "/pullRequests/$pullRequestId/threads/$threadId?api-version=$API_VERSION")
        
        logger.info("Updating thread #$threadId status to ${status.getDisplayName()} (API value: ${status.toApiValue()}) in PR #$pullRequestId")
        logger.info("Request body: ${gson.toJson(request)}")
        
        try {
            val response = executePatch(url, request, config.personalAccessToken)
            logger.info("Thread status updated successfully. Response: $response")
        } catch (e: Exception) {
            logger.error("Failed to update thread status", e)
            throw AzureDevOpsApiException("Errore durante l'aggiornamento dello stato del thread: ${e.message}", e)
        }
    }

    /**
     * Trova la Pull Request associata a un branch specifico
     * 
     * @param branchName Nome del branch (senza refs/heads/)
     * @return La PR attiva associata al branch, o null se non esiste
     */
    @Throws(AzureDevOpsApiException::class)
    fun findPullRequestForBranch(branchName: String): PullRequest? {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()

        if (!config.isValid()) {
            throw AzureDevOpsApiException("Azure DevOps non configurato.")
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
            null // Ritorna null invece di lanciare eccezione
        }
    }

    /**
     * Esegue una richiesta GET
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
     * Esegue una richiesta POST
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

            // Scrivi il body
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
     * Esegue una richiesta PATCH
     * Usa reflection per abilitare PATCH in HttpURLConnection
     */
    @Throws(IOException::class, AzureDevOpsApiException::class)
    private fun executePatch(urlString: String, body: Any, token: String): String {
        val url = java.net.URI(urlString).toURL()
        val connection = url.openConnection() as HttpURLConnection
        
        try {
            // Workaround per PATCH: usa reflection per impostare il metodo
            // HttpURLConnection non supporta PATCH di default
            try {
                val methodField = HttpURLConnection::class.java.getDeclaredField("method")
                methodField.isAccessible = true
                methodField.set(connection, "PATCH")
            } catch (e: Exception) {
                logger.warn("Failed to set PATCH method via reflection, trying setRequestMethod", e)
                // Fallback: prova comunque (funziona su alcune JVM)
                connection.requestMethod = "PATCH"
            }
            
            connection.setRequestProperty("Authorization", createAuthHeader(token))
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("Accept", "application/json")
            connection.doOutput = true
            connection.connectTimeout = 10000
            connection.readTimeout = 10000

            // Scrivi il body
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
     * Crea l'header di autenticazione Basic Auth con il PAT
     */
    private fun createAuthHeader(token: String): String {
        val credentials = ":$token"
        val encodedCredentials = Base64.getEncoder().encodeToString(credentials.toByteArray(StandardCharsets.UTF_8))
        return "Basic $encodedCredentials"
    }

    /**
     * Gestisce le risposte di errore da Azure DevOps
     */
    private fun handleErrorResponse(statusCode: Int, errorBody: String): AzureDevOpsApiException {
        logger.warn("Azure DevOps API error - Status: $statusCode, Body: $errorBody")
        
        // Prova a parsare l'errore
        val errorMessage = try {
            val error = gson.fromJson(errorBody, AzureDevOpsErrorResponse::class.java)
            error.message ?: "Errore sconosciuto"
        } catch (e: JsonSyntaxException) {
            errorBody.ifEmpty { "Errore HTTP $statusCode" }
        }

        return when (statusCode) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> 
                AzureDevOpsApiException("Autenticazione fallita. Verifica il Personal Access Token (401)")
            HttpURLConnection.HTTP_FORBIDDEN -> 
                AzureDevOpsApiException("Permessi insufficienti. Verifica i permessi del PAT (403)")
            HttpURLConnection.HTTP_NOT_FOUND -> 
                AzureDevOpsApiException("Risorsa non trovata. Verifica Organization, Project e Repository (404)")
            HttpURLConnection.HTTP_CONFLICT -> 
                AzureDevOpsApiException("Conflitto: $errorMessage (409)")
            HttpURLConnection.HTTP_BAD_REQUEST -> 
                AzureDevOpsApiException("Richiesta non valida: $errorMessage (400)")
            else -> 
                AzureDevOpsApiException("Errore HTTP $statusCode: $errorMessage")
        }
    }
    
    /**
     * Recupera tutte le modifiche (file changes) di una Pull Request
     * 
     * @param pullRequestId ID della PR
     * @return Lista di modifiche
     */
    @Throws(AzureDevOpsApiException::class)
    fun getPullRequestChanges(pullRequestId: Int): List<PullRequestChange> {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()
        
        // Prima ottieni la PR per avere l'ultima iteration
        val pr = getPullRequest(pullRequestId)
        
        // Ora ottieni le modifiche dell'ultima iteration
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
     * Recupera il contenuto di un file a un commit specifico
     * 
     * @param commitId SHA del commit
     * @param filePath Path del file (es: "/src/main/Program.cs")
     * @return Contenuto del file come stringa
     */
    @Throws(AzureDevOpsApiException::class)
    /**
     * Recupera il contenuto di un file a un commit specifico
     * 
     * @param commitId SHA del commit
     * @param filePath Percorso del file (es: /src/main.cs)
     * @return Contenuto del file come stringa
     */
    fun getFileContent(commitId: String, filePath: String): String {
        val configService = AzureDevOpsConfigService.getInstance(project)
        val config = configService.getConfig()
        
        // Encode del path per URL
        val encodedPath = URLEncoder.encode(filePath, StandardCharsets.UTF_8.toString())
        
        // Endpoint per ottenere il contenuto del file
        // includeContent=true fa tornare il contenuto nel campo "content"
        val url = buildApiUrl(
            config.project,
            config.repository,
            "/items?path=$encodedPath&versionDescriptor.version=$commitId&versionDescriptor.versionType=commit&includeContent=true"
        ) + "&api-version=$API_VERSION"
        
        logger.info("Getting file content from: $url")
        
        val response = executeGet(url, config.personalAccessToken)
        
        // Parsa la risposta JSON per estrarre il contenuto
        return try {
            val jsonObject = gson.fromJson(response, com.google.gson.JsonObject::class.java)
            
            // Il contenuto è nel campo "content"
            val content = jsonObject.get("content")?.asString ?: ""
            
            logger.info("Extracted content: ${content.length} characters")
            
            content
        } catch (e: Exception) {
            logger.error("Failed to parse file content response", e)
            logger.error("Response was: $response")
            ""
        }
    }
}

/**
 * Eccezione personalizzata per errori dell'API di Azure DevOps
 */
class AzureDevOpsApiException(message: String, cause: Throwable? = null) : Exception(message, cause)
