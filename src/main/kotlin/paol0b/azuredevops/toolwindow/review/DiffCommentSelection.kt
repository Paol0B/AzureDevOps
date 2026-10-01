package paol0b.azuredevops.toolwindow.review

import paol0b.azuredevops.model.FileCommentRange

object DiffCommentSelection {
    /** Resolve only selections whose every display row maps contiguously to one file side. */
    fun resolve(text: String, start: Int, end: Int, fileLine: (Int, Boolean) -> Int?): FileCommentRange? {
        if (start < 0 || end > text.length || start >= end) return null
        val lineStarts = mutableListOf(0)
        text.forEachIndexed { index, char -> if (char == '\n') lineStarts.add(index + 1) }
        fun lineAt(offset: Int) = lineStarts.binarySearch(offset).let { if (it >= 0) it else -it - 2 }
        val lastOffset = if (text[end - 1] == '\n') end - 1 else end
        val firstRow = lineAt(start)
        val lastRow = lineAt(lastOffset)
        for (left in listOf(false, true)) {
            val first = fileLine(firstRow, left)?.takeIf { it >= 0 } ?: continue
            if ((firstRow..lastRow).any { fileLine(it, left) != first + it - firstRow }) continue
            return FileCommentRange(left, first + 1, first + lastRow - firstRow + 1,
                start - lineStarts[firstRow] + 1, lastOffset - lineStarts[lastRow] + 1)
        }
        return null
    }
}
