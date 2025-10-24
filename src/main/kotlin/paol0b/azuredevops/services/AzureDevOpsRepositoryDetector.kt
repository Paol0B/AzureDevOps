package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Informazioni rilevate da un repository Azure DevOps
 */
data class AzureDevOpsRepoInfo(
    val organization: String,
    val project: String,
    val repository: String,
    val remoteUrl: String
) {
    fun isValid(): Boolean = organization.isNotBlank() && 
                            project.isNotBlank() && 
                            repository.isNotBlank()
}

/**
 * Servizio per rilevare automaticamente se il repository Git è di Azure DevOps
 * e estrarre organization, project e repository dall'URL remoto
 */
@Service(Service.Level.PROJECT)
class AzureDevOpsRepositoryDetector(private val project: Project) {

    private val logger = Logger.getInstance(AzureDevOpsRepositoryDetector::class.java)

    companion object {
        // Pattern per URL HTTPS: https://[username@]dev.azure.com/{organization}/{project}/_git/{repository}
        // Supporta anche URL-encoded characters (es: Connettivit%C3%A0)
        private val HTTPS_PATTERN = Pattern.compile(
            "https://(?:[^@]+@)?dev\\.azure\\.com/([^/]+)/([^/]+)/_git/([^/]+?)(?:\\.git)?/?$"
        )
        
        // Pattern per URL HTTPS alternativo: https://[username@]{organization}.visualstudio.com/{project}/_git/{repository}
        private val VISUALSTUDIO_PATTERN = Pattern.compile(
            "https://(?:[^@]+@)?([^.]+)\\.visualstudio\\.com/([^/]+)/_git/([^/]+?)(?:\\.git)?/?$"
        )
        
        // Pattern per SSH v3: git@ssh.dev.azure.com:v3/{organization}/{project}/{repository}
        private val SSH_V3_PATTERN = Pattern.compile(
            "git@ssh\\.dev\\.azure\\.com:v3/([^/]+)/([^/]+)/([^/]+?)(?:\\.git)?/?$"
        )
        
        // Pattern per SSH legacy: {organization}@vs-ssh.visualstudio.com:v3/{organization}/{project}/{repository}
        private val SSH_LEGACY_PATTERN = Pattern.compile(
            "[^@]+@vs-ssh\\.visualstudio\\.com:v3/([^/]+)/([^/]+)/([^/]+?)(?:\\.git)?/?$"
        )

        fun getInstance(project: Project): AzureDevOpsRepositoryDetector {
            return project.getService(AzureDevOpsRepositoryDetector::class.java)
        }
    }

    /**
     * Rileva se il repository corrente è un repository Azure DevOps
     */
    fun isAzureDevOpsRepository(): Boolean {
        return detectAzureDevOpsInfo() != null
    }

    /**
     * Rileva automaticamente le informazioni di Azure DevOps dall'URL remoto del repository
     */
    fun detectAzureDevOpsInfo(): AzureDevOpsRepoInfo? {
        val gitService = GitRepositoryService.getInstance(project)
        val repository = gitService.getCurrentRepository() ?: run {
            logger.debug("No Git repository found")
            return null
        }

        val remotes = repository.remotes
        if (remotes.isEmpty()) {
            logger.debug("No Git remotes found")
            return null
        }

        // Prova tutti i remote (origin, upstream, ecc.)
        for (remote in remotes) {
            val urls = remote.urls
            for (url in urls) {
                logger.debug("Checking remote URL: $url")
                
                val info = parseAzureDevOpsUrl(url)
                if (info != null) {
                    logger.info("Detected Azure DevOps repository: ${info.organization}/${info.project}/${info.repository}")
                    return info
                }
            }
        }

        logger.debug("Not an Azure DevOps repository")
        return null
    }

    /**
     * Parsing dell'URL per estrarre organization, project e repository
     */
    private fun parseAzureDevOpsUrl(url: String): AzureDevOpsRepoInfo? {
        // Prova pattern HTTPS standard
        var matcher = HTTPS_PATTERN.matcher(url)
        if (matcher.matches()) {
            return AzureDevOpsRepoInfo(
                organization = urlDecode(matcher.group(1)),
                project = urlDecode(matcher.group(2)),
                repository = urlDecode(matcher.group(3)),
                remoteUrl = url
            )
        }

        // Prova pattern VisualStudio.com
        matcher = VISUALSTUDIO_PATTERN.matcher(url)
        if (matcher.matches()) {
            return AzureDevOpsRepoInfo(
                organization = urlDecode(matcher.group(1)),
                project = urlDecode(matcher.group(2)),
                repository = urlDecode(matcher.group(3)),
                remoteUrl = url
            )
        }

        // Prova pattern SSH v3
        matcher = SSH_V3_PATTERN.matcher(url)
        if (matcher.matches()) {
            return AzureDevOpsRepoInfo(
                organization = urlDecode(matcher.group(1)),
                project = urlDecode(matcher.group(2)),
                repository = urlDecode(matcher.group(3)),
                remoteUrl = url
            )
        }

        // Prova pattern SSH legacy
        matcher = SSH_LEGACY_PATTERN.matcher(url)
        if (matcher.matches()) {
            return AzureDevOpsRepoInfo(
                organization = urlDecode(matcher.group(1)),
                project = urlDecode(matcher.group(2)),
                repository = urlDecode(matcher.group(3)),
                remoteUrl = url
            )
        }

        return null
    }
    
    /**
     * Decodifica una stringa URL-encoded (es: "Connettivit%C3%A0" -> "Connettività")
     */
    private fun urlDecode(value: String): String {
        return try {
            URLDecoder.decode(value, StandardCharsets.UTF_8.toString())
        } catch (e: Exception) {
            logger.warn("Failed to URL decode: $value", e)
            value
        }
    }

    /**
     * Ottiene l'organization rilevata automaticamente
     */
    fun getOrganization(): String? = detectAzureDevOpsInfo()?.organization

    /**
     * Ottiene il project rilevato automaticamente
     */
    fun getProject(): String? = detectAzureDevOpsInfo()?.project

    /**
     * Ottiene il repository rilevato automaticamente
     */
    fun getRepository(): String? = detectAzureDevOpsInfo()?.repository

    /**
     * Ottiene una descrizione user-friendly del repository rilevato
     */
    fun getRepositoryDescription(): String? {
        val info = detectAzureDevOpsInfo() ?: return null
        return "${info.organization}/${info.project}/${info.repository}"
    }
}
