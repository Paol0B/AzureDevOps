package paol0b.azuredevops.util

import com.intellij.openapi.application.ApplicationManager
import java.net.URI

/**
 * Shared utility functions used across the plugin.
 */
object PluginUtil {

    /**
     * Returns true if the IDE is running in internal/dev mode.
     */
    fun isDevMode(): Boolean {
        return try {
            val app = ApplicationManager.getApplication()
            app != null && app.isInternal
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * Extracts the organization name from an Azure DevOps URL.
     *
     * Supports:
     * - `https://dev.azure.com/{organization}` → `{organization}`
     * - `https://{organization}.visualstudio.com` → `{organization}`
     * - Self-hosted → host as identifier
     */
    fun extractOrganizationFromUrl(url: String): String? {
        return try {
            val uri = URI(url)

            // dev.azure.com: https://dev.azure.com/{organization}
            if (uri.host?.contains("dev.azure.com") == true) {
                val path = uri.path.trim('/')
                return if (path.isNotEmpty()) path.split("/").firstOrNull() else null
            }

            // visualstudio.com: https://{organization}.visualstudio.com
            if (uri.host?.endsWith(".visualstudio.com") == true) {
                return uri.host?.substringBefore(".visualstudio.com")
            }

            // Self-hosted: use the full host
            uri.host
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses Azure DevOps API error messages into user-friendly text.
     * Handles common TF error codes from the API.
     */
    fun parseApiErrorMessage(message: String?): String {
        if (message == null) return "Unknown error occurred"

        return when {
            message.contains("TF401027") -> "Branch policies are not met. Consider using auto-complete or override policies."
            message.contains("TF401171") -> "You don't have permission to perform this action."
            message.contains("TF401179") -> "Only the pull request author can perform this action, or the PR must have at least one approved reviewer."
            message.contains("TF401181") -> "Required reviewers must approve before completion."
            else -> message.take(200)
        }
    }
}
