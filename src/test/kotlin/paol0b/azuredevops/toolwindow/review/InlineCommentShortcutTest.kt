package paol0b.azuredevops.toolwindow.review

import com.intellij.testFramework.LightPlatformTestCase
import paol0b.azuredevops.model.FileCommentRange
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.Container
import javax.swing.*

class InlineCommentShortcutTest : LightPlatformTestCase() {
    private fun components(root: Container): List<java.awt.Component> = root.components.flatMap {
        listOf(it) + if (it is Container) components(it) else emptyList()
    }

    fun testControlAndCommandEnterUseSubmitButtonAndRespectPendingSubmission() {
        val composer = InlineCommentEditorComponent(project, AzureDevOpsApiClient(project),
            1, "/file.kt", FileCommentRange(false, 2), null, null, null, {}, {})
        val area = components(composer).filterIsInstance<ReviewCommentInput>().single()
        val button = components(composer).filterIsInstance<JButton>().single { it.text == "Add Review Comment" }
        // Empty text keeps the real submission handler off the network; observe actual button dispatch.
        var clicks = 0
        button.addActionListener { clicks++ }
        for (stroke in listOf("control ENTER", "meta ENTER")) {
            invokeReviewShortcut(area, stroke)
            assertEquals(1, clicks)
            button.isEnabled = false
            invokeReviewShortcut(area, stroke)
            assertEquals("Shortcut must not post again while submission is pending", 1, clicks)
            button.isEnabled = true
            clicks = 0
        }
        assertFalse("Plain Enter must remain with the editor/IME",
            com.intellij.openapi.actionSystem.ex.ActionUtil.getActions(area).any {
                it.shortcutSet.shortcuts.contains(com.intellij.openapi.actionSystem.KeyboardShortcut(KeyStroke.getKeyStroke("ENTER"), null))
            })
    }
}
