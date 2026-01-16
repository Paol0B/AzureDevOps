package paol0b.azuredevops.services

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * Utilities for Azure DevOps URL parsing and normalization.
 */
object AzureDevOpsUrlUtil {

    fun extractOrganizationFromUrl(url: String): String? {
        return try {
            val normalizedUrl = normalizeForParsing(url)
            val uri = URI(normalizedUrl)
            val host = uri.host ?: return null

            if (host.endsWith(".visualstudio.com", ignoreCase = true)) {
                return host.substringBefore(".visualstudio.com")
            }

            val path = uri.path?.trim('/') ?: return null
            if (path.isBlank()) return null

            val segments = path.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) return null

            return if (segments.first().equals("v3", ignoreCase = true) && segments.size > 1) {
                segments[1]
            } else {
                segments[0]
            }
        } catch (e: Exception) {
            null
        }
    }

    fun isSameOrganization(leftUrl: String, rightUrl: String): Boolean {
        val leftOrg = extractOrganizationFromUrl(leftUrl)
        val rightOrg = extractOrganizationFromUrl(rightUrl)
        if (leftOrg.isNullOrBlank() || rightOrg.isNullOrBlank()) return false
        return leftOrg.equals(rightOrg, ignoreCase = true)
    }

    /**
     * Normalize Azure DevOps URL to handle all special characters, spaces, etc.
     * Properly decodes and reconstructs the URL to ensure Git can handle it.
     */
    fun normalizeAzureDevOpsUrl(url: String): String {
        return try {
            var decodedUrl = url
            var previousUrl: String

            do {
                previousUrl = decodedUrl
                decodedUrl = URLDecoder.decode(previousUrl, StandardCharsets.UTF_8)
            } while (decodedUrl != previousUrl)

            val uri = URI(decodedUrl)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return url
            val path = uri.path ?: return url

            val segments = path.split("/").filter { it.isNotEmpty() }
            val encodedPath = segments.joinToString("/") { segment ->
                URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20")
            }

            "$scheme://$host/$encodedPath"
        } catch (e: Exception) {
            url
        }
    }

    private fun normalizeForParsing(url: String): String {
        return if (url.startsWith("git@") || url.startsWith("ssh://")) {
            url.replace("git@", "ssh://").replace(":", "/")
        } else {
            url
        }
    }
}