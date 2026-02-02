package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.toolwindow.review.editor.PrReviewKey
import paol0b.azuredevops.toolwindow.review.editor.PrReviewVirtualFile
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class PrReviewTabService(private val project: Project) {

    private val prByFile = ConcurrentHashMap<VirtualFile, PullRequest>()
    private val fileByKey = ConcurrentHashMap<PrReviewKey, VirtualFile>()

    companion object {
        fun getInstance(project: Project): PrReviewTabService {
            return project.getService(PrReviewTabService::class.java)
        }
    }

    fun openReviewTab(pullRequest: PullRequest) {
        val editorManager = FileEditorManager.getInstance(project)
        val key = PrReviewKey(pullRequest.pullRequestId, pullRequest.repository?.id)

        val existing = fileByKey[key]
        if (existing != null) {
            editorManager.openFile(existing, true, true)
            return
        }

        val file = PrReviewVirtualFile(key)
        prByFile[file] = pullRequest
        fileByKey[key] = file
        editorManager.openFile(file, true, true)
    }

    fun getPullRequest(file: VirtualFile): PullRequest? {
        return prByFile[file]
    }

    fun unregisterFile(file: VirtualFile) {
        val pullRequest = prByFile.remove(file) ?: return
        val key = PrReviewKey(pullRequest.pullRequestId, pullRequest.repository?.id)
        fileByKey.remove(key)
    }
}
