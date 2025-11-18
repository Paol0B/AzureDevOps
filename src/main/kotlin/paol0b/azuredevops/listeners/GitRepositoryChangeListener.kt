package paol0b.azuredevops.listeners

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.BranchChangeListener
import paol0b.azuredevops.services.AzureDevOpsRepositoryDetector

/**
 * Listener per invalidare la cache del rilevamento quando cambia il repository Git
 */
class GitRepositoryChangeListener(private val project: Project) : BranchChangeListener {
    
    override fun branchWillChange(branchName: String) {
        // Non necessario
    }
    
    override fun branchHasChanged(branchName: String) {
        // Invalida la cache quando cambia il branch
        // (potrebbe essere un clone diverso o un cambio di remote)
        val detector = AzureDevOpsRepositoryDetector.getInstance(project)
        detector.invalidateCache()
    }
}
