package paol0b.azuredevops.toolwindow.create

import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileListener
import com.intellij.openapi.vfs.VirtualFileSystem
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * Singleton virtual file used to show diffs when creating a PR.
 *
 * Because [equals]/[hashCode] are constant the platform will always reuse
 * the same editor tab — clicking a different file simply updates the content
 * via [CreatePrDiffFileEditor.showChange].
 */
class CreatePrDiffVirtualFile private constructor() : VirtualFile() {

    companion object {
        /** Single instance per project — stored in [CreatePullRequestPanel]. */
        fun create(): CreatePrDiffVirtualFile = CreatePrDiffVirtualFile()
    }

    /** Updated by the panel before opening / refreshing the tab. */
    var displayFileName: String = "Diff"

    override fun getName(): String = "New PR: $displayFileName"
    override fun getFileSystem(): VirtualFileSystem = CreatePrDiffVirtualFileSystem
    override fun getPath(): String = "create-pr-diff://diff"
    override fun isWritable(): Boolean = false
    override fun isDirectory(): Boolean = false
    override fun isValid(): Boolean = true
    override fun getParent(): VirtualFile? = null
    override fun getChildren(): Array<VirtualFile>? = null
    override fun getOutputStream(requestor: Any?, newModificationStamp: Long, newTimeStamp: Long): OutputStream {
        throw UnsupportedOperationException("Read-only")
    }
    override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    override fun getLength(): Long = 0
    override fun refresh(asynchronously: Boolean, recursive: Boolean, postRunnable: Runnable?) {}
    override fun getTimeStamp(): Long = 0
    override fun getModificationStamp(): Long = 0
    override fun getFileType() = PlainTextFileType.INSTANCE
    override fun contentsToByteArray(): ByteArray = ByteArray(0)

    // Always equal so the platform reuses the same editor tab
    override fun equals(other: Any?): Boolean = other is CreatePrDiffVirtualFile
    override fun hashCode(): Int = 0x0C0EA7E
}

object CreatePrDiffVirtualFileSystem : VirtualFileSystem() {
    override fun getProtocol(): String = "create-pr-diff"
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
