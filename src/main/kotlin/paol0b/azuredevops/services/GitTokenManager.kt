package paol0b.azuredevops.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.net.URI
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * Application-level service that tracks all Git repositories cloned (or opened) via the plugin
 * and keeps their `http.extraHeader` git-config entry up-to-date with the latest OAuth / PAT token.
 *
 * Why http.extraHeader instead of the OS credential store?
 * ─────────────────────────────────────────────────────────
 * OAuth tokens expire (typically every 60-90 minutes). Updating them in the OS credential store
 * (`git credential approve`) is unreliable: the system keychain may serve a stale cached entry
 * instead of the freshly stored one.  Writing `http.extraHeader = Authorization: Basic …`
 * directly into the local `.git/config` guarantees that every subsequent git operation in that
 * repository uses the current token.  Whenever the token is refreshed this service is called and
 * the config entry is overwritten in-place.
 *
 * Works for both OAuth and PAT (both use `Basic BASE64(:token)` with an empty username, which is
 * the format the Azure DevOps git HTTPS endpoint expects).
 */
@Service(Service.Level.APP)
@State(
    name = "AzureDevOpsGitTokenManager",
    storages = [Storage("azureDevOpsGitTokenManager.xml")]
)
class GitTokenManager : PersistentStateComponent<GitTokenManager.State> {

    private val logger = Logger.getInstance(GitTokenManager::class.java)

    /** Persisted state: repoAbsolutePath → accountId */
    data class State(
        var managedRepos: MutableMap<String, String> = mutableMapOf()
    )

    private var myState = State()

    companion object {
        fun getInstance(): GitTokenManager =
            ApplicationManager.getApplication().getService(GitTokenManager::class.java)
    }

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    // ─── Registration ────────────────────────────────────────────────────────

    /** Associates a local repository directory with the plugin account that manages it. */
    fun registerRepo(repoPath: String, accountId: String) {
        myState.managedRepos[repoPath] = accountId
        logger.info("GitTokenManager: registered repo '$repoPath' → account '$accountId'")
    }

    /** Removes all repo registrations for the given account (e.g. when the account is deleted). */
    fun unregisterAllForAccount(accountId: String) {
        val removed = myState.managedRepos.entries.filter { it.value == accountId }.map { it.key }
        removed.forEach { myState.managedRepos.remove(it) }
        if (removed.isNotEmpty()) {
            logger.info("GitTokenManager: unregistered ${removed.size} repo(s) for account '$accountId'")
        }
    }

    // ─── Token updates ───────────────────────────────────────────────────────

    /**
     * Updates the `http.extraHeader` git-config entry in every repository that is associated
     * with [accountId].  Called automatically from [AzureDevOpsAccountManager.updateToken].
     */
    fun updateAllReposForAccount(accountId: String, token: String) {
        val repos = myState.managedRepos.filter { it.value == accountId }
        if (repos.isEmpty()) {
            logger.debug("GitTokenManager: no registered repos for account '$accountId'")
            return
        }
        logger.info("GitTokenManager: refreshing auth header in ${repos.size} repo(s) for account '$accountId'")
        for ((repoPath, _) in repos) {
            try {
                writeAuthHeader(repoPath, token)
            } catch (e: Exception) {
                logger.warn("GitTokenManager: failed to update auth for '$repoPath'", e)
            }
        }
    }

    /**
     * Writes (or overwrites) the `http.extraHeader` entry in the repository's **local**
     * `.git/config` with a fresh `Authorization: Basic BASE64(:token)` value.
     *
     * Also attempts to erase any stale entry in the OS credential store so that git does not
     * accidentally send an expired token alongside the fresh one.
     *
     * @return `true` on success, `false` if the repository directory is invalid or the
     *         `git config` command fails.
     */
    fun writeAuthHeader(repoPath: String, token: String): Boolean {
        val repoDir = File(repoPath)
        if (!repoDir.exists() || !File(repoDir, ".git").exists()) {
            logger.warn("GitTokenManager: '$repoPath' is not a valid git repository – skipping")
            return false
        }

        val authValue = buildAuthHeaderValue(token)

        return try {
            val result = runGitConfig(
                repoDir,
                "--local", "--replace-all", "http.extraHeader", authValue
            )
            if (result) {
                logger.info("GitTokenManager: wrote http.extraHeader for '$repoPath'")
                // Remove stale credentials from the OS credential store to avoid conflicts
                eraseStaleCredentials(repoDir)
            } else {
                logger.warn("GitTokenManager: git config --replace-all failed for '$repoPath'")
            }
            result
        } catch (e: Exception) {
            logger.warn("GitTokenManager: exception writing auth header for '$repoPath'", e)
            false
        }
    }

    /**
     * Removes the `http.extraHeader` entry from the repository's local `.git/config`.
     * Called when you want git to fall back to the default credential manager (e.g. after
     * switching to a different auth method).
     */
    fun removeAuthHeader(repoPath: String): Boolean {
        val repoDir = File(repoPath)
        if (!repoDir.exists()) return false
        return try {
            // exit code 5 means the key did not exist; treat that as success
            runGitConfig(repoDir, "--local", "--unset-all", "http.extraHeader", allowExitCode5 = true)
        } catch (e: Exception) {
            logger.warn("GitTokenManager: exception removing auth header for '$repoPath'", e)
            false
        }
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private fun buildAuthHeaderValue(token: String): String {
        val encoded = Base64.getEncoder()
            .encodeToString(":$token".toByteArray(StandardCharsets.UTF_8))
        return "Authorization: Basic $encoded"
    }

    private fun runGitConfig(
        repoDir: File,
        vararg args: String,
        allowExitCode5: Boolean = false
    ): Boolean {
        val cmd = mutableListOf("git", "config") + args.toList()
        val process = ProcessBuilder(cmd)
            .directory(repoDir)
            .redirectErrorStream(true)
            .start()

        // Drain stdout so the process does not block on a full pipe
        val output = process.inputStream.bufferedReader().readText()
        val exited = process.waitFor(10, TimeUnit.SECONDS)
        if (!exited) {
            process.destroyForcibly()
            logger.warn("GitTokenManager: git config timed out for ${repoDir.path}")
            return false
        }
        val exitCode = process.exitValue()
        if (exitCode != 0 && !(allowExitCode5 && exitCode == 5)) {
            logger.warn("GitTokenManager: git config exited $exitCode for ${repoDir.path}: $output")
            return false
        }
        return true
    }

    /**
     * Erases the cached credential for the repository's origin URL from the OS credential store.
     * This prevents git from sending the (potentially expired) stored credential alongside the
     * fresh `http.extraHeader` token.
     *
     * Failures here are non-fatal and only logged at DEBUG level.
     */
    private fun eraseStaleCredentials(repoDir: File) {
        try {
            val remoteUrl = getOriginUrl(repoDir) ?: return
            val uri = URI(remoteUrl)
            val inputData = buildString {
                appendLine("protocol=${uri.scheme}")
                appendLine("host=${uri.host}")
                appendLine()
            }

            val process = ProcessBuilder("git", "credential", "erase")
                .directory(repoDir)
                .redirectErrorStream(true)
                .start()

            process.outputStream.use { os ->
                os.write(inputData.toByteArray(StandardCharsets.UTF_8))
                os.flush()
            }
            process.waitFor(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.debug("GitTokenManager: could not erase stale credentials for ${repoDir.path}", e)
        }
    }

    private fun getOriginUrl(repoDir: File): String? {
        return try {
            val process = ProcessBuilder("git", "config", "--local", "--get", "remote.origin.url")
                .directory(repoDir)
                .redirectErrorStream(true)
                .start()
            val url = process.inputStream.bufferedReader().readText().trim()
            process.waitFor(5, TimeUnit.SECONDS)
            url.ifBlank { null }
        } catch (e: Exception) {
            null
        }
    }
}
