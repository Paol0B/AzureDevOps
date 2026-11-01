package paol0b.azuredevops.toolwindow.review

import org.junit.Assert.*
import com.intellij.testFramework.LightPlatformTestCase
import java.awt.Container
import javax.swing.*

class PrCommentComposerTest : LightPlatformTestCase() {
    private fun components(root: Container): List<java.awt.Component> = root.components.flatMap {
        listOf(it) + if (it is Container) components(it) else emptyList()
    }

    fun testFailedSubmissionPreservesDraftAndSuccessfulRetryClearsIt() {
            var completion: ((Result<Unit>) -> Unit)? = null
            var submitted = ""
            var refreshes = 0
            val composer = PrCommentComposer({ text, callback -> submitted = text; completion = callback }, { refreshes++ })
            val area = components(composer).filterIsInstance<ReviewCommentInput>().single()
            val button = components(composer).filterIsInstance<JButton>().single { it.text == "Add comment" }
            area.text = "  Please add coverage  "
            button.doClick()
            assertEquals("Please add coverage", submitted)
            assertFalse(button.isEnabled)
            assertFalse(area.isEnabled)
            completion!!(Result.failure(IllegalStateException("Permission denied")))
            assertEquals("  Please add coverage  ", area.text)
            assertTrue(button.isEnabled)
            assertTrue(components(composer).filterIsInstance<JLabel>().any { it.text.contains("Permission denied") })
            assertEquals(0, refreshes)
            button.doClick()
            completion!!(Result.success(Unit))
            assertEquals("", area.text)
            assertEquals(1, refreshes)
    }

    fun testBlankCommentDoesNotSubmit() {
            val composer = PrCommentComposer({ _, _ -> fail("Blank comment sent") }, {})
            components(composer).filterIsInstance<ReviewCommentInput>().single().text = "  "
            components(composer).filterIsInstance<JButton>().single { it.text == "Add comment" }.doClick()
    }
}
