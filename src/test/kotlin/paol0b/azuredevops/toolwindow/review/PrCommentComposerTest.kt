package paol0b.azuredevops.toolwindow.review

import org.junit.Assert.*
import org.junit.Test
import java.awt.Container
import javax.swing.*

class PrCommentComposerTest {
    private fun components(root: Container): List<java.awt.Component> = root.components.flatMap {
        listOf(it) + if (it is Container) components(it) else emptyList()
    }

    @Test fun `failed submission preserves draft and shows error then successful retry clears it`() {
        SwingUtilities.invokeAndWait {
            var completion: ((Result<Unit>) -> Unit)? = null
            var submitted = ""
            var refreshes = 0
            val composer = PrCommentComposer({ text, callback -> submitted = text; completion = callback }, { refreshes++ })
            val area = components(composer).filterIsInstance<JTextArea>().single()
            val button = components(composer).filterIsInstance<JButton>().single()
            area.text = "  Please add coverage  "
            button.doClick()
            assertEquals("Please add coverage", submitted)
            assertFalse(button.isEnabled)
            assertFalse(area.isEditable)
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
    }

    @Test fun `blank comment does not submit`() {
        SwingUtilities.invokeAndWait {
            val composer = PrCommentComposer({ _, _ -> fail("Blank comment sent") }, {})
            components(composer).filterIsInstance<JTextArea>().single().text = "  "
            components(composer).filterIsInstance<JButton>().single().doClick()
        }
    }
}
