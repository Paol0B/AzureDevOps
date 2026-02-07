package paol0b.azuredevops.services

import com.intellij.openapi.components.Service
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import paol0b.azuredevops.model.BuildTimeline
import paol0b.azuredevops.model.PipelineBuild
import paol0b.azuredevops.model.TimelineRecord
import paol0b.azuredevops.toolwindow.pipeline.editor.PipelineDiagramVirtualFile
import paol0b.azuredevops.toolwindow.pipeline.editor.PipelineLogFileEditor
import paol0b.azuredevops.toolwindow.pipeline.editor.PipelineLogVirtualFile
import java.util.concurrent.ConcurrentHashMap

/**
 * Service that manages virtual file ↔ pipeline data mappings,
 * mirroring the pattern of [PrReviewTabService].
 */
@Service(Service.Level.PROJECT)
class PipelineTabService(private val project: Project) {

    /** Build data stored by VirtualFile. */
    private val buildByFile = ConcurrentHashMap<VirtualFile, PipelineBuild>()

    /** Timeline data stored by VirtualFile. */
    private val timelineByFile = ConcurrentHashMap<VirtualFile, BuildTimeline>()

    /** Timeline record (task) stored by VirtualFile (for log files). */
    private val recordByFile = ConcurrentHashMap<VirtualFile, TimelineRecord>()

    /** Log virtual files, keyed by "buildId-logId". */
    private val logFiles = ConcurrentHashMap<String, PipelineLogVirtualFile>()

    /** Diagram virtual files, keyed by buildId. */
    private val diagramFiles = ConcurrentHashMap<Int, PipelineDiagramVirtualFile>()

    companion object {
        fun getInstance(project: Project): PipelineTabService {
            return project.getService(PipelineTabService::class.java)
        }
    }

    // ------------------------------------------------------------------
    // Log tabs
    // ------------------------------------------------------------------

    fun openLogTab(build: PipelineBuild, record: TimelineRecord) {
        val editorManager = FileEditorManager.getInstance(project)
        val logId = record.log?.id ?: return
        val logKey = "${build.id}-$logId"

        val existing = logFiles[logKey]
        if (existing != null) {
            editorManager.openFile(existing, true, true)
            return
        }

        val logFile = PipelineLogVirtualFile(build.id, logId, record.name ?: "Task Log")
        buildByFile[logFile] = build
        recordByFile[logFile] = record
        logFiles[logKey] = logFile
        editorManager.openFile(logFile, true, true)
    }

    // ------------------------------------------------------------------
    // Diagram tabs
    // ------------------------------------------------------------------

    fun openDiagramTab(build: PipelineBuild, timeline: BuildTimeline) {
        val editorManager = FileEditorManager.getInstance(project)

        val existing = diagramFiles[build.id]
        if (existing != null) {
            editorManager.openFile(existing, true, true)
            return
        }

        val diagramFile = PipelineDiagramVirtualFile(build.id, build.getDefinitionName(), build.buildNumber ?: "")
        buildByFile[diagramFile] = build
        timelineByFile[diagramFile] = timeline
        diagramFiles[build.id] = diagramFile
        editorManager.openFile(diagramFile, true, true)
    }

    // ------------------------------------------------------------------
    // Lookups
    // ------------------------------------------------------------------

    fun getBuild(file: VirtualFile): PipelineBuild? = buildByFile[file]
    fun getTimeline(file: VirtualFile): BuildTimeline? = timelineByFile[file]
    fun getTimelineRecord(file: VirtualFile): TimelineRecord? = recordByFile[file]

    fun unregisterFile(file: VirtualFile) {
        val build = buildByFile.remove(file) ?: return
        timelineByFile.remove(file)
        recordByFile.remove(file)

        when (file) {
            is PipelineLogVirtualFile -> {
                val logKey = "${build.id}-${file.logId}"
                logFiles.remove(logKey)
            }
            is PipelineDiagramVirtualFile -> {
                diagramFiles.remove(build.id)
            }
        }
    }
}
