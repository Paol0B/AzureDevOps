package paol0b.azuredevops.services

import org.junit.Assert.*
import org.junit.Test
import paol0b.azuredevops.model.*
import java.io.IOException

class PullRequestDiffContentsTest {
    private fun change(type: String, oldPath: String? = null) = PullRequestChange(1, 1, type, GitItem(null, "/new.kt", "blob", null, null), oldPath)

    @Test fun `empty file content remains a valid response`() {
        assertEquals("", PullRequestDiffContents.decodeFileContent("""{"content":""}"""))
    }

    @Test(expected = IllegalStateException::class)
    fun `missing file content is not interpreted as an empty file`() {
        PullRequestDiffContents.decodeFileContent("""{"path":"/new.kt","isBinary":true}""")
    }

    @Test fun `added files fetch only the source side`() {
        val calls = mutableListOf<Pair<String, String>>()
        val contents = PullRequestDiffContents.load(change("add"), "/new.kt", "source", null) { commit, path ->
            calls.add(commit to path); "new contents"
        }
        assertEquals("" to "new contents", contents)
        assertEquals(listOf("source" to "/new.kt"), calls)
    }

    @Test fun `deleted files fetch only the target side`() {
        val calls = mutableListOf<Pair<String, String>>()
        val contents = PullRequestDiffContents.load(change("delete"), "/new.kt", null, "target") { commit, path ->
            calls.add(commit to path); "old contents"
        }
        assertEquals("old contents" to "", contents)
        assertEquals(listOf("target" to "/new.kt"), calls)
    }

    @Test fun `renamed edited files fetch their original path on the target side`() {
        val calls = mutableListOf<Pair<String, String>>()
        val contents = PullRequestDiffContents.load(change("rename, edit", "/old.kt"), "/new.kt", "source", "target") { commit, path ->
            calls.add(commit to path); if (commit == "target") "old" else "new"
        }
        assertEquals("old" to "new", contents)
        assertEquals(listOf("target" to "/old.kt", "source" to "/new.kt"), calls)
    }

    @Test(expected = IOException::class)
    fun `edited file download failure is propagated instead of rendered empty`() {
        PullRequestDiffContents.load(change("edit"), "/new.kt", "source", "target") { _, _ -> throw IOException("Timeout") }
    }

    @Test(expected = IOException::class)
    fun `preview with unknown change metadata never hides download failures`() {
        PullRequestDiffContents.load(null, "/unknown.kt", "source", "target") { _, _ -> throw IOException("Access denied") }
    }

    @Test(expected = IllegalStateException::class)
    fun `missing required commit fails instead of rendering an empty side`() {
        PullRequestDiffContents.load(change("edit"), "/new.kt", null, "target") { _, _ -> "old" }
    }
}
