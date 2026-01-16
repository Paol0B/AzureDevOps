package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.charset.StandardCharsets

/**
 * Service to interact with Git Credential Helper
 * to automatically retrieve the saved Personal Access Token
 */
@Service(Service.Level.PROJECT)
class GitCredentialHelperService(private val project: Project) {

    private val logger = Logger.getInstance(GitCredentialHelperService::class.java)

    companion object {
        fun getInstance(project: Project): GitCredentialHelperService {
            return project.getService(GitCredentialHelperService::class.java)
        }
    }

    /**
     * Attempts to retrieve credentials for Azure DevOps from Git Credential Helper
     *
     * @param url Azure DevOps repository URL
     * @return The Personal Access Token if found, null otherwise
     */
    fun getCredentialsFromHelper(url: String): String? {
        return try {
            // Try first with the specific protocol
            getCredentialForUrl(url) ?:
            // If it doesn't work, try with generic Azure DevOps URLs
            getCredentialForUrl("https://dev.azure.com") ?:
            getCredentialForUrl("https://ssh.dev.azure.com")
        } catch (e: Exception) {
            logger.warn("Failed to retrieve credentials from Git credential helper", e)
            null
        }
    }

    /**
     * Retrieves credentials for a specific URL using git credential fill
     */
    private fun getCredentialForUrl(url: String): String? {
        try {
            // Extract protocol and host from the URL
            val (protocol, host, path) = parseUrl(url) ?: return null
            
            logger.info("Attempting to get credentials for: protocol=$protocol, host=$host, path=$path")
            
            // Prepare input for git credential fill
            val input = buildString {
                appendLine("protocol=$protocol")
                appendLine("host=$host")
                if (path.isNotBlank()) {
                    appendLine("path=$path")
                }
                appendLine() // Empty line to terminate
            }
            
            // Run git credential fill with redirectErrorStream to avoid popup windows
            val processBuilder = ProcessBuilder("git", "credential", "fill")
            
            // IMPORTANT: Prevent process window from opening on Windows
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                // Hide process window on Windows
                processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)
                processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
            } else {
                processBuilder.redirectErrorStream(true)
            }
            
            val process = processBuilder.start()
            
            // Send input
            process.outputStream.use { outputStream ->
                outputStream.write(input.toByteArray())
                outputStream.flush()
            }
            
            // Read output with timeout to avoid blocking
            val output = readProcessOutput(process, 5000) // 5 seconds timeout

            val exitCode = process.waitFor()
            
            if (exitCode == 0 && output.isNotBlank()) {
                // Parse output to extract password (PAT)
                return parseCredentialOutput(output)
            } else {
                logger.debug("git credential fill returned exit code: $exitCode, output: $output")
            }
        } catch (e: Exception) {
            logger.debug("Failed to get credential for $url", e)
        }
        
        return null
    }
    
    /**
     * Reads the output of the process with timeout to avoid blocking
     */
    private fun readProcessOutput(process: Process, timeoutMs: Long): String {
        val output = StringBuilder()
        val startTime = System.currentTimeMillis()
        
        process.inputStream.bufferedReader().use { reader ->
            while (System.currentTimeMillis() - startTime < timeoutMs) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    output.appendLine(line)
                } else {
                    Thread.sleep(50)
                    // Check if the process has terminated
                    if (!process.isAlive) {
                        // Read any remaining data
                        reader.lines().forEach { output.appendLine(it) }
                        break
                    }
                }
            }
        }
        
        return output.toString()
    }

    /**
     * Saves credentials in Git Credential Helper
     *
     * @param url Repository URL
     * @param username Username (for Azure DevOps can be blank or any value)
     * @param password Personal Access Token
     */
    fun saveCredentialsToHelper(url: String, username: String = "", password: String): Boolean {
        return try {
            val (protocol, host, path) = parseUrl(url) ?: return false
            
            val input = buildCredentialInput(protocol, host, path, username, password)
            val processBuilder = ProcessBuilder("git", "credential", "approve")
            GitRepositoryService.getInstance(project).getRepository()?.root?.let { root ->
                processBuilder.directory(File(root.path))
            }
            
            // IMPORTANT: Prevent process window from opening on Windows
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)
                processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
            } else {
                processBuilder.redirectErrorStream(true)
            }
            
            val process = processBuilder.start()
            
            process.outputStream.use { outputStream ->
                outputStream.write(input.toByteArray())
                outputStream.flush()
            }
            
            val exitCode = process.waitFor()

            val hostOnlySaved = saveHostOnlyCredential(protocol, host, username, password)

            if (exitCode == 0) {
                logger.info("Credentials saved to Git credential helper for $host")
                true || hostOnlySaved
            } else {
                logger.warn("Failed to save credentials to Git credential helper, exit code: $exitCode")
                hostOnlySaved
            }
        } catch (e: Exception) {
            logger.warn("Failed to save credentials to Git credential helper", e)
            false
        }
    }

    private fun saveHostOnlyCredential(protocol: String, host: String, username: String, password: String): Boolean {
        val isAzureDevOpsHost = host.equals("dev.azure.com", ignoreCase = true) ||
            host.endsWith(".visualstudio.com", ignoreCase = true)

        if (!isAzureDevOpsHost) {
            return false
        }

        return try {
            val input = buildCredentialInput(protocol, host, "", username, password)
            val processBuilder = ProcessBuilder("git", "credential", "approve")
            GitRepositoryService.getInstance(project).getRepository()?.root?.let { root ->
                processBuilder.directory(File(root.path))
            }

            if (System.getProperty("os.name").lowercase().contains("windows")) {
                processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)
                processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
            } else {
                processBuilder.redirectErrorStream(true)
            }

            val process = processBuilder.start()
            process.outputStream.use { outputStream ->
                outputStream.write(input.toByteArray())
                outputStream.flush()
            }
            process.waitFor() == 0
        } catch (e: Exception) {
            logger.debug("Failed to save host-only credential", e)
            false
        }
    }

    private fun buildCredentialInput(
        protocol: String,
        host: String,
        path: String,
        username: String,
        password: String
    ): String {
        return buildString {
            appendLine("protocol=$protocol")
            appendLine("host=$host")
            if (path.isNotBlank()) {
                appendLine("path=$path")
            }
            appendLine("username=${username.ifBlank { "PersonalAccessToken" }}")
            appendLine("password=$password")
            appendLine()
        }
    }

    /**
     * Checks if Git Credential Helper is available
     */
    fun isCredentialHelperAvailable(): Boolean {
        return try {
            // First, check whether a credential.helper is configured for this repository or globally.
            // This avoids calling `git credential --help` which on some Windows setups may open the
            // local HTML documentation (file://...) in the browser.
            val configBuilder = ProcessBuilder("git", "config", "--get", "credential.helper")
            if (System.getProperty("os.name").lowercase().contains("windows")) {
                configBuilder.redirectError(ProcessBuilder.Redirect.PIPE)
                configBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
            } else {
                configBuilder.redirectErrorStream(true)
            }
            val configProcess = configBuilder.start()
            val output = configProcess.inputStream.bufferedReader().use { it.readText().trim() }
            configProcess.waitFor()

            if (output.isNotBlank()) {
                // A helper is configured (e.g. 'manager' or 'manager-core') — consider it available
                true
            } else {
                // Fallback: verify that git is present on PATH by checking version
                val versionBuilder = ProcessBuilder("git", "--version")
                if (System.getProperty("os.name").lowercase().contains("windows")) {
                    versionBuilder.redirectError(ProcessBuilder.Redirect.PIPE)
                    versionBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
                } else {
                    versionBuilder.redirectErrorStream(true)
                }
                val versionProcess = versionBuilder.start()
                val exitCode = versionProcess.waitFor()
                exitCode == 0
            }
        } catch (e: Exception) {
            logger.debug("Git credential helper not available", e)
            false
        }
    }

    /**
     * Parses the output of git credential fill
     * Format:
     * protocol=https
     * host=dev.azure.com
     * username=...
     * password=...
     */
    private fun parseCredentialOutput(output: String): String? {
        val lines = output.lines()
        for (line in lines) {
            if (line.startsWith("password=")) {
                val password = line.substringAfter("password=").trim()
                if (password.isNotBlank()) {
                    return password
                }
            }
        }
        return null
    }

    /**
     * Parses a URL and extracts protocol, host, and path
     *
     * @return Triple of (protocol, host, path) or null if parsing fails
     */
    private fun parseUrl(url: String): Triple<String, String, String>? {
        return try {
            // Handle SSH URLs
            if (url.startsWith("git@") || url.startsWith("ssh://")) {
                val cleanUrl = url.replace("git@", "ssh://").replace(":", "/")
                val uri = java.net.URI(cleanUrl)
                return Triple("ssh", uri.host ?: "", uri.path?.removePrefix("/") ?: "")
            }
            
            // Handle HTTPS URLs
            val uri = java.net.URI(url)
            val protocol = uri.scheme ?: "https"
            val host = uri.host ?: return null
            val path = uri.path?.removePrefix("/")?.removeSuffix(".git") ?: ""
            
            Triple(protocol, host, path)
        } catch (e: Exception) {
            logger.debug("Failed to parse URL: $url", e)
            null
        }
    }

    /**
     * Attempts to retrieve credentials for the current repository
     */
    fun getCredentialsForCurrentRepository(): String? {
        val gitService = GitRepositoryService.getInstance(project)
        val remoteUrl = gitService.getRemoteUrl() ?: return null
        
        logger.info("Attempting to retrieve credentials for remote URL: $remoteUrl")
        return getCredentialsFromHelper(remoteUrl)
    }

    /**
     * Updates or adds the local git config http.extraHeader Authorization entry.
     * This keeps workflows that store auth headers in .git/config in sync
     * with refreshed OAuth tokens.
     */
    fun upsertAuthorizationHeader(token: String): Boolean {
        val repository = GitRepositoryService.getInstance(project).getRepository() ?: return false
        val repoDir = File(repository.root.path)

        val existingHeaders = readExtraHeaders(repoDir)
        val authHeader = buildAuthorizationHeader(token)

        if (existingHeaders!!.any { it == authHeader }) {
            return true
        }

        val hasAuthorizationHeader = existingHeaders!!.any { it.startsWith("Authorization:") }
        return if (hasAuthorizationHeader) {
            replaceAuthorizationHeader(repoDir, authHeader)
        } else {
            addAuthorizationHeader(repoDir, authHeader)
        }
    }

    private fun buildAuthorizationHeader(token: String): String {
        val encoded = java.util.Base64.getEncoder().encodeToString(":$token".toByteArray(StandardCharsets.UTF_8))
        return "Authorization: Basic $encoded"
    }

    private fun readExtraHeaders(repoDir: File): List<String>? {
        return try {
            val processBuilder = ProcessBuilder("git", "config", "--local", "--get-all", "http.extraHeader")
            processBuilder.directory(repoDir)

            if (System.getProperty("os.name").lowercase().contains("windows")) {
                processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)
                processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
            } else {
                processBuilder.redirectErrorStream(true)
            }

            val process = processBuilder.start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            process.waitFor()

            output.lines().map { it.trim() }.filter { it.isNotBlank() }
        } catch (e: Exception) {
            logger.debug("Failed to read http.extraHeader from git config", e)
            emptyList()
        }
    }

    private fun replaceAuthorizationHeader(repoDir: File, authHeader: String): Boolean {
        return try {
            val processBuilder = ProcessBuilder(
                "git",
                "config",
                "--local",
                "--replace-all",
                "http.extraHeader",
                authHeader,
                "^Authorization:.*"
            )
            processBuilder.directory(repoDir)

            if (System.getProperty("os.name").lowercase().contains("windows")) {
                processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)
                processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
            } else {
                processBuilder.redirectErrorStream(true)
            }

            val process = processBuilder.start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                logger.warn("Failed to update http.extraHeader in git config, exit code: $exitCode")
            }
            exitCode == 0
        } catch (e: Exception) {
            logger.warn("Failed to update http.extraHeader in git config", e)
            false
        }
    }

    private fun addAuthorizationHeader(repoDir: File, authHeader: String): Boolean {
        return try {
            val processBuilder = ProcessBuilder(
                "git",
                "config",
                "--local",
                "--add",
                "http.extraHeader",
                authHeader
            )
            processBuilder.directory(repoDir)

            if (System.getProperty("os.name").lowercase().contains("windows")) {
                processBuilder.redirectError(ProcessBuilder.Redirect.PIPE)
                processBuilder.redirectOutput(ProcessBuilder.Redirect.PIPE)
            } else {
                processBuilder.redirectErrorStream(true)
            }

            val process = processBuilder.start()
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                logger.warn("Failed to add http.extraHeader in git config, exit code: $exitCode")
            }
            exitCode == 0
        } catch (e: Exception) {
            logger.warn("Failed to add http.extraHeader in git config", e)
            false
        }
    }
}
