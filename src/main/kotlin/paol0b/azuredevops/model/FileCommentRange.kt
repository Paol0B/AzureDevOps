package paol0b.azuredevops.model

/** One-based Azure DevOps positions; endOffset is the exclusive end of selected text. */
data class FileCommentRange(
    val isLeftSide: Boolean,
    val startLine: Int,
    val endLine: Int = startLine,
    val startOffset: Int = 1,
    val endOffset: Int = 1
) {
    init {
        require(startLine > 0 && endLine >= startLine && startOffset > 0 && endOffset > 0)
        require(endLine != startLine || endOffset >= startOffset)
    }
    fun threadContext(filePath: String): Map<String, Any> {
        require(filePath.isNotBlank())
        val side = if (isLeftSide) "left" else "right"
        return mapOf("filePath" to if (filePath.startsWith('/')) filePath else "/$filePath",
            "${side}FileStart" to mapOf("line" to startLine, "offset" to startOffset),
            "${side}FileEnd" to mapOf("line" to endLine, "offset" to endOffset))
    }
}
