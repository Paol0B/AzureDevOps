package paol0b.azuredevops.toolwindow.review

data class CommentLineRange(
    val startLine: Int,
    val endLine: Int
) {
    init {
        require(startLine > 0) { "startLine must be positive" }
        require(endLine >= startLine) { "endLine must not precede startLine" }
    }

    val displayText: String
        get() = if (startLine == endLine) "line $startLine" else "lines $startLine-$endLine"

    companion object {
        fun fromSelection(
            clickedLine: Int,
            selectionStartLine: Int?,
            selectionEndLine: Int?
        ): CommentLineRange {
            if (selectionStartLine == null || selectionEndLine == null) {
                return CommentLineRange(clickedLine, clickedLine)
            }

            val start = minOf(selectionStartLine, selectionEndLine)
            val end = maxOf(selectionStartLine, selectionEndLine)
            return if (clickedLine in start..end) {
                CommentLineRange(start, end)
            } else {
                CommentLineRange(clickedLine, clickedLine)
            }
        }
    }
}
