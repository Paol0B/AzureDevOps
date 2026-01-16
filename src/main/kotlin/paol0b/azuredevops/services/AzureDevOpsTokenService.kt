package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import paol0b.azuredevops.checkout.AzureDevOpsAccount
import paol0b.azuredevops.checkout.AzureDevOpsAccountManager
import paol0b.azuredevops.checkout.AzureDevOpsOAuthService
import java.util.concurrent.ConcurrentHashMap

/**
 * Centralized token lifecycle service for Azure DevOps OAuth tokens.
 * Handles refresh, concurrency, and Git credential helper synchronization.
 */
@Service(Service.Level.APP)
class AzureDevOpsTokenService {

    private val logger = Logger.getInstance(AzureDevOpsTokenService::class.java)
    private val refreshLocks = ConcurrentHashMap<String, Any>()

    companion object {
        fun getInstance(): AzureDevOpsTokenService {
            return com.intellij.openapi.application.ApplicationManager.getApplication()
                .getService(AzureDevOpsTokenService::class.java)
        }
    }

    /**
     * Returns a valid access token for the given account, refreshing if necessary.
     * If refresh succeeds, also updates Git Credential Helper for matching projects.
     */
    fun getValidAccessToken(account: AzureDevOpsAccount, project: Project? = null): String? {
        val accountManager = AzureDevOpsAccountManager.getInstance()
        return when (accountManager.getAccountAuthState(account.id)) {
            AzureDevOpsAccountManager.AccountAuthState.VALID -> accountManager.getToken(account.id)
            AzureDevOpsAccountManager.AccountAuthState.EXPIRED -> refreshAccessToken(account, project)
            AzureDevOpsAccountManager.AccountAuthState.REVOKED -> null
            AzureDevOpsAccountManager.AccountAuthState.UNKNOWN -> accountManager.getToken(account.id)
        }
    }

    /**
     * Forces a refresh of the access token (if possible) and updates Git credentials.
     */
    fun refreshAccessToken(account: AzureDevOpsAccount, project: Project? = null): String? {
        val lock = refreshLocks.computeIfAbsent(account.id) { Any() }
        synchronized(lock) {
            val accountManager = AzureDevOpsAccountManager.getInstance()

            // Double-check in case another thread refreshed while we were waiting.
            val state = accountManager.getAccountAuthState(account.id)
            if (state == AzureDevOpsAccountManager.AccountAuthState.VALID) {
                return accountManager.getToken(account.id)
            }

            val refreshToken = accountManager.getRefreshToken(account.id)
            if (refreshToken.isNullOrBlank()) {
                logger.warn("No refresh token available for account ${account.id}")
                return null
            }

            val oauthService = AzureDevOpsOAuthService.getInstance()
            val result = oauthService.refreshAccessToken(refreshToken, account.serverUrl)
                ?: return null

            accountManager.updateToken(
                account.id,
                result.accessToken,
                result.refreshToken,
                result.expiresIn
            )

            updateGitCredentials(account, result.accessToken, project)

            return result.accessToken
        }
    }

    private fun updateGitCredentials(account: AzureDevOpsAccount, token: String, project: Project?) {
        val projects = if (project != null) {
            arrayOf(project)
        } else {
            ProjectManager.getInstance().openProjects
        }

        projects.forEach { openProject ->
            if (openProject.isDisposed) return@forEach

            val gitService = GitRepositoryService.getInstance(openProject)
            val remoteUrl = gitService.getRemoteUrl() ?: return@forEach

            val gitHelper = GitCredentialHelperService.getInstance(openProject)
            if (!AzureDevOpsUrlUtil.isSameOrganization(remoteUrl, account.serverUrl)) {
                return@forEach
            }

            gitHelper.upsertAuthorizationHeader(token)

            val existing = gitHelper.getCredentialsFromHelper(remoteUrl)
            if (existing != token) {
                gitHelper.saveCredentialsToHelper(remoteUrl, "oauth", token)
            }
        }
    }
}