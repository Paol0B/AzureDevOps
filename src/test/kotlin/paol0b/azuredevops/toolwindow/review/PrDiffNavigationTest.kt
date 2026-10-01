package paol0b.azuredevops.toolwindow.review

import com.intellij.diff.util.LineCol
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import java.io.File

class PrDiffNavigationTest : LightPlatformTestCase() {
    private val remote = "https://dev.azure.com/org/project/_git/repo"

    fun testSourceSnapshotNavigatesToLocalFileWithoutDisplayingLocalEdits() = withLocalFile { file ->
        val content = PrDiffNavigation.createContent(project, "PR snapshot\n", PlainTextFileType.INSTANCE, file)
        assertEquals("PR snapshot\n", content.document.text)
        assertEquals(file, content.highlightFile)
        assertTrue(content.getNavigatable(LineCol(0, 0))?.canNavigate() == true)
    }

    fun testUnmatchedFileKeepsServerSnapshotWithoutNavigation() {
        val content = PrDiffNavigation.createContent(project, "PR snapshot", PlainTextFileType.INSTANCE, null)
        assertEquals("PR snapshot", content.document.text)
        assertNull(content.getNavigatable(LineCol(0, 0)))
    }

    fun testCheckoutMatchesFullBranchAndAzureRepositoryAcrossRemoteFormats() {
        assertTrue(PrDiffNavigation.matchesCheckout(listOf("git@ssh.dev.azure.com:v3/org/project/repo"),
            "feature/topic", null, remote, "refs/heads/feature/topic"))
        assertTrue(PrDiffNavigation.matchesCheckout(listOf(remote),
            "local-alias", "feature/topic", remote, "refs/heads/feature/topic"))
        assertFalse(PrDiffNavigation.matchesCheckout(listOf(remote),
            "different/topic", null, remote, "refs/heads/feature/topic"))
        assertFalse(PrDiffNavigation.matchesCheckout(listOf("https://dev.azure.com/other/project/_git/repo"),
            "feature/topic", null, remote, "refs/heads/feature/topic"))
        assertFalse(PrDiffNavigation.matchesCheckout(listOf(remote),
            null, null, remote, "refs/heads/feature/topic"))
        assertFalse(PrDiffNavigation.matchesCheckout(listOf(remote),
            "feature/topic", null, null, "refs/heads/feature/topic"))
    }

    fun testLocalPathDoesNotEscapeRepositoryOrNavigateToDirectory() = withLocalFile { file ->
        val root = file.parent
        assertEquals(file, PrDiffNavigation.findFile(root, "/navigation.txt"))
        assertNull(PrDiffNavigation.findFile(root, "/../navigation.txt"))
        assertNull(PrDiffNavigation.findFile(root, "/"))
        assertNull(PrDiffNavigation.findFile(root, "/missing.txt"))
    }
    private fun withLocalFile(test: (VirtualFile) -> Unit) {
        val directory = FileUtil.createTempDirectory("pr-navigation", "", true)
        try {
            val path = File(directory, "navigation.txt").apply { writeText("local edits\n") }
            val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path)!!
            test(file)
        } finally { FileUtil.delete(directory) }
    }
}
