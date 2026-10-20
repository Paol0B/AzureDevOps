package paol0b.azuredevops.toolwindow.review

/** Azure DevOps file coordinates, independent of the diff's display rows. */
data class DiffCommentLocation(val isLeftSide: Boolean, val lineNumber: Int) {
    companion object {
        fun fromUnifiedLines(leftLine: Int, rightLine: Int): DiffCommentLocation? = when {
            rightLine >= 0 -> DiffCommentLocation(false, rightLine + 1)
            leftLine >= 0 -> DiffCommentLocation(true, leftLine + 1)
            else -> null
        }
    }
}
