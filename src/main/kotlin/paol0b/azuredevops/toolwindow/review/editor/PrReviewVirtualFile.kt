package paol0b.azuredevops.toolwindow.review.editor

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileSystem
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

data class PrReviewKey(
    val pullRequestId: Int,
    val repositoryId: String?
)

object PrReviewFileNaming {
    fun buildFileName(key: PrReviewKey): String {
        val repoSuffix = key.repositoryId?.let { "-$it" } ?: ""
        return "PR Review #${key.pullRequestId}$repoSuffix.prreview"
    }
}

class PrReviewVirtualFile(
    val key: PrReviewKey
) : VirtualFile() {

    private val fileName = PrReviewFileNaming.buildFileName(key)
    private val fileSystem = PrReviewVirtualFileSystem()

    override fun getName(): String = fileName
    override fun getFileSystem(): VirtualFileSystem = fileSystem
    override fun getPath(): String = fileName
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
}

private class PrReviewVirtualFileSystem : VirtualFileSystem() {
    override fun getProtocol(): String = "prreview"
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
