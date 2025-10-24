package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import paol0b.azuredevops.model.CommentThread

/**
 * Servizio per tracciare quali file hanno commenti PR
 * Usato per mostrare badge nell'esplora soluzioni
 */
@Service(Service.Level.PROJECT)
class PullRequestCommentsTracker(private val project: Project) {

    // Mappa: file -> lista di thread di commenti
    private val fileComments = mutableMapOf<VirtualFile, List<CommentThread>>()

    companion object {
        fun getInstance(project: Project): PullRequestCommentsTracker {
            return project.getService(PullRequestCommentsTracker::class.java)
        }
    }

    /**
     * Registra i commenti per un file
     */
    fun setCommentsForFile(file: VirtualFile, threads: List<CommentThread>) {
        fileComments[file] = threads
    }

    /**
     * Rimuove i commenti di un file
     */
    fun clearCommentsForFile(file: VirtualFile) {
        fileComments.remove(file)
    }

    /**
     * Rimuove tutti i commenti
     */
    fun clearAllComments() {
        fileComments.clear()
    }

    /**
     * Verifica se un file ha commenti PR
     */
    fun hasComments(file: VirtualFile): Boolean {
        return fileComments[file]?.isNotEmpty() == true
    }

    /**
     * Verifica se un file ha commenti attivi (non risolti)
     */
    fun hasActiveComments(file: VirtualFile): Boolean {
        return fileComments[file]?.any { !it.isResolved() } == true
    }

    /**
     * Ottiene il numero totale di commenti per un file
     */
    fun getCommentCount(file: VirtualFile): Int {
        return fileComments[file]?.size ?: 0
    }

    /**
     * Ottiene il numero di commenti attivi per un file
     */
    fun getActiveCommentCount(file: VirtualFile): Int {
        return fileComments[file]?.count { !it.isResolved() } ?: 0
    }

    /**
     * Ottiene i commenti per un file
     */
    fun getCommentsForFile(file: VirtualFile): List<CommentThread> {
        return fileComments[file] ?: emptyList()
    }
}
