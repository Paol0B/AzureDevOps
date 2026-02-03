package paol0b.azuredevops.checkout

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.intellij.openapi.diagnostic.Logger
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.Base64

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
    private val httpClient = OkHttpClient()

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
        val credentials = ":$token"
        val encodedCredentials = Base64.getEncoder()
            .encodeToString(credentials.toByteArray(Charsets.UTF_8))
        
        val request = Request.Builder()
            .url(urlString)
            .get()
            .addHeader("Authorization", "Basic $encodedCredentials")
            .addHeader("Accept", "application/json")
            .build()

        return httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: ""
                throw Exception("HTTP error ${response.code}: $errorBody")
            }
            response.body?.string() ?: ""
        }
    }
}
