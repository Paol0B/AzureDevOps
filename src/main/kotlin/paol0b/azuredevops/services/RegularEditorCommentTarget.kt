package paol0b.azuredevops.services

import paol0b.azuredevops.model.*
import paol0b.azuredevops.toolwindow.review.DiffCommentSelection

/** A regular editor can use source-side coordinates only when its text matches the PR. */
data class RegularEditorCommentTarget(val change: PullRequestChange, val range: FileCommentRange) {
    companion object {
        fun resolve(localText: String, sourceText: String, filePath: String, changes: List<PullRequestChange>,
                    caretOffset: Int, selectionStart: Int = caretOffset, selectionEnd: Int = caretOffset): RegularEditorCommentTarget {
            val change = changes.firstOrNull { it.effectivePath() == filePath && !it.isRemovedFile() }
            require(change != null) { "This file is not part of the pull request's source changes." }
            require(localText == sourceText.replace("\r\n", "\n").replace('\r', '\n').removePrefix("\uFEFF")) {
                "Local code differs from the PR version. Open the PR diff to comment, or update your branch first."
            }
            require(caretOffset in 0..localText.length) { "The comment location is no longer valid." }
            val range = if (selectionStart != selectionEnd) {
                DiffCommentSelection.resolve(localText, selectionStart, selectionEnd) { row, left -> row.takeUnless { left } }
                    ?: throw IllegalArgumentException("Select a contiguous code range to comment.")
            } else FileCommentRange(false, localText.take(caretOffset).count { it == '\n' } + 1)
            return RegularEditorCommentTarget(change, range)
        }
    }
}
