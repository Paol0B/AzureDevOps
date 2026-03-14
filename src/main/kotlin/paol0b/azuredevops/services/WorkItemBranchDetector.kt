package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Detects work item IDs from the current branch name.
 * Used by the commit message provider to auto-fill #{id} in commit messages.
 */
@Service(Service.Level.PROJECT)
class WorkItemBranchDetector(private val project: Project) {

    companion object {
        private val WORK_ITEM_PATTERN = Regex("(?:feature|bugfix|task|work)/(\\d+)")

        fun getInstance(project: Project): WorkItemBranchDetector {
            return project.getService(WorkItemBranchDetector::class.java)
        }
    }

    @Volatile
    var currentWorkItemId: Int? = null
        private set

    /**
     * Detect a work item ID from the given branch name.
     * Looks for patterns like feature/1234-title, bugfix/1234-title, etc.
     */
    fun detectFromBranch(branchName: String) {
        currentWorkItemId = WORK_ITEM_PATTERN.find(branchName)
            ?.groupValues?.get(1)?.toIntOrNull()
    }

    /**
     * Returns a commit message prefix like "#1234" if a work item ID is detected.
     */
    fun getCommitMessagePrefix(): String? {
        return currentWorkItemId?.let { "#$it" }
    }
}
