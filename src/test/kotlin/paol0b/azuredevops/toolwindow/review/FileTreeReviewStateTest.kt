package paol0b.azuredevops.toolwindow.review

import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.ui.UIUtil
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.components.JBLabel
import javax.swing.JComponent
import paol0b.azuredevops.model.GitItem
import paol0b.azuredevops.model.PullRequestChange
import paol0b.azuredevops.services.ReviewedFilesState

class FileTreeReviewStateTest : LightPlatformTestCase() {
    fun testWebsiteMarkIsDisplayedAndFailuresKeepConfirmedCheckboxState() {
        val state = ReviewedFilesState.decode("""{"dataProviders":{"${ReviewedFilesState.PROVIDER}":{"visit":{"viewedState":"{\"hashes\":{\"1@abcdef01@/file.kt\":2}}"}}}}""")
        var failReads = false
        var failWrites = false
        val panel = FileTreePanel(project, 123,
            readReviewStatus = { if (failReads) error("Server unavailable") else state },
            writeReviewStatus = { _, _ -> if (failWrites) error("Save failed") else ReviewedFilesState() })
        try {
            val change = PullRequestChange(1, 2, "edit", GitItem("abcdef012345", "/file.kt", "blob", null, null), null)
            panel.loadFileChanges(listOf(change))
            waitForStatus(panel, "Review status synced")
            panel.selectFile("/file.kt")
            val tree = UIUtil.findComponentOfType(panel, CheckboxTree::class.java)!!
            val node = tree.lastSelectedPathComponent as CheckedTreeNode
            assertTrue("Website-reviewed file must appear checked", node.isChecked)
            failReads = true
            panel.syncReviewedFiles()
            waitForStatus(panel, "Review status sync failed")
            assertTrue("Failed reads must retain the last server-confirmed mark", node.isChecked)
            tree.setNodeState(node, false)
            waitForStatus(panel, "Review status synced")
            assertFalse("Confirmed server writes update the checkbox", node.isChecked)
            failWrites = true
            tree.setNodeState(node, true)
            waitForStatus(panel, "Review status sync failed")
            assertFalse("Failed writes must not claim a file was reviewed", node.isChecked)
        } finally { panel.dispose() }
    }

    private fun waitForStatus(panel: JComponent, expected: String) {
        val label = UIUtil.findComponentOfType(panel, JBLabel::class.java)!!
        val deadline = System.nanoTime() + 5_000_000_000L
        while (System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            if (label.text == expected) return
            Thread.sleep(5)
        }
        fail("Expected '$expected', got '${label.text}'")
    }

    fun testStatusRefreshDoesNotReopenTheSelectedDiff() {
        val panel = FileTreePanel(project, 123, readReviewStatus = { ReviewedFilesState() })
        try {
            val change = PullRequestChange(1, 2, "edit", GitItem("abcdef012345", "/file.kt", "blob", null, null), null)
            panel.loadFileChanges(listOf(change))
            UIUtil.dispatchAllInvocationEvents()
            panel.selectFile("/file.kt")
            var selectedEvents = 0
            panel.addFileSelectionListener { selectedEvents++ }
            panel.refreshTree()
            UIUtil.dispatchAllInvocationEvents()
            assertEquals("Updating checkboxes must preserve selection without reopening the diff", 0, selectedEvents)
            assertEquals(change, panel.getSelectedFileChange())
            val newer = change.copy(item = change.item!!.copy(objectId = "fedcba987654"))
            panel.loadFileChanges(listOf(newer))
            UIUtil.dispatchAllInvocationEvents()
            assertEquals("Polling new metadata must not steal focus or cancel a comment draft", 0, selectedEvents)
            assertEquals(newer, panel.getSelectedFileChange())
        } finally { panel.dispose() }
    }
}
