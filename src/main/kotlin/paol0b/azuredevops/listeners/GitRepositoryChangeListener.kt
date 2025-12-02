package paol0b.azuredevops.listeners

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.BranchChangeListener
import paol0b.azuredevops.services.AzureDevOpsRepositoryDetector

/**
 * Listener to invalidate the detection cache when the Git repository changes
 */
class GitRepositoryChangeListener(private val project: Project) : BranchChangeListener {
    
    override fun branchWillChange(branchName: String) {
        // Not needed
    }
    
    override fun branchHasChanged(branchName: String) {
        // Invalidate the cache when the branch changes
        // (could be a different clone or a remote change)
        val detector = AzureDevOpsRepositoryDetector.getInstance(project)
        detector.invalidateCache()
    }
}
