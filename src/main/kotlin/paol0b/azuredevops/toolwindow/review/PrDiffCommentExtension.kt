package paol0b.azuredevops.toolwindow.review

import com.intellij.diff.DiffContext
import com.intellij.diff.DiffExtension
import com.intellij.diff.FrameDiffTool
import com.intellij.diff.requests.DiffRequest
import com.intellij.openapi.util.Key

/** Bind after the selected diff tool creates its actual editors (including unified mode). */
class PrDiffCommentExtension : DiffExtension() {
    override fun onViewerCreated(viewer: FrameDiffTool.DiffViewer, context: DiffContext, request: DiffRequest) {
        request.getUserData(BIND_COMMENTS)?.invoke(viewer)
    }

    companion object {
        val BIND_COMMENTS = Key.create<(FrameDiffTool.DiffViewer) -> Unit>("azuredevops.pr.diff.comments")
    }
}
