package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import git4idea.GitLocalBranch
import git4idea.GitRemoteBranch
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryManager
import paol0b.azuredevops.model.GitBranch

/**
 * Servizio per interagire con il repository Git locale
 */
@Service(Service.Level.PROJECT)
class GitRepositoryService(private val project: Project) {

    private val logger = Logger.getInstance(GitRepositoryService::class.java)

    companion object {
        fun getInstance(project: Project): GitRepositoryService {
            return project.getService(GitRepositoryService::class.java)
        }
    }

    /**
     * Ottiene il repository Git corrente del progetto
     */
    fun getCurrentRepository(): GitRepository? {
        val manager = GitRepositoryManager.getInstance(project)
        val repositories = manager.repositories
        
        if (repositories.isEmpty()) {
            logger.warn("No Git repositories found in project")
            return null
        }
        
        // Restituisce il primo repository (in progetti multi-repo, potrebbe essere esteso)
        return repositories.firstOrNull()
    }

    /**
     * Ottiene il branch corrente
     */
    fun getCurrentBranch(): GitBranch? {
        val repository = getCurrentRepository() ?: return null
        val currentBranch = repository.currentBranch ?: return null
        
        return GitBranch(
            name = "refs/heads/${currentBranch.name}",
            displayName = currentBranch.name
        )
    }

    /**
     * Ottiene tutti i branch locali
     */
    fun getLocalBranches(): List<GitBranch> {
        val repository = getCurrentRepository() ?: return emptyList()
        
        return repository.branches.localBranches.map { branch ->
            GitBranch(
                name = "refs/heads/${branch.name}",
                displayName = branch.name
            )
        }
    }

    /**
     * Ottiene tutti i branch remoti
     */
    fun getRemoteBranches(): List<GitBranch> {
        val repository = getCurrentRepository() ?: return emptyList()
        
        return repository.branches.remoteBranches.map { branch ->
            GitBranch(
                name = "refs/heads/${branch.nameForRemoteOperations}",
                displayName = branch.nameForRemoteOperations
            )
        }
    }

    /**
     * Determina il branch target (main o master) per la Pull Request
     * Priorità: main > master > null
     */
    fun getDefaultTargetBranch(): GitBranch? {
        val repository = getCurrentRepository() ?: return null
        
        // Cerca prima nei branch remoti
        val remoteBranches = repository.branches.remoteBranches
        val mainBranch = remoteBranches.firstOrNull { it.nameForRemoteOperations == "main" }
        if (mainBranch != null) {
            return GitBranch("refs/heads/main", "main")
        }
        
        val masterBranch = remoteBranches.firstOrNull { it.nameForRemoteOperations == "master" }
        if (masterBranch != null) {
            return GitBranch("refs/heads/master", "master")
        }
        
        // Cerca nei branch locali come fallback
        val localBranches = repository.branches.localBranches
        val localMain = localBranches.firstOrNull { it.name == "main" }
        if (localMain != null) {
            return GitBranch("refs/heads/main", "main")
        }
        
        val localMaster = localBranches.firstOrNull { it.name == "master" }
        if (localMaster != null) {
            return GitBranch("refs/heads/master", "master")
        }
        
        logger.warn("No default branch (main/master) found")
        return null
    }

    /**
     * Verifica se il progetto ha un repository Git
     */
    fun hasGitRepository(): Boolean {
        return getCurrentRepository() != null
    }

    /**
     * Ottiene il nome del repository Git (dall'URL remoto se disponibile)
     */
    fun getRepositoryName(): String? {
        val repository = getCurrentRepository() ?: return null
        
        // Prova a ottenere il nome dal remote
        val remotes = repository.remotes
        if (remotes.isNotEmpty()) {
            val firstRemoteUrl = remotes.first().firstUrl ?: return null
            // Estrai il nome del repository dall'URL
            // Es: https://dev.azure.com/org/project/_git/repo -> repo
            // Es: git@ssh.dev.azure.com:v3/org/project/repo -> repo
            return extractRepositoryNameFromUrl(firstRemoteUrl)
        }
        
        return null
    }

    /**
     * Estrae il nome del repository da un URL Git
     */
    private fun extractRepositoryNameFromUrl(url: String): String {
        // Rimuovi .git alla fine se presente
        val cleanUrl = url.removeSuffix(".git")
        
        // Prendi l'ultimo segmento del path
        val segments = cleanUrl.split('/', '\\')
        return segments.lastOrNull()?.takeIf { it.isNotBlank() } ?: cleanUrl
    }

    /**
     * Verifica se il branch corrente è main o master
     */
    fun isOnMainBranch(): Boolean {
        val currentBranch = getCurrentBranch() ?: return false
        return currentBranch.displayName == "main" || currentBranch.displayName == "master"
    }

    /**
     * Ottiene tutti i branch disponibili (locali e remoti combinati, senza duplicati)
     */
    fun getAllBranches(): List<GitBranch> {
        val repository = getCurrentRepository() ?: return emptyList()
        
        val branchNames = mutableSetOf<String>()
        val branches = mutableListOf<GitBranch>()
        
        // Aggiungi branch locali
        repository.branches.localBranches.forEach { branch ->
            if (branchNames.add(branch.name)) {
                branches.add(GitBranch("refs/heads/${branch.name}", branch.name))
            }
        }
        
        // Aggiungi branch remoti non già presenti
        repository.branches.remoteBranches.forEach { branch ->
            val name = branch.nameForRemoteOperations
            if (branchNames.add(name)) {
                branches.add(GitBranch("refs/heads/$name", name))
            }
        }
        
        return branches.sortedBy { it.displayName }
    }
    
    /**
     * Ottiene l'URL del remote Git (origin)
     */
    fun getRemoteUrl(): String? {
        val repository = getCurrentRepository() ?: return null
        
        // Cerca il remote "origin"
        val originRemote = repository.remotes.firstOrNull { it.name == "origin" }
        if (originRemote != null) {
            return originRemote.firstUrl
        }
        
        // Se non c'è origin, restituisci il primo remote disponibile
        val firstRemote = repository.remotes.firstOrNull()
        return firstRemote?.firstUrl
    }
}
