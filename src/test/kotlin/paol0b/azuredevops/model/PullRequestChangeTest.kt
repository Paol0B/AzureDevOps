package paol0b.azuredevops.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PullRequestChangeTest {

    @Test
    fun `change type helpers handle renamed and modified files`() {
        val change = PullRequestChange(
            changeId = 1,
            changeTrackingId = 10,
            changeType = "rename, edit",
            item = GitItem(
                objectId = "new-sha",
                path = "/src/NewName.kt",
                gitObjectType = "blob",
                commitId = "source-commit",
                url = null
            ),
            originalPath = "/src/OldName.kt"
        )

        assertTrue(change.changeTypeTokens().containsAll(listOf("rename", "edit")))
        assertTrue(change.hasChangeType("rename"))
        assertTrue(change.hasChangeType("edit"))
        assertEquals("rename", change.primaryChangeType())
        assertEquals("/src/OldName.kt", change.previousPath())
    }

    @Test
    fun `previous path falls back to current path for non renamed files`() {
        val change = PullRequestChange(
            changeId = 2,
            changeTrackingId = 20,
            changeType = "edit",
            item = GitItem(
                objectId = "sha",
                path = "/src/Existing.kt",
                gitObjectType = "blob",
                commitId = "commit",
                url = null
            ),
            originalPath = null
        )

        assertEquals("edit", change.primaryChangeType())
        assertEquals("/src/Existing.kt", change.previousPath())
    }

    @Test
    fun `display helpers highlight added files clearly`() {
        val change = PullRequestChange(
            changeId = 3,
            changeTrackingId = 30,
            changeType = "add",
            item = GitItem(
                objectId = "sha-added",
                path = "/src/NewFile.kt",
                gitObjectType = "blob",
                commitId = "source-commit",
                url = null
            ),
            originalPath = null
        )

        assertTrue(change.isAddedFile())
        assertEquals("Added", change.displayChangeLabel())
        assertEquals(
            "Base (main) - file absent" to "Changes (feature) - added file",
            change.diffSideTitles("main", "feature")
        )
    }

    @Test
    fun `display helpers highlight removed files clearly`() {
        val change = PullRequestChange(
            changeId = 4,
            changeTrackingId = 40,
            changeType = "delete",
            item = GitItem(
                objectId = "sha-removed",
                path = "/src/OldFile.kt",
                gitObjectType = "blob",
                commitId = "target-commit",
                url = null
            ),
            originalPath = null
        )

        assertTrue(change.isRemovedFile())
        assertEquals("Removed", change.displayChangeLabel())
        assertEquals(
            "Base (main) - removed file" to "Changes (feature) - file absent",
            change.diffSideTitles("main", "feature")
        )
    }
}
