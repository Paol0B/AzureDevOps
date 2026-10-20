package paol0b.azuredevops.toolwindow.review

import com.intellij.testFramework.LightPlatformTestCase
import paol0b.azuredevops.model.FileCommentRange
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.Container
import java.awt.event.ActionEvent
import javax.swing.*

class InlineCommentShortcutTest : LightPlatformTestCase() {
    private fun components(root: Container): List<java.awt.Component> = root.components.flatMap {
        listOf(it) + if (it is Container) components(it) else emptyList()
    }

    fun testControlAndCommandEnterUseSubmitButtonAndRespectPendingSubmission() {
        val composer = InlineCommentEditorComponent(project, AzureDevOpsApiClient(project),
            1, "/file.kt", FileCommentRange(false, 2), null, null, null, {}, {})
        val area = components(composer).filterIsInstance<JTextArea>().single()
        val button = components(composer).filterIsInstance<JButton>().single { it.text == "Add Review Comment" }
        // Empty text keeps the real submission handler off the network; observe actual button dispatch.
        var clicks = 0
        button.addActionListener { clicks++ }
        for (stroke in listOf("control ENTER", "meta ENTER")) {
            val key = area.inputMap.get(KeyStroke.getKeyStroke(stroke))
            assertNotNull("$stroke must have a submit binding", key)
            val action = area.actionMap.get(key)
            assertNotNull(action)
            action.actionPerformed(ActionEvent(area, ActionEvent.ACTION_PERFORMED, "submit"))
            assertEquals(1, clicks)
            button.isEnabled = false
            action.actionPerformed(ActionEvent(area, ActionEvent.ACTION_PERFORMED, "submit"))
            assertEquals("Shortcut must not post again while submission is pending", 1, clicks)
            button.isEnabled = true
            clicks = 0
        }
        assertFalse("Plain Enter must remain a newline", area.inputMap.get(KeyStroke.getKeyStroke("meta ENTER")) ==
            area.inputMap.get(KeyStroke.getKeyStroke("ENTER")))
    }
}
