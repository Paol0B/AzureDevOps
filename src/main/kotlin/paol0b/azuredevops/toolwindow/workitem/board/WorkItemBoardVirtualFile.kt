package paol0b.azuredevops.toolwindow.workitem.board

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileSystem
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Virtual file representing the Work Item Kanban Board.
 * Opened as a full editor tab for maximum screen real estate.
 */
class WorkItemBoardVirtualFile(
    val iterationPath: String?,
    val iterationName: String
) : VirtualFile() {

    private val displayName = if (iterationName.isNotBlank()) "Board — $iterationName" else "Work Item Board"
    private val fileSystem = WorkItemBoardVirtualFileSystem

    override fun getName(): String = displayName
    override fun getFileSystem(): VirtualFileSystem = fileSystem
    override fun getPath(): String = "workitemboard://${iterationPath ?: "all"}"
    override fun isWritable(): Boolean = false
    override fun isDirectory(): Boolean = false
    override fun isValid(): Boolean = true
    override fun getParent(): VirtualFile? = null
    override fun getChildren(): Array<VirtualFile>? = null
    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
        throw UnsupportedOperationException("Read-only virtual file")
    }
    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun getLength(): Long = 0
    override fun refresh(asynchronously: Boolean, recursive: Boolean, postRunnable: Runnable?) {}
    override fun getTimeStamp(): Long = 0
    override fun getModificationStamp(): Long = 0
    override fun getFileType() = PlainTextFileType.INSTANCE
    override fun contentsToByteArray(): ByteArray = ByteArray(0)

    // Only one board tab at a time (keyed by iteration)
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is WorkItemBoardVirtualFile) return false
        return iterationPath == other.iterationPath
    }

    override fun hashCode(): Int = iterationPath?.hashCode() ?: 0
}

object WorkItemBoardVirtualFileSystem : VirtualFileSystem() {
    override fun getProtocol(): String = "workitemboard"
    override fun findFileByPath(path: String): VirtualFile? = null
    override fun refresh(asynchronous: Boolean) {}
    override fun refreshAndFindFileByPath(path: String): VirtualFile? = null
    override fun addVirtualFileListener(listener: VirtualFileListener) {}
    override fun removeVirtualFileListener(listener: VirtualFileListener) {}
    override fun deleteFile(requestor: Any?, vfile: VirtualFile) {}
    override fun moveFile(requestor: Any?, vfile: VirtualFile, newParent: VirtualFile) {}
    override fun renameFile(requestor: Any?, vfile: VirtualFile, newName: String) {}
    override fun createChildFile(requestor: Any?, vdir: VirtualFile, fileName: String): VirtualFile {
        throw UnsupportedOperationException()
    }
    override fun createChildDirectory(requestor: Any?, vdir: VirtualFile, dirName: String): VirtualFile {
        throw UnsupportedOperationException()
    }
    override fun copyFile(requestor: Any?, vfile: VirtualFile, newParent: VirtualFile, copyName: String): VirtualFile {
        throw UnsupportedOperationException()
    }
    override fun isReadOnly(): Boolean = true
}
