package paol0b.azuredevops.services

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Project service that triggers a periodic refresh of the Pull Requests list.
 * The polling interval is set to 5 seconds.
 */
@Service(Service.Level.PROJECT)
class PullRequestsPollingService(private val project: Project) {

    private val logger = Logger.getInstance(PullRequestsPollingService::class.java)
    private var scheduler: ScheduledExecutorService? = null
    private var isPolling = false

    companion object {
        private const val POLLING_INTERVAL_SECONDS = 5L

        fun getInstance(project: Project): PullRequestsPollingService {
            return project.getService(PullRequestsPollingService::class.java)
        }
    }

    /**
     * Start polling and execute the provided refresh action every interval.
     * The action is invoked on the EDT via ApplicationManager.invokeLater.
     */
    fun startPolling(refreshAction: () -> Unit) {
        if (isPolling) {
            logger.info("Pull requests polling already active")
            return
        }

        logger.info("Starting pull requests polling with ${POLLING_INTERVAL_SECONDS}s interval")
        isPolling = true

        scheduler = ScheduledThreadPoolExecutor(1).apply {
            scheduleAtFixedRate({
                try {
                    ApplicationManager.getApplication().invokeLater {
                        try {
                            refreshAction()
                        } catch (e: Exception) {
                            logger.warn("Error while executing pull requests refresh action", e)
                        }
                    }
                } catch (e: Exception) {
                    logger.warn("Error scheduling pull requests refresh", e)
                }
            }, POLLING_INTERVAL_SECONDS, POLLING_INTERVAL_SECONDS, TimeUnit.SECONDS)
        }
    }

    fun stopPolling() {
        if (!isPolling) return
        logger.info("Stopping pull requests polling")
        isPolling = false
        scheduler?.shutdown()
        scheduler = null
    }

    fun isPolling(): Boolean = isPolling
}
