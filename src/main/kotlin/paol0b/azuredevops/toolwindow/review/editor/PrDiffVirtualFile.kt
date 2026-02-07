package paol0b.azuredevops.toolwindow.review.editor

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Virtual file representing a single file diff inside a Pull Request.
 * Opened in an editor tab when the user clicks a file in the review tab's file tree.
 */
class PrDiffVirtualFile(
    val pullRequestId: Int,
    val filePath: String,
    val repositoryId: String?
) : VirtualFile() {

    private val displayName = "PR #$pullRequestId: ${filePath.substringAfterLast('/')}"
    private val fileSystem = PrDiffVirtualFileSystem

    override fun getName(): String = displayName
    override fun getFileSystem(): VirtualFileSystem = fileSystem
    override fun getPath(): String = "prdiff://$pullRequestId/${filePath}"
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

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PrDiffVirtualFile) return false
        return pullRequestId == other.pullRequestId && filePath == other.filePath
    }

    override fun hashCode(): Int = 31 * pullRequestId + filePath.hashCode()
}

object PrDiffVirtualFileSystem : VirtualFileSystem() {
    override fun getProtocol(): String = "prdiff"
    override fun findFileByPath(path: String): VirtualFile? = null
    override fun refresh(asynchronous: Boolean) {}
    override fun refreshAndFindFileByPath(path: String): VirtualFile? = null
    override fun addVirtualFileListener(listener: com.intellij.openapi.vfs.VirtualFileListener) {}
    override fun removeVirtualFileListener(listener: com.intellij.openapi.vfs.VirtualFileListener) {}
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
