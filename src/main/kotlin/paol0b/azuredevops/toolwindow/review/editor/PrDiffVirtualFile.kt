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
data class PrDiffKey(
    val pullRequestId: Int,
    val filePath: String,
    val repositoryId: String?
)

class PrDiffVirtualFile(
    val key: PrDiffKey
) : VirtualFile() {

    val pullRequestId: Int get() = key.pullRequestId
    val filePath: String get() = key.filePath
    val repositoryId: String? get() = key.repositoryId

    private val fileSystem = PrDiffVirtualFileSystem

    override fun getName(): String = "PR #$pullRequestId: ${filePath.substringAfterLast('/')}"
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

    override fun equals(other: Any?): Boolean = other is PrDiffVirtualFile && key == other.key

    override fun hashCode(): Int = key.hashCode()
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
