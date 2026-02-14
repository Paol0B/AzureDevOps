package paol0b.azuredevops.services

import com.intellij.openapi.diagnostic.Logger
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.regex.Pattern

/**
 * Utility to parse Azure DevOps URLs (cloud and self-hosted)
 */
object AzureDevOpsUrlParser {
    private val logger = Logger.getInstance(AzureDevOpsUrlParser::class.java)

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

    // Pattern for self-hosted Azure DevOps Server:
    // https://[username@]server[:port][/path]/{collection}/{project}/_git/{repository}
    // The _git segment is the key marker for Azure DevOps Server repositories.
    private val SELF_HOSTED_PATTERN = Pattern.compile(
        "https?://(?:[^@]+@)?([^/]+)(/[^_]+)?/([^/]+)/_git/([^/]+?)(?:\\.git)?/?$"
    )

    /**
     * Parses the URL to extract organization, project, and repository
     */
    fun parse(url: String): AzureDevOpsRepoInfo? {
        // Try standard HTTPS pattern
        var matcher = HTTPS_PATTERN.matcher(url)
        if (matcher.matches()) {
            return AzureDevOpsRepoInfo(
                organization = urlDecode(matcher.group(1)),
                project = urlDecode(matcher.group(2)),
                repository = urlDecode(matcher.group(3)),
                remoteUrl = url,
                useVisualStudioDomain = false
            )
        }

        // Try VisualStudio.com pattern
        matcher = VISUALSTUDIO_PATTERN.matcher(url)
        if (matcher.matches()) {
            return AzureDevOpsRepoInfo(
                organization = urlDecode(matcher.group(1)),
                project = urlDecode(matcher.group(2)),
                repository = urlDecode(matcher.group(3)),
                remoteUrl = url,
                useVisualStudioDomain = true
            )
        }

        // Try SSH v3 pattern
        matcher = SSH_V3_PATTERN.matcher(url)
        if (matcher.matches()) {
            return AzureDevOpsRepoInfo(
                organization = urlDecode(matcher.group(1)),
                project = urlDecode(matcher.group(2)),
                repository = urlDecode(matcher.group(3)),
                remoteUrl = url,
                useVisualStudioDomain = false
            )
        }

        // Try legacy SSH pattern
        matcher = SSH_LEGACY_PATTERN.matcher(url)
        if (matcher.matches()) {
            return AzureDevOpsRepoInfo(
                organization = urlDecode(matcher.group(1)),
                project = urlDecode(matcher.group(2)),
                repository = urlDecode(matcher.group(3)),
                remoteUrl = url,
                useVisualStudioDomain = true
            )
        }

        // Try self-hosted Azure DevOps Server pattern
        matcher = SELF_HOSTED_PATTERN.matcher(url)
        if (matcher.matches()) {
            val host = matcher.group(1)                        // e.g. "tfs.company.com"
            val collectionPath = matcher.group(2)?.trim('/') ?: ""  // e.g. "tfs/DefaultCollection"
            val project = urlDecode(matcher.group(3))          // e.g. "MyProject"
            val repository = urlDecode(matcher.group(4))       // e.g. "MyRepo"

            // Skip known cloud hosts — they should be caught by the patterns above
            if (host.contains("dev.azure.com") || host.endsWith(".visualstudio.com")) {
                return null
            }

            // Build the self-hosted server base URL (scheme + host + collection path)
            val scheme = if (url.startsWith("https")) "https" else "http"
            val selfHostedBaseUrl = if (collectionPath.isNotEmpty()) {
                "$scheme://$host/$collectionPath"
            } else {
                "$scheme://$host"
            }

            // For self-hosted, the "organization" field holds the collection/path identifier
            val organization = if (collectionPath.isNotEmpty()) {
                collectionPath.split("/").lastOrNull() ?: host
            } else {
                host
            }

            return AzureDevOpsRepoInfo(
                organization = organization,
                project = project,
                repository = repository,
                remoteUrl = url,
                useVisualStudioDomain = false,
                selfHostedUrl = selfHostedBaseUrl
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
