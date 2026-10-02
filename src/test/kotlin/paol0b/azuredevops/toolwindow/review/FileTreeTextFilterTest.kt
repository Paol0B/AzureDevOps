package paol0b.azuredevops.toolwindow.review

import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.CheckboxTree
import com.intellij.ui.SearchTextField
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.GitItem
import paol0b.azuredevops.model.PullRequestChange
import paol0b.azuredevops.services.ReviewedFilesState
import javax.swing.tree.DefaultMutableTreeNode

class FileTreeTextFilterTest : LightPlatformTestCase() {
    private fun change(path: String) = PullRequestChange(1, 2, "edit", GitItem("abcdef012345", path, "blob", null, null), null)
    private fun paths(panel: FileTreePanel): List<String> {
        val tree = UIUtil.findComponentOfType(panel, CheckboxTree::class.java)!!
        return (tree.model.root as DefaultMutableTreeNode).depthFirstEnumeration().toList()
            .mapNotNull { ((it as DefaultMutableTreeNode).userObject as? FileTreePanel.FileTreeData)?.filePath }.sorted()
    }
    fun testTextFilterMatchesFilenameAndPathAndKeepsQueryDuringRefresh() {
        val panel = FileTreePanel(project, 123, readReviewStatus = { ReviewedFilesState() })
        try {
            val changes = listOf(change("/src/Alpha.kt"), change("/tests/AlphaTest.kt"), change("/src/Beta.kt"))
            panel.loadFileChanges(changes)
            val search = UIUtil.findComponentOfType(panel, SearchTextField::class.java)
            assertNotNull("File list needs a visible text filter", search)
            search!!.text = " ALPHA "
            assertEquals(listOf("/src/Alpha.kt", "/tests/AlphaTest.kt"), paths(panel))
            panel.loadFileChanges(changes + change("/tests/Other.kt"))
            assertEquals(listOf("/src/Alpha.kt", "/tests/AlphaTest.kt"), paths(panel))
            search.text = "src/"
            assertEquals(listOf("/src/Alpha.kt", "/src/Beta.kt"), paths(panel))
            search.text = "no match"
            assertEquals(emptyList<String>(), paths(panel))
            search.text = ""
            assertEquals(4, paths(panel).size)
        } finally { panel.dispose() }
    }
    fun testFilterPreservesSelectedDeletedFileWithoutCurrentItem() {
        val panel = FileTreePanel(project, 123, readReviewStatus = { ReviewedFilesState() })
        try {
            val deleted = PullRequestChange(1, 2, "delete", null, "/src/Removed.kt")
            panel.loadFileChanges(listOf(deleted, change("/src/Other.kt")))
            panel.selectFile("/src/Removed.kt")
            var selections = 0
            panel.addFileSelectionListener { selections++ }
            UIUtil.findComponentOfType(panel, SearchTextField::class.java)!!.text = "removed"
            assertEquals(deleted, panel.getSelectedFileChange())
            assertEquals(0, selections)
        } finally { panel.dispose() }
    }
    fun testSearchCombinesWithReviewFilterAndDoesNotReopenDiff() {
        val state = ReviewedFilesState.decode("""{"dataProviders":{"${ReviewedFilesState.PROVIDER}":{"visit":{"viewedState":"{\"hashes\":{\"1@abcdef01@/src/Alpha.kt\":2}}"}}}}""")
        val panel = FileTreePanel(project, 123, readReviewStatus = { state })
        try {
            panel.loadFileChanges(listOf(change("/src/Alpha.kt"), change("/tests/AlphaTest.kt"), change("/src/Beta.kt")))
            val tree = UIUtil.findComponentOfType(panel, CheckboxTree::class.java)!!
            val deadline = System.nanoTime() + 5_000_000_000L
            while (System.nanoTime() < deadline) {
                UIUtil.dispatchAllInvocationEvents()
                if (tree.rowCount > 0 && tree.getPathForRow(1)?.lastPathComponent.let {
                        (it as? com.intellij.ui.CheckedTreeNode)?.isEnabled == true }) break
                Thread.sleep(5)
            }
            panel.selectFile("/src/Alpha.kt")
            var selections = 0
            panel.addFileSelectionListener { selections++ }
            val search = UIUtil.findComponentOfType(panel, SearchTextField::class.java)
            assertNotNull(search)
            search!!.text = "alpha"
            assertEquals("/src/Alpha.kt", panel.getSelectedFileChange()?.item?.path)
            assertEquals(0, selections)
            panel.setFilterMode(FileTreePanel.FilterMode.REVIEWED)
            assertEquals(listOf("/src/Alpha.kt"), paths(panel))
            panel.setFilterMode(FileTreePanel.FilterMode.UNREVIEWED)
            assertEquals(listOf("/tests/AlphaTest.kt"), paths(panel))
            assertEquals(0, selections)
        } finally { panel.dispose() }
    }
}
