package paol0b.azuredevops.toolwindow.review

import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.ui.UIUtil
import com.intellij.util.xmlb.XmlSerializer
import com.intellij.ui.CheckboxTree
import com.intellij.ui.CheckedTreeNode
import com.intellij.ui.components.JBLabel
import javax.swing.JComponent
import paol0b.azuredevops.model.GitItem
import paol0b.azuredevops.model.PullRequestChange
import paol0b.azuredevops.services.ReviewedFilesState
import paol0b.azuredevops.services.PrReviewStateService

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
            waitForStatus(panel, "Local review status — sync failed")
            assertTrue("Failed reads must retain the last server-confirmed mark", node.isChecked)
            tree.setNodeState(node, false)
            waitForStatus(panel, "Review status synced")
            assertFalse("Confirmed server writes update the checkbox", node.isChecked)
            failWrites = true
            tree.setNodeState(node, true)
            waitForStatus(panel, "Local review status — sync failed")
            assertTrue("Failed writes retain the local reviewed mark", node.isChecked)
            assertTrue("Failed writes leave checkboxes usable", node.isEnabled)
        } finally { panel.dispose() }
    }

    override fun tearDown() {
        try {
            PrReviewStateService.getInstance(project).loadState(PrReviewStateService.State())
        } finally { super.tearDown() }
    }

    private fun file(sha: String = "abcdef012345") =
        PullRequestChange(1, 2, "edit", GitItem(sha, "/file.kt", "blob", null, null), null)

    private fun selectedNode(panel: FileTreePanel): CheckedTreeNode {
        panel.selectFile("/file.kt")
        return UIUtil.findComponentOfType(panel, CheckboxTree::class.java)!!.lastSelectedPathComponent as CheckedTreeNode
    }

    fun testInitialFailurePreservesLegacyMarksAndLocalChangesAcrossReopening() {
        val service = PrReviewStateService.getInstance(project)
        service.markFileAsReviewed(123, "/file.kt")
        val panel = FileTreePanel(project, 123, readReviewStatus = { error("Unavailable") },
            writeReviewStatus = { _, _ -> error("Unavailable") })
        try {
            panel.loadFileChanges(listOf(file()))
            waitForStatus(panel, "Local review status — sync failed")
            val node = selectedNode(panel)
            assertTrue("Existing local marks must survive an unavailable endpoint", node.isChecked)
            assertTrue("Unavailable server must not disable local reviewing", node.isEnabled)
            val tree = UIUtil.findComponentOfType(panel, CheckboxTree::class.java)!!
            tree.setNodeState(node, false)
            waitForStatus(panel, "Local review status — sync failed")
            assertFalse(node.isChecked)
            assertFalse(service.isFileReviewed(123, "/file.kt"))
        } finally { panel.dispose() }
        // Exercise the actual persistent bean format, including the false override.
        val saved = XmlSerializer.serialize(service.getState())
        service.loadState(PrReviewStateService.State())
        service.loadState(XmlSerializer.deserialize(saved, PrReviewStateService.State::class.java))
        val reopened = FileTreePanel(project, 123, readReviewStatus = { error("Unavailable") })
        try {
            reopened.loadFileChanges(listOf(file()))
            waitForStatus(reopened, "Local review status — sync failed")
            assertFalse("Unreviewed local overrides persist across reopening", selectedNode(reopened).isChecked)
        } finally { reopened.dispose() }
    }

    fun testSuccessfulRetryMigratesLegacyMarkAndKeepsVersionSemantics() {
        PrReviewStateService.getInstance(project).markFileAsReviewed(123, "/file.kt")
        val empty = ReviewedFilesState()
        val marked = ReviewedFilesState.decode("""{"dataProviders":{"${ReviewedFilesState.PROVIDER}":{"visit":{"viewedState":"{\"hashes\":{\"1@abcdef01@/file.kt\":2}}"}}}}""")
        var available = false
        var writes = 0
        val panel = FileTreePanel(project, 123,
            readReviewStatus = { if (available) empty else error("Unavailable") },
            writeReviewStatus = { change, reviewed ->
                check(change.item?.objectId == "abcdef012345" && reviewed)
                writes++
                marked
            })
        try {
            panel.loadFileChanges(listOf(file()))
            waitForStatus(panel, "Local review status — sync failed")
            available = true
            panel.syncReviewedFiles()
            waitForStatus(panel, "Review status synced")
            assertEquals("Legacy local marks must migrate rather than disappear on retry", 1, writes)
            assertTrue(selectedNode(panel).isChecked)
            panel.loadFileChanges(listOf(file("fedcba987654")))
            waitForStatus(panel, "Review status synced")
            assertFalse("A migrated path mark must not mark a newer version reviewed", selectedNode(panel).isChecked)
            assertEquals(1, writes)
        } finally { panel.dispose() }
    }

    fun testRetryDoesNotOverwriteUnsyncedUnreviewedOverrideWithServerMark() {
        val marked = ReviewedFilesState.decode("""{"dataProviders":{"${ReviewedFilesState.PROVIDER}":{"visit":{"viewedState":"{\"hashes\":{\"1@abcdef01@/file.kt\":2}}"}}}}""")
        var canWrite = false
        var writes = 0
        val panel = FileTreePanel(project, 123, readReviewStatus = { marked },
            writeReviewStatus = { _, reviewed ->
                assertFalse("Retry must submit the local unreviewed choice", reviewed)
                writes++
                if (!canWrite) error("Unavailable") else ReviewedFilesState()
            })
        try {
            panel.loadFileChanges(listOf(file()))
            waitForStatus(panel, "Review status synced")
            val node = selectedNode(panel)
            UIUtil.findComponentOfType(panel, CheckboxTree::class.java)!!.setNodeState(node, false)
            waitForStatus(panel, "Local review status — sync failed")
            assertFalse(node.isChecked)
            canWrite = true
            panel.syncReviewedFiles()
            waitForStatus(panel, "Review status synced")
            assertEquals(2, writes)
            assertFalse("Server snapshot must not erase an unsynced local unreview", node.isChecked)
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
