package paol0b.azuredevops.toolwindow.review

import paol0b.azuredevops.model.FileCommentRange

/** A draft belongs to the exact file positions and revisions shown when it was opened. */
internal data class InlineCommentDraftKey(
    val pullRequestId: Int,
    val projectName: String?,
    val repositoryId: String?,
    val filePath: String,
    val range: FileCommentRange,
    val sourceCommit: String?,
    val targetCommit: String?
)

/** Retains composer state (including a pending submission) independently of diff popups. */
internal class InlineCommentDrafts<T> {
    private val drafts = linkedMapOf<InlineCommentDraftKey, T>()

    fun getOrCreate(key: InlineCommentDraftKey, create: () -> T): T = drafts.getOrPut(key, create)
    fun get(key: InlineCommentDraftKey): T? = drafts[key]
    fun remove(key: InlineCommentDraftKey) { drafts.remove(key) }
    fun keys(): List<InlineCommentDraftKey> = drafts.keys.toList()
}
