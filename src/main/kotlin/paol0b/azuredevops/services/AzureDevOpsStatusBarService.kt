package paol0b.azuredevops.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import paol0b.azuredevops.model.BuildResult
import paol0b.azuredevops.model.BuildStatus
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

@Service(Service.Level.PROJECT)
class AzureDevOpsStatusBarService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(AzureDevOpsStatusBarService::class.java)

    enum class BuildStatusSummary {
        Succeeded, Failed, PartiallySucceeded, InProgress, Unknown
    }

    data class StatusBarData(
        val buildStatus: BuildStatusSummary = BuildStatusSummary.Unknown,
        val activePrCount: Int = 0,
        val activeWorkItemCount: Int = 0
    )

    @Volatile
    private var cachedData: StatusBarData = StatusBarData()
    private var scheduler: ScheduledExecutorService? = null
    private var isRunning = false
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    companion object {
        private const val POLLING_INTERVAL_SECONDS = 30L

        fun getInstance(project: Project): AzureDevOpsStatusBarService {
            return project.getService(AzureDevOpsStatusBarService::class.java)
        }
    }

    fun getData(): StatusBarData = cachedData

    fun addUpdateListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeUpdateListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    fun startPolling() {
        if (isRunning) return
        isRunning = true

        scheduler = ScheduledThreadPoolExecutor(1).apply {
            scheduleAtFixedRate({
                refreshAll()
            }, 0, POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        }
    }

    fun stopPolling() {
        if (!isRunning) return
        isRunning = false
        scheduler?.shutdown()
        scheduler = null
    }

    fun refreshAll() {
        if (!AzureDevOpsConfigService.getInstance(project).isConfigured()) {
            cachedData = StatusBarData()
            notifyListeners()
            return
        }

        try {
            val apiClient = AzureDevOpsApiClient.getInstance(project)

            val buildStatus = fetchBuildStatus(apiClient)
            val prCount = fetchActivePrCount(apiClient)
            val wiCount = fetchActiveWorkItemCount(apiClient)

            cachedData = StatusBarData(
                buildStatus = buildStatus,
                activePrCount = prCount,
                activeWorkItemCount = wiCount
            )
            notifyListeners()
        } catch (e: Exception) {
            logger.warn("Failed to refresh status bar data: ${e.message}")
        }
    }

    fun refreshBuildStatusOnly() {
        if (!AzureDevOpsConfigService.getInstance(project).isConfigured()) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val buildStatus = fetchBuildStatus(apiClient)
                cachedData = cachedData.copy(buildStatus = buildStatus)
                notifyListeners()
            } catch (e: Exception) {
                logger.warn("Failed to refresh build status: ${e.message}")
            }
        }
    }

    override fun dispose() {
        stopPolling()
        listeners.clear()
    }

    private fun fetchBuildStatus(apiClient: AzureDevOpsApiClient): BuildStatusSummary {
        return try {
            val branchName = GitRepositoryService.getInstance(project).getCurrentBranch()?.displayName
                ?: return BuildStatusSummary.Unknown
            val builds = apiClient.getBuilds(branchName = "refs/heads/$branchName", top = 1)
            val build = builds.firstOrNull() ?: return BuildStatusSummary.Unknown

            when {
                build.status == BuildStatus.InProgress -> BuildStatusSummary.InProgress
                build.result == BuildResult.Succeeded -> BuildStatusSummary.Succeeded
                build.result == BuildResult.Failed -> BuildStatusSummary.Failed
                build.result == BuildResult.PartiallySucceeded -> BuildStatusSummary.PartiallySucceeded
                else -> BuildStatusSummary.Unknown
            }
        } catch (e: Exception) {
            logger.warn("Failed to fetch build status: ${e.message}")
            cachedData.buildStatus
        }
    }

    private fun fetchActivePrCount(apiClient: AzureDevOpsApiClient): Int {
        return try {
            apiClient.getPullRequests(status = "active").size
        } catch (e: Exception) {
            logger.warn("Failed to fetch PR count: ${e.message}")
            cachedData.activePrCount
        }
    }

    private fun fetchActiveWorkItemCount(apiClient: AzureDevOpsApiClient): Int {
        return try {
            apiClient.getMyWorkItems().size
        } catch (e: Exception) {
            logger.warn("Failed to fetch work item count: ${e.message}")
            cachedData.activeWorkItemCount
        }
    }

    private fun notifyListeners() {
        ApplicationManager.getApplication().invokeLater {
            listeners.forEach { it() }
        }
    }
}
