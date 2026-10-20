package paol0b.azuredevops.services

import org.junit.Assert.*
import org.junit.Test

class RegularEditorCommentGuardsTest {
    private val remotes = listOf("origin" to "https://dev.azure.com/org/project/_git/other",
        "upstream" to "https://dev.azure.com/org/project/_git/target")
    @Test fun `tracked remote determines the PR repository`() {
        assertEquals("target", RegularEditorCommentGuards.repository(remotes, "upstream").repository)
    }
    @Test fun `untracked branches reject ambiguous repositories`() {
        assertThrows(IllegalArgumentException::class.java) { RegularEditorCommentGuards.repository(remotes, null) }
        assertEquals("other", RegularEditorCommentGuards.repository(remotes.take(1), null).repository)
    }
    @Test fun `non Azure tracked remote never falls back to a different remote`() {
        assertThrows(IllegalArgumentException::class.java) {
            RegularEditorCommentGuards.repository(remotes + ("github" to "https://github.com/org/target.git"), "github")
        }
    }
    @Test fun `local changes during server validation prevent posting`() {
        var current = true
        assertThrows(IllegalStateException::class.java) {
            RegularEditorCommentGuards.beforePost({ check(current) }) { current = false; true }
        }
    }
    @Test fun `server source changes prevent posting`() {
        assertThrows(IllegalStateException::class.java) { RegularEditorCommentGuards.beforePost({}) { false } }
    }
}
