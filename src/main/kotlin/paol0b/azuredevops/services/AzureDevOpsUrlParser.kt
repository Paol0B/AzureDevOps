package paol0b.azuredevops.services

import com.intellij.openapi.diagnostic.Logger
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Utility to parse Azure DevOps URLs
 */
object AzureDevOpsUrlParser {
    private val logger = Logger.getInstance(AzureDevOpsUrlParser::class.java)

    // Pattern for HTTPS URL: https://[username@]{server}/{organization}/{project}/_git/{repository}
    // Supports dev.azure.com, visualstudio.com, and custom on-premise servers
    // Also supports URL-encoded characters (e.g., Connettivit%C3%A0)
    private val HTTPS_PATTERN = Pattern.compile(
        "https://(?:[^@]+@)?([^/]+)/([^/]+)/([^/]+)/_git/([^/]+?)(?:\\.git)?/?$"
    )

    // Pattern for alternative HTTPS URL: https://[username@]{organization}.visualstudio.com/{project}/_git/{repository}
    private val VISUALSTUDIO_PATTERN = Pattern.compile(
        "https://(?:[^@]+@)?([^.]+)\\.visualstudio\\.com/([^/]+)/_git/([^/]+?)(?:\\.git)?/?$"
    )

    // Pattern for SSH v3: git@ssh.{server}:v3/{organization}/{project}/{repository}
    private val SSH_V3_PATTERN = Pattern.compile(
        "git@ssh\\.([^:]+):v3/([^/]+)/([^/]+)/([^/]+?)(?:\\.git)?/?$"
    )

    // Pattern for legacy SSH: {organization}@vs-ssh.{server}:v3/{organization}/{project}/{repository}
    private val SSH_LEGACY_PATTERN = Pattern.compile(
        "[^@]+@vs-ssh\\.([^:]+):v3/([^/]+)/([^/]+)/([^/]+?)(?:\\.git)?/?$"
    )

    /**
     * Parses the URL to extract baseUrl, organization, project, and repository
     */
    fun parse(url: String): AzureDevOpsRepoInfo? {
        // Try standard HTTPS pattern (dev.azure.com, custom on-premise, etc.)
        var matcher = HTTPS_PATTERN.matcher(url)
        if (matcher.matches()) {
            val server = matcher.group(1)
            val organization = urlDecode(matcher.group(2))
            val project = urlDecode(matcher.group(3))
            val repository = urlDecode(matcher.group(4))
            val baseUrl = "https://$server/$organization"
            
            return AzureDevOpsRepoInfo(
                baseUrl = baseUrl,
                organization = organization,
                project = project,
                repository = repository,
                remoteUrl = url,
                useVisualStudioDomain = false
            )
        }

        // Try VisualStudio.com pattern
        matcher = VISUALSTUDIO_PATTERN.matcher(url)
        if (matcher.matches()) {
            val organization = urlDecode(matcher.group(1))
            val project = urlDecode(matcher.group(2))
            val repository = urlDecode(matcher.group(3))
            val baseUrl = "https://$organization.visualstudio.com"
            
            return AzureDevOpsRepoInfo(
                baseUrl = baseUrl,
                organization = organization,
                project = project,
                repository = repository,
                remoteUrl = url,
                useVisualStudioDomain = true
            )
        }

        // Try SSH v3 pattern
        matcher = SSH_V3_PATTERN.matcher(url)
        if (matcher.matches()) {
            val server = matcher.group(1)
            val organization = urlDecode(matcher.group(2))
            val project = urlDecode(matcher.group(3))
            val repository = urlDecode(matcher.group(4))
            // For SSH, convert ssh.dev.azure.com -> dev.azure.com
            val httpServer = server.removePrefix("ssh.")
            val baseUrl = "https://$httpServer/$organization"
            
            return AzureDevOpsRepoInfo(
                baseUrl = baseUrl,
                organization = organization,
                project = project,
                repository = repository,
                remoteUrl = url,
                useVisualStudioDomain = false
            )
        }

        // Try legacy SSH pattern
        matcher = SSH_LEGACY_PATTERN.matcher(url)
        if (matcher.matches()) {
            val server = matcher.group(1)
            val organization = urlDecode(matcher.group(2))
            val project = urlDecode(matcher.group(3))
            val repository = urlDecode(matcher.group(4))
            // For legacy SSH, convert vs-ssh.visualstudio.com -> {org}.visualstudio.com
            val baseUrl = "https://$organization.visualstudio.com"
            
            return AzureDevOpsRepoInfo(
                baseUrl = baseUrl,
                organization = organization,
                project = project,
                repository = repository,
                remoteUrl = url,
                useVisualStudioDomain = true
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
}
