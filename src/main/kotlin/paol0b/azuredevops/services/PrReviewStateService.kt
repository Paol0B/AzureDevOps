package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil
import paol0b.azuredevops.model.PullRequest

/**
 * Service to manage PR Review workspace state
 * Tracks reviewed files, current PR selection, and review progress
 */
@Service(Service.Level.PROJECT)
@State(
    name = "PrReviewStateService",
    storages = [Storage("azuredevops-pr-review.xml")]
)
class PrReviewStateService(private val project: Project) : PersistentStateComponent<PrReviewStateService.State> {

    private val logger = Logger.getInstance(PrReviewStateService::class.java)
    private var myState = State()

    // Listeners for state changes
    private val stateChangeListeners = mutableListOf<StateChangeListener>()

    companion object {
        fun getInstance(project: Project): PrReviewStateService {
            return project.getService(PrReviewStateService::class.java)
        }
    }

    data class State(
        // Map of PR ID to list of reviewed file paths
        var reviewedFiles: MutableMap<Int, MutableSet<String>> = mutableMapOf(),
        
        // Current selected PR for review
        var currentPullRequestId: Int? = null,
        
        // Map of PR ID to vote status (for caching)
        var prVotes: MutableMap<Int, Int> = mutableMapOf()
    )

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    /**
     * Mark a file as reviewed in a PR
     */
    fun markFileAsReviewed(pullRequestId: Int, filePath: String) {
        val reviewedSet = myState.reviewedFiles.getOrPut(pullRequestId) { mutableSetOf() }
        reviewedSet.add(filePath)
        notifyStateChanged()
        logger.info("Marked file as reviewed: PR #$pullRequestId - $filePath")
    }

    /**
     * Unmark a file as reviewed
     */
    fun unmarkFileAsReviewed(pullRequestId: Int, filePath: String) {
        myState.reviewedFiles[pullRequestId]?.remove(filePath)
        notifyStateChanged()
        logger.info("Unmarked file as reviewed: PR #$pullRequestId - $filePath")
    }

    /**
     * Check if a file is marked as reviewed
     */
    fun isFileReviewed(pullRequestId: Int, filePath: String): Boolean {
        return myState.reviewedFiles[pullRequestId]?.contains(filePath) ?: false
    }

    /**
     * Get all reviewed files for a PR
     */
    fun getReviewedFiles(pullRequestId: Int): Set<String> {
        return myState.reviewedFiles[pullRequestId]?.toSet() ?: emptySet()
    }

    /**
     * Clear all reviewed files for a PR
     */
    fun clearReviewedFiles(pullRequestId: Int) {
        myState.reviewedFiles.remove(pullRequestId)
        notifyStateChanged()
        logger.info("Cleared all reviewed files for PR #$pullRequestId")
    }

    /**
     * Set the current PR being reviewed
     */
    fun setCurrentPullRequest(pullRequestId: Int?) {
        myState.currentPullRequestId = pullRequestId
        notifyStateChanged()
        logger.info("Set current PR to: #$pullRequestId")
    }

    /**
     * Get the current PR being reviewed
     */
    fun getCurrentPullRequest(): Int? = myState.currentPullRequestId

    /**
     * Get review progress (percentage of files reviewed)
     */
    fun getReviewProgress(pullRequestId: Int, totalFiles: Int): Int {
        if (totalFiles == 0) return 100
        val reviewedCount = myState.reviewedFiles[pullRequestId]?.size ?: 0
        return (reviewedCount * 100) / totalFiles
    }

    /**
     * Save vote status for a PR
     */
    fun savePrVote(pullRequestId: Int, vote: Int) {
        myState.prVotes[pullRequestId] = vote
        notifyStateChanged()
    }

    /**
     * Get cached vote for a PR
     */
    fun getPrVote(pullRequestId: Int): Int? = myState.prVotes[pullRequestId]

    /**
     * Add a state change listener
     */
    fun addStateChangeListener(listener: StateChangeListener) {
        stateChangeListeners.add(listener)
    }

    /**
     * Remove a state change listener
     */
    fun removeStateChangeListener(listener: StateChangeListener) {
        stateChangeListeners.remove(listener)
    }

    private fun notifyStateChanged() {
        stateChangeListeners.forEach { it.onStateChanged() }
    }

    /**
     * Interface for listening to state changes
     */
    interface StateChangeListener {
        fun onStateChanged()
    }
}
