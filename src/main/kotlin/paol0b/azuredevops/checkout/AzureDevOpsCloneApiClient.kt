package paol0b.azuredevops.checkout

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import java.net.HttpURLConnection
import java.net.URI

/**
 * API client for fetching projects and repositories from Azure DevOps
 * Uses Azure DevOps REST API 7.0+ (non-deprecated)
 */
class AzureDevOpsCloneApiClient(
    private val serverUrl: String,
    private val token: String
) {
    private val logger = Logger.getInstance(AzureDevOpsCloneApiClient::class.java)
    private val gson = Gson()

    data class Project(
        val id: String,
        val name: String,
        val description: String?
    )

    data class Repository(
        val id: String,
        val name: String,
        val remoteUrl: String,
        val webUrl: String,
        val projectId: String
    )

    /**
     * Fetch all projects from the Azure DevOps organization
     */
    fun getProjects(): List<Project> {
        val url = "$serverUrl/_apis/projects?api-version=7.0"
        val json = executeGet(url)
        
        val jsonObject = gson.fromJson(json, JsonObject::class.java)
        val projects = mutableListOf<Project>()
        
        jsonObject.getAsJsonArray("value")?.forEach { element ->
            val projectObj = element.asJsonObject
            projects.add(Project(
                id = projectObj.get("id").asString,
                name = projectObj.get("name").asString,
                description = projectObj.get("description")?.asString
            ))
        }
        
        logger.info("Fetched ${projects.size} projects from $serverUrl")
        return projects
    }

    /**
     * Fetch all repositories for a specific project
     */
    fun getRepositories(projectId: String): List<Repository> {
        val url = "$serverUrl/_apis/git/repositories?api-version=7.0"
        val json = executeGet(url)
        
        val jsonObject = gson.fromJson(json, JsonObject::class.java)
        val repositories = mutableListOf<Repository>()
        
        jsonObject.getAsJsonArray("value")?.forEach { element ->
            val repoObj = element.asJsonObject
            val repoProjectId = repoObj.getAsJsonObject("project")?.get("id")?.asString
            
            // Filter repositories by project
            if (repoProjectId == projectId) {
                repositories.add(Repository(
                    id = repoObj.get("id").asString,
                    name = repoObj.get("name").asString,
                    remoteUrl = repoObj.get("remoteUrl").asString,
                    webUrl = repoObj.get("webUrl").asString,
                    projectId = repoProjectId
                ))
            }
        }
        
        logger.info("Fetched ${repositories.size} repositories for project $projectId")
        return repositories
    }

    private fun executeGet(urlString: String): String {
        val connection = URI(urlString).toURL().openConnection() as HttpURLConnection
        
        try {
            connection.requestMethod = "GET"
            val credentials = ":$token"
            val encodedCredentials = java.util.Base64.getEncoder()
                .encodeToString(credentials.toByteArray(java.nio.charset.StandardCharsets.UTF_8))
            connection.setRequestProperty("Authorization", "Basic $encodedCredentials")
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 30000
            connection.readTimeout = 30000

            val responseCode = connection.responseCode
            
            if (responseCode != HttpURLConnection.HTTP_OK) {
                val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw Exception("HTTP error $responseCode: $errorBody")
            }

            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}
