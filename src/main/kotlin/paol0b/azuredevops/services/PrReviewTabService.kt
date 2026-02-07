package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.model.PullRequestChange
import paol0b.azuredevops.toolwindow.review.editor.PrDiffVirtualFile
import paol0b.azuredevops.toolwindow.review.editor.PrReviewKey
import paol0b.azuredevops.toolwindow.review.editor.PrReviewVirtualFile
import paol0b.azuredevops.toolwindow.review.editor.PrTimelineVirtualFile
import java.util.concurrent.ConcurrentHashMap

@Service(Service.Level.PROJECT)
class PrReviewTabService(private val project: Project) {

    /** PR data stored by VirtualFile (for all virtual file types) */
    private val prByFile = ConcurrentHashMap<VirtualFile, PullRequest>()

    /** Legacy review key → file mapping */
    private val fileByKey = ConcurrentHashMap<PrReviewKey, VirtualFile>()

    /** Diff files keyed by "prId:filePath" */
    private val diffFiles = ConcurrentHashMap<String, PrDiffVirtualFile>()

    /** Timeline files keyed by prId */
    private val timelineFiles = ConcurrentHashMap<Int, PrTimelineVirtualFile>()

    /** Change data stored by diff file */
    private val changeByFile = ConcurrentHashMap<VirtualFile, PullRequestChange>()

    companion object {
        fun getInstance(project: Project): PrReviewTabService {
            return project.getService(PrReviewTabService::class.java)
        }
    }

    // ------------------------------------------------------------------
    // Legacy full-review editor tab
    // ------------------------------------------------------------------

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

    // ------------------------------------------------------------------
    // Individual file diff editor tab
    // ------------------------------------------------------------------

    fun openDiffTab(pullRequest: PullRequest, change: PullRequestChange) {
        val editorManager = FileEditorManager.getInstance(project)
        val filePath = change.item?.path ?: return
        val diffKey = "${pullRequest.pullRequestId}:$filePath"

        val existing = diffFiles[diffKey]
        if (existing != null) {
            editorManager.openFile(existing, true, true)
            return
        }

        val diffFile = PrDiffVirtualFile(pullRequest.pullRequestId, filePath, pullRequest.repository?.id)
        prByFile[diffFile] = pullRequest
        changeByFile[diffFile] = change
        diffFiles[diffKey] = diffFile
        editorManager.openFile(diffFile, true, true)
    }

    // ------------------------------------------------------------------
    // Timeline editor tab
    // ------------------------------------------------------------------

    fun openTimelineTab(pullRequest: PullRequest) {
        val editorManager = FileEditorManager.getInstance(project)

        val existing = timelineFiles[pullRequest.pullRequestId]
        if (existing != null) {
            editorManager.openFile(existing, true, true)
            return
        }

        val timelineFile = PrTimelineVirtualFile(pullRequest.pullRequestId, pullRequest.repository?.id)
        prByFile[timelineFile] = pullRequest
        timelineFiles[pullRequest.pullRequestId] = timelineFile
        editorManager.openFile(timelineFile, true, true)
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    fun getPullRequest(file: VirtualFile): PullRequest? = prByFile[file]

    fun getChange(file: VirtualFile): PullRequestChange? = changeByFile[file]

    fun unregisterFile(file: VirtualFile) {
        val pullRequest = prByFile.remove(file) ?: return
        changeByFile.remove(file)

        when (file) {
            is PrReviewVirtualFile -> {
                val key = PrReviewKey(pullRequest.pullRequestId, pullRequest.repository?.id)
                fileByKey.remove(key)
            }
            is PrDiffVirtualFile -> {
                diffFiles.entries.removeIf { it.value == file }
            }
            is PrTimelineVirtualFile -> {
                timelineFiles.remove(pullRequest.pullRequestId)
            }
        }
    }
}
