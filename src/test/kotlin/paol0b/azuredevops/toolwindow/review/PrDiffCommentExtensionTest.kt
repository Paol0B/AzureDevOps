package paol0b.azuredevops.toolwindow.review

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.impl.DiffRequestProcessor
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.fragmented.UnifiedDiffTool
import com.intellij.diff.tools.fragmented.UnifiedDiffViewer
import com.intellij.diff.util.DiffUserDataKeysEx
import com.intellij.diff.util.Side
import paol0b.azuredevops.model.FileCommentRange
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.ui.UIUtil

class PrDiffCommentExtensionTest : LightPlatformTestCase() {
    fun testUnifiedViewerReceivesCommentBindingAndMapsInsertedRows() {
        val factory = DiffContentFactory.getInstance()
        val request = SimpleDiffRequest("PR diff", factory.create("first\nlast\n"),
            factory.create("first\nadded\nlast\n"), "Base", "Changes")
        var boundViewer: UnifiedDiffViewer? = null
        request.putUserData(PrDiffCommentExtension.BIND_COMMENTS) { viewer ->
            boundViewer = viewer as? UnifiedDiffViewer
        }
        val hints = UserDataHolderBase().apply {
            putUserData(DiffUserDataKeysEx.FORCE_DIFF_TOOL, UnifiedDiffTool.INSTANCE)
        }
        val processor = object : DiffRequestProcessor(project, hints) {
            override fun updateRequest(force: Boolean, scrollToChangePolicy: DiffUserDataKeysEx.ScrollToPolicy?) {
                applyRequest(request, force, scrollToChangePolicy)
            }
        }
        try {
            processor.updateRequest()
            UIUtil.dispatchAllInvocationEvents()
            assertNotNull("Registered PR extension must receive the unified viewer", boundViewer)
            val viewer = boundViewer!!
            viewer.rediff(true)
            UIUtil.dispatchAllInvocationEvents()
            val addedRow = viewer.transferLineToOnesideStrict(Side.RIGHT, 1)
            assertTrue(addedRow >= 0)
            val document = viewer.editor.document
            val selectedStart = document.getLineStartOffset(addedRow) + 1
            val selectedEnd = document.getLineStartOffset(addedRow) + 4
            assertEquals(FileCommentRange(false, 2, 2, 2, 5), DiffCommentSelection.resolve(
                document.text, selectedStart, selectedEnd
            ) { row, left ->
                viewer.transferLineFromOnesideStrict(if (left) Side.LEFT else Side.RIGHT, row).takeIf { it >= 0 }
            })
            assertEquals(DiffCommentLocation(false, 2), DiffCommentLocation.fromUnifiedLines(
                viewer.transferLineFromOnesideStrict(Side.LEFT, addedRow),
                viewer.transferLineFromOnesideStrict(Side.RIGHT, addedRow)
            ))
        } finally {
            Disposer.dispose(processor)
        }
    }
}
