package paol0b.azuredevops.checkout

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.CheckoutProvider
import git4idea.GitVcs
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import paol0b.azuredevops.AzureDevOpsIcons
import paol0b.azuredevops.services.GitCredentialHelperService
import java.io.File
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import javax.swing.Icon

/**
 * Checkout provider for Azure DevOps repositories.
 * Appears in the "Clone Repository" dialog alongside GitHub, GitLab, etc.
 * 
 * This provider integrates Azure DevOps into the standard IDE checkout workflow,
 * allowing users to browse and clone repositories directly from Azure DevOps organizations.
 */
@Suppress("OVERRIDE_DEPRECATION")
class AzureDevOpsCheckoutProvider : CheckoutProvider {

    private val logger = Logger.getInstance(AzureDevOpsCheckoutProvider::class.java)

    override fun getVcsName(): String = "Azure DevOps"
    
    /**
     * Provide Azure DevOps icon for the checkout provider card
     */
    fun getIcon(): Icon = AzureDevOpsIcons.Logo

    override fun doCheckout(project: Project, listener: CheckoutProvider.Listener?) {
        val dialog = AzureDevOpsCloneDialog.create(project) ?: return
        
        if (dialog.showAndGet()) {
            val selectedRepo = dialog.getSelectedRepository() ?: return
            val targetDirectory = dialog.getTargetDirectory()

            // Normalize URL: decode if already encoded, then properly construct
            val cloneUrl = normalizeAzureDevOpsUrl(selectedRepo.remoteUrl)
            
            val account = dialog.getSelectedAccount()
            val token = account?.let { 
                AzureDevOpsAccountManager.getInstance().getToken(it.id) 
            }
            
            // Perform the clone operation using Git
            ProgressManager.getInstance().run(object : Task.Backgroundable(
                project, 
                "Cloning ${selectedRepo.name} from Azure DevOps...", 
                true
            ) {
                override fun run(indicator: ProgressIndicator) {
                    try {
                        indicator.text = "Cloning repository from Azure DevOps..."
                        indicator.text2 = cloneUrl
                        indicator.isIndeterminate = false
                        indicator.fraction = 0.0
                        
                        val checkoutDir = File(targetDirectory)
                        checkoutDir.mkdirs()
                        
                        // Ensure parent directory exists, but NOT the target directory itself
                        // Git clone will fail if the target directory already exists
                        val parentCreated = checkoutDir.parentFile?.mkdirs() ?: false
                        logger.info("Parent dir created/exists: $parentCreated, Parent exists: ${checkoutDir.parentFile?.exists()}")
                        
                        // Get token
                        val token = account?.let { 
                            AzureDevOpsAccountManager.getInstance().getToken(it.id) 
                        }
                        
                        // Execute git clone
                        val handler = GitLineHandler(project, checkoutDir.parentFile, GitCommand.CLONE)
                        
                        // Use http.extraHeader to pass credentials securely and reliably
                        // This works for both PAT and OAuth tokens (using token as password)
                        if (token != null) {
                            logger.info("Configuring git http.extraHeader with token (length: ${token.length})")
                            val authHeader = "Authorization: Basic " + java.util.Base64.getEncoder().encodeToString(":$token".toByteArray(StandardCharsets.UTF_8))
                            handler.addParameters("-c", "http.extraHeader=$authHeader")
                        }
                        
                        // Git clone syntax: git clone [options] <repository> [<directory>]
                        handler.addParameters("--progress")
                        handler.addParameters(cloneUrl) // Use the normalized (encoded) URL
                        handler.addParameters(checkoutDir.name)
                        
                        // Add progress listener
                        handler.addLineListener { line, _ ->
                            indicator.text2 = line
                            // Try to extract progress percentage
                            val progressMatch = Regex("""(\d+)%""").find(line)
                            if (progressMatch != null) {
                                val progress = progressMatch.groupValues[1].toIntOrNull()
                                if (progress != null) {
                                    indicator.fraction = progress / 100.0
                                }
                            }
                        }
                        
                        indicator.fraction = 0.1
                        val result = Git.getInstance().runCommand(handler)
                        indicator.fraction = 1.0

                        if (result.success()) {
                            // Save credentials to git credential helper for future use
                            if (token != null) {
                                saveCredentialsToGit(checkoutDir, cloneUrl, token)
                            }
                            
                            listener?.directoryCheckedOut(checkoutDir, GitVcs.getKey())
                            listener?.checkoutCompleted()
                            
                            NotificationGroupManager.getInstance()
                                .getNotificationGroup("AzureDevOps.Notifications")
                                .createNotification(
                                    "Azure DevOps Clone",
                                    "Repository '${selectedRepo.name}' cloned successfully to ${checkoutDir.absolutePath}",
                                    NotificationType.INFORMATION
                                )
                                .notify(project)
                        } else {
                            val errorMessage = result.errorOutputAsJoinedString
                            NotificationGroupManager.getInstance()
                                .getNotificationGroup("AzureDevOps.Notifications")
                                .createNotification(
                                    "Azure DevOps Clone Error",
                                    "Failed to clone repository: $errorMessage",
                                    NotificationType.ERROR
                                )
                                .notify(project)
                        }
                    } catch (e: Exception) {
                        NotificationGroupManager.getInstance()
                            .getNotificationGroup("AzureDevOps.Notifications")
                            .createNotification(
                                "Azure DevOps Clone Error",
                                "Failed to clone repository: ${e.message}",
                                NotificationType.ERROR
                            )
                            .notify(project)
                    }
                }
            })
        }
    }
    
    /**
     * Save credentials to git credential helper for seamless future operations
     */
    private fun saveCredentialsToGit(repoDir: File, url: String, token: String) {
        try {
            val processBuilder = ProcessBuilder(
                "git", "credential", "approve"
            )
            processBuilder.directory(repoDir)
            processBuilder.redirectErrorStream(true)
            
            val process = processBuilder.start()
            
            // Send credentials in git credential format
            process.outputStream.bufferedWriter().use { writer ->
                val uri = java.net.URI(url)
                writer.write("protocol=${uri.scheme}\n")
                writer.write("host=${uri.host}\n")
                // Include path for proper credential matching with special characters
                val path = uri.path?.removePrefix("/")?.removeSuffix(".git") ?: ""
                if (path.isNotBlank()) {
                    writer.write("path=$path\n")
                }
                // Use a valid username placeholder for Azure DevOps
                writer.write("username=oauth\n")
                writer.write("password=$token\n")
                writer.write("\n")
                writer.flush()
            }
            
            process.waitFor()
        } catch (e: Exception) {
            // Ignore errors - credential saving is not critical
        }
    }

    /**
     * Normalize Azure DevOps URL to handle all special characters, spaces, etc.
     * Properly decodes and reconstructs the URL to ensure Git can handle it.
     */
    private fun normalizeAzureDevOpsUrl(url: String): String {
        return try {
            // First, decode the URL completely (handle multiple encodings)
            var decodedUrl = url
            var previousUrl: String
            
            // Decode multiple times until no more changes (handle double-encoding)
            do {
                previousUrl = decodedUrl
                decodedUrl = URLDecoder.decode(previousUrl, StandardCharsets.UTF_8)
            } while (decodedUrl != previousUrl)
            
            // Parse the URL
            val uri = URI(decodedUrl)
            val scheme = uri.scheme ?: "https"
            val host = uri.host ?: return url // Fallback if can't parse
            
            // Get path and properly encode it
            val path = uri.path ?: return url
            
            // Split path and encode each segment properly
            val segments = path.split("/").filter { it.isNotEmpty() }
            val encodedPath = segments.joinToString("/") { segment ->
                java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8)
                    .replace("+", "%20") // Space should be %20, not +
            }
            
            // Reconstruct the URL
            "$scheme://$host/$encodedPath"
        } catch (e: Exception) {
            // If anything fails, return original URL
            url
        }
    }
}

