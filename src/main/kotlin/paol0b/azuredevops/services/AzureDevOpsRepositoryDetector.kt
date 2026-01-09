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
    val remoteUrl: String,
    val useVisualStudioDomain: Boolean = false
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
                
                val info = AzureDevOpsUrlParser.parse(url)
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
