package paol0b.azuredevops.toolwindow.review

import com.intellij.testFramework.LightPlatformTestCase
import java.awt.event.InputMethodEvent
import java.text.AttributedString

class ReviewCommentInputTest : LightPlatformTestCase() {
    fun testCompositionGuardsSubmitAndCancelUntilTextIsCommitted() {
        val input = ReviewCommentInput(project, false, "Comment")
        var submissions = 0
        var cancellations = 0
        input.registerSubmitShortcut { submissions++ }
        input.registerCancelShortcut { cancellations++ }
        input.addNotify()
        try {
            val target = input.editor!!.contentComponent
            fun composition(text: String, committed: Int) {
                val event = InputMethodEvent(target, InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                    AttributedString(text).iterator, committed, null, null)
                target.inputMethodListeners.forEach { it.inputMethodTextChanged(event) }
            }
            composition("中文", 0)
            invokeReviewShortcut(input, "control ENTER")
            invokeReviewShortcut(input, "meta ENTER")
            invokeReviewShortcut(input, "ESCAPE")
            assertEquals(0, submissions)
            assertEquals(0, cancellations)
            composition("中文", 2)
            invokeReviewShortcut(input, "control ENTER")
            invokeReviewShortcut(input, "meta ENTER")
            invokeReviewShortcut(input, "ESCAPE")
            assertEquals(2, submissions)
            assertEquals(1, cancellations)
            input.text = "中文測試：這是一則 PR 留言，English 123 😀"
            assertEquals("中文測試：這是一則 PR 留言，English 123 😀", input.text)
        } finally {
            input.removeNotify()
        }
    }
}
