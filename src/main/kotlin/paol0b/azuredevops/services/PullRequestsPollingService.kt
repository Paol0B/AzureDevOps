package paol0b.azuredevops.services

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Project service that triggers a periodic refresh of the Pull Requests list.
 * Uses self-rescheduling to prevent overlapping requests and support configurable intervals.
 */
@Service(Service.Level.PROJECT)
class PullRequestsPollingService(private val project: Project) : Disposable {

    private val logger = Logger.getInstance(PullRequestsPollingService::class.java)
    private var scheduler: ScheduledExecutorService? = null
    @Volatile
    private var isPolling = false
    private var refreshAction: (() -> Unit)? = null

    companion object {
        fun getInstance(project: Project): PullRequestsPollingService {
            return project.getService(PullRequestsPollingService::class.java)
        }
    }

    /**
     * Start polling and execute the provided refresh action at the configured interval.
     * Uses self-rescheduling: the next poll is scheduled only after the current one completes,
     * naturally preventing overlapping requests.
     */
    fun startPolling(refreshAction: () -> Unit) {
        if (isPolling) {
            logger.info("Pull requests polling already active")
            return
        }

        this.refreshAction = refreshAction
        val interval = AzureDevOpsSettingsService.getInstance(project).state.pullRequestIntervalSeconds
        logger.info("Starting pull requests polling with ${interval}s interval")
        isPolling = true

        scheduler = ScheduledThreadPoolExecutor(1)
        scheduleNext()
    }

    private fun scheduleNext() {
        if (!isPolling) return
        val interval = AzureDevOpsSettingsService.getInstance(project).state.pullRequestIntervalSeconds
        scheduler?.schedule({
            try {
                ApplicationManager.getApplication().invokeLater {
                    try {
                        refreshAction?.invoke()
                    } catch (e: Exception) {
                        logger.warn("Error while executing pull requests refresh action", e)
                    }
                }
            } catch (e: Exception) {
                logger.warn("Error scheduling pull requests refresh", e)
            } finally {
                scheduleNext()
            }
        }, interval, TimeUnit.SECONDS)
    }

    fun stopPolling() {
        if (!isPolling) return
        logger.info("Stopping pull requests polling")
        isPolling = false
        scheduler?.let {
            it.shutdown()
            try {
                it.awaitTermination(5, TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                it.shutdownNow()
            }
        }
        scheduler = null
    }

    override fun dispose() {
        stopPolling()
    }

    /**
     * Reschedule polling with the current settings.
     * Called when polling interval settings change.
     */
    fun reschedule() {
        if (!isPolling) return
        val action = refreshAction ?: return
        logger.info("Rescheduling pull requests polling with updated interval")
        stopPolling()
        startPolling(action)
    }

    fun isPolling(): Boolean = isPolling
}
