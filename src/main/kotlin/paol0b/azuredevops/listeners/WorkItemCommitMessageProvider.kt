package paol0b.azuredevops.listeners

import com.intellij.openapi.vcs.CheckinProjectPanel
import com.intellij.openapi.vcs.changes.CommitContext
import com.intellij.openapi.vcs.checkin.CheckinHandler
import com.intellij.openapi.vcs.checkin.CheckinHandlerFactory
import paol0b.azuredevops.services.WorkItemBranchDetector

/**
 * Automatically prepends work item ID (e.g., "#1234") to commit messages
 * when the current branch matches a work item naming pattern.
 */
class WorkItemCommitMessageProvider : CheckinHandlerFactory() {

    override fun createHandler(panel: CheckinProjectPanel, commitContext: CommitContext): CheckinHandler {
        return object : CheckinHandler() {
            override fun beforeCheckin(): ReturnResult {
                val project = panel.project
                val detector = WorkItemBranchDetector.getInstance(project)
                val prefix = detector.getCommitMessagePrefix() ?: return ReturnResult.COMMIT

                val currentMessage = panel.commitMessage.trim()
                if (currentMessage.isEmpty() || !currentMessage.startsWith(prefix)) {
                    panel.commitMessage = if (currentMessage.isEmpty()) {
                        "$prefix "
                    } else {
                        "$prefix $currentMessage"
                    }
                }

                return ReturnResult.COMMIT
            }
        }
    }
}
