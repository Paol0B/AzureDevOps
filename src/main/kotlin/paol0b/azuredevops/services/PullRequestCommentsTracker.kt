package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import paol0b.azuredevops.model.CommentThread

/**
 * Service to track which files have PR comments
 * Used to show badges in the solution explorer
 */
@Service(Service.Level.PROJECT)
class PullRequestCommentsTracker(private val project: Project) {

    // Map: file -> list of comment threads
    private val fileComments = mutableMapOf<VirtualFile, List<CommentThread>>()

    companion object {
        fun getInstance(project: Project): PullRequestCommentsTracker {
            return project.getService(PullRequestCommentsTracker::class.java)
        }
    }

    /**
     * Registers comments for a file
     */
    fun setCommentsForFile(file: VirtualFile, threads: List<CommentThread>) {
        fileComments[file] = threads
    }

    /**
     * Removes comments for a file
     */
    fun clearCommentsForFile(file: VirtualFile) {
        fileComments.remove(file)
    }

    /**
     * Removes all comments
     */
    fun clearAllComments() {
        fileComments.clear()
    }

    /**
     * Checks if a file has PR comments
     */
    fun hasComments(file: VirtualFile): Boolean {
        return fileComments[file]?.isNotEmpty() == true
    }

    /**
     * Checks if a file has active (unresolved) comments
     */
    fun hasActiveComments(file: VirtualFile): Boolean {
        return fileComments[file]?.any { !it.isResolved() } == true
    }

    /**
     * Gets the total number of comments for a file
     */
    fun getCommentCount(file: VirtualFile): Int {
        return fileComments[file]?.size ?: 0
    }

    /**
     * Gets the number of active comments for a file
     */
    fun getActiveCommentCount(file: VirtualFile): Int {
        return fileComments[file]?.count { !it.isResolved() } ?: 0
    }

    /**
     * Gets the comments for a file
     */
    fun getCommentsForFile(file: VirtualFile): List<CommentThread> {
        return fileComments[file] ?: emptyList()
    }
}
