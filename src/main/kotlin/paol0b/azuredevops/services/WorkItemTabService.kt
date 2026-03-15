package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import paol0b.azuredevops.toolwindow.workitem.board.WorkItemBoardVirtualFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Service that manages virtual file ↔ work item data mappings,
 * mirroring the pattern of [PipelineTabService].
 */
@Service(Service.Level.PROJECT)
class WorkItemTabService(private val project: Project) {

    /** Board virtual files, keyed by iteration path (or "all"). */
    private val boardFiles = ConcurrentHashMap<String, WorkItemBoardVirtualFile>()

    companion object {
        fun getInstance(project: Project): WorkItemTabService {
            return project.getService(WorkItemTabService::class.java)
        }
    }

    /**
     * Open (or focus) the Kanban board as an editor tab.
     */
    fun openBoardTab(iterationPath: String?, iterationName: String) {
        val editorManager = FileEditorManager.getInstance(project)
        val key = iterationPath ?: "all"

        val existing = boardFiles[key]
        if (existing != null) {
            editorManager.openFile(existing, true, true)
            return
        }

        val boardFile = WorkItemBoardVirtualFile(iterationPath, iterationName)
        boardFiles[key] = boardFile
        editorManager.openFile(boardFile, true, true)
    }

    fun unregisterFile(file: VirtualFile) {
        when (file) {
            is WorkItemBoardVirtualFile -> {
                val key = file.iterationPath ?: "all"
                boardFiles.remove(key)
            }
        }
    }
}
