package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Information detected from an Azure DevOps repository
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
 * Service to automatically detect if the Git repository is Azure DevOps
 * and extract organization, project, and repository from the remote URL
 */
@Service(Service.Level.PROJECT)
class AzureDevOpsRepositoryDetector(private val project: Project) {

    private val logger = Logger.getInstance(AzureDevOpsRepositoryDetector::class.java)
    
    // Cache to avoid repeated detections
    @Volatile
    private var cachedInfo: AzureDevOpsRepoInfo? = null
    @Volatile
    private var cacheTimestamp: Long = 0
    private val CACHE_VALIDITY_MS = 30000L // 30 seconds

    companion object {
        // Pattern for HTTPS URL: https://[username@]dev.azure.com/{organization}/{project}/_git/{repository}
        // Also supports URL-encoded characters (e.g., Connettivit%C3%A0)
        private val HTTPS_PATTERN = Pattern.compile(
            "https://(?:[^@]+@)?dev\\.azure\\.com/([^/]+)/([^/]+)/_git/([^/]+?)(?:\\.git)?/?$"
        )
        
        // Pattern for alternative HTTPS URL: https://[username@]{organization}.visualstudio.com/{project}/_git/{repository}
        private val VISUALSTUDIO_PATTERN = Pattern.compile(
            "https://(?:[^@]+@)?([^.]+)\\.visualstudio\\.com/([^/]+)/_git/([^/]+?)(?:\\.git)?/?$"
        )
        
        // Pattern for SSH v3: git@ssh.dev.azure.com:v3/{organization}/{project}/{repository}
        private val SSH_V3_PATTERN = Pattern.compile(
            "git@ssh\\.dev\\.azure\\.com:v3/([^/]+)/([^/]+)/([^/]+?)(?:\\.git)?/?$"
        )
        
        // Pattern for legacy SSH: {organization}@vs-ssh.visualstudio.com:v3/{organization}/{project}/{repository}
        private val SSH_LEGACY_PATTERN = Pattern.compile(
            "[^@]+@vs-ssh\\.visualstudio\\.com:v3/([^/]+)/([^/]+)/([^/]+?)(?:\\.git)?/?$"
        )

        fun getInstance(project: Project): AzureDevOpsRepositoryDetector {
            return project.getService(AzureDevOpsRepositoryDetector::class.java)
        }
    }

    /**
     * Detects if the current repository is an Azure DevOps repository
     */
    fun isAzureDevOpsRepository(): Boolean {
        return detectAzureDevOpsInfo() != null
    }

    /**
     * Automatically detects Azure DevOps info from the remote URL of the repository
     */
    fun detectAzureDevOpsInfo(): AzureDevOpsRepoInfo? {
        // Check cache
        val now = System.currentTimeMillis()
        if (cachedInfo != null && (now - cacheTimestamp) < CACHE_VALIDITY_MS) {
            return cachedInfo
        }
        
        val gitService = GitRepositoryService.getInstance(project)
        val repository = gitService.getCurrentRepository() ?: run {
            logger.debug("No Git repository found")
            // Do not update cache if no repository, might be temporary
            return cachedInfo // Return previous cache if exists
        }

        val remotes = repository.remotes
        if (remotes.isEmpty()) {
            logger.debug("No Git remotes found")
            return cachedInfo // Return previous cache if exists
        }

        // Try all remotes (origin, upstream, etc.)
        for (remote in remotes) {
            val urls = remote.urls
            for (url in urls) {
                logger.debug("Checking remote URL: $url")
                
                val info = parseAzureDevOpsUrl(url)
                if (info != null) {
                    logger.info("Detected Azure DevOps repository: ${info.organization}/${info.project}/${info.repository}")
                    // Update cache
                    cachedInfo = info
                    cacheTimestamp = now
                    return info
                }
            }
        }

        logger.debug("Not an Azure DevOps repository")
        // Update cache with null only if there was no previous cache
        if (cachedInfo == null) {
            cacheTimestamp = now
        }
        return cachedInfo
    }
    
    /**
     * Invalidates the cache to force a new detection
     */
    fun invalidateCache() {
        cachedInfo = null
        cacheTimestamp = 0
    }

    /**
     * Parses the URL to extract organization, project, and repository
     */
    private fun parseAzureDevOpsUrl(url: String): AzureDevOpsRepoInfo? {
        // Try standard HTTPS pattern
        var matcher = HTTPS_PATTERN.matcher(url)
        if (matcher.matches()) {
            return AzureDevOpsRepoInfo(
                organization = urlDecode(matcher.group(1)),
                project = urlDecode(matcher.group(2)),
                repository = urlDecode(matcher.group(3)),
                remoteUrl = url
            )
        }

        // Try VisualStudio.com pattern
        matcher = VISUALSTUDIO_PATTERN.matcher(url)
        if (matcher.matches()) {
            return AzureDevOpsRepoInfo(
                organization = urlDecode(matcher.group(1)),
                project = urlDecode(matcher.group(2)),
                repository = urlDecode(matcher.group(3)),
                remoteUrl = url
            )
        }

        // Try SSH v3 pattern
        matcher = SSH_V3_PATTERN.matcher(url)
        if (matcher.matches()) {
            return AzureDevOpsRepoInfo(
                organization = urlDecode(matcher.group(1)),
                project = urlDecode(matcher.group(2)),
                repository = urlDecode(matcher.group(3)),
                remoteUrl = url
            )
        }

        // Try legacy SSH pattern
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
     * Decodes a URL-encoded string (e.g., "Connettivit%C3%A0" -> "Connettività")
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
     * Gets the automatically detected organization
     */
    fun getOrganization(): String? = detectAzureDevOpsInfo()?.organization

    /**
     * Gets the automatically detected project
     */
    fun getProject(): String? = detectAzureDevOpsInfo()?.project

    /**
     * Gets the automatically detected repository
     */
    fun getRepository(): String? = detectAzureDevOpsInfo()?.repository

    /**
     * Gets a user-friendly description of the detected repository
     */
    fun getRepositoryDescription(): String? {
        val info = detectAzureDevOpsInfo() ?: return null
        return "${info.organization}/${info.project}/${info.repository}"
    }
}
