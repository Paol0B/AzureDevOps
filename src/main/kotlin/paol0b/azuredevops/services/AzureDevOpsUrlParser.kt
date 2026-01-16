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
