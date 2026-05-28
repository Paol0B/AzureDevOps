package paol0b.azuredevops.checkout

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fetches repositories for a list of projects using a bounded number of concurrent HTTP calls.
 *
 * Callers receive each project's repos through [onLoaded] as soon as that call returns,
 * which lets the clone dialog tree fill in incrementally instead of waiting for the whole
 * org. [onAllDone] fires once every project has reported back (success or failure).
 *
 * Tasks dispatched into IntelliJ's pooled thread executor; concurrency is gated by a
 * semaphore so we never have more than [concurrency] requests in flight at once.
 */
internal class RepositoryLoader(
    private val apiClient: AzureDevOpsCloneApiClient,
    concurrency: Int = DEFAULT_CONCURRENCY
) {
    private val logger = Logger.getInstance(RepositoryLoader::class.java)
    private val semaphore = Semaphore(concurrency)

    fun load(
        projectIds: List<String>,
        onLoaded: (projectId: String, repos: List<AzureDevOpsCloneApiClient.Repository>) -> Unit,
        onFailed: (projectId: String, error: Throwable) -> Unit,
        onAllDone: () -> Unit
    ) {
        if (projectIds.isEmpty()) {
            onAllDone()
            return
        }

        val remaining = AtomicInteger(projectIds.size)
        projectIds.forEach { projectId ->
            ApplicationManager.getApplication().executeOnPooledThread {
                semaphore.acquire()
                try {
                    val repos = apiClient.getRepositoriesForProject(projectId)
                    onLoaded(projectId, repos)
                } catch (e: Throwable) {
                    logger.warn("Failed to load repositories for project $projectId", e)
                    onFailed(projectId, e)
                } finally {
                    semaphore.release()
                    if (remaining.decrementAndGet() == 0) {
                        onAllDone()
                    }
                }
            }
        }
    }

    companion object {
        const val DEFAULT_CONCURRENCY = 8
    }
}
