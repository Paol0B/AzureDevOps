package paol0b.azuredevops.model

import org.junit.Assert.*
import org.junit.Test

class FileCommentRangeTest {
    @Test fun `selected source columns reach the Azure DevOps thread payload`() {
        val context = FileCommentRange(false, 143, 144, 8, 16).threadContext("file.kt")
        assertEquals("/file.kt", context["filePath"])
        assertEquals(mapOf("line" to 143, "offset" to 8), context["rightFileStart"])
        assertEquals(mapOf("line" to 144, "offset" to 16), context["rightFileEnd"])
        assertFalse(context.containsKey("leftFileStart"))
    }
    @Test fun `removed text is anchored on target side`() {
        val context = FileCommentRange(true, 5, 5, 2, 9).threadContext("/file.kt")
        assertEquals(mapOf("line" to 5, "offset" to 2), context["leftFileStart"])
        assertEquals(mapOf("line" to 5, "offset" to 9), context["leftFileEnd"])
        assertFalse(context.containsKey("rightFileStart"))
    }
    @Test fun `existing line comments retain default offsets`() {
        val context = FileCommentRange(false, 5).threadContext("/file.kt")
        assertEquals(mapOf("line" to 5, "offset" to 1), context["rightFileStart"])
        assertEquals(context["rightFileStart"], context["rightFileEnd"])
    }
}
