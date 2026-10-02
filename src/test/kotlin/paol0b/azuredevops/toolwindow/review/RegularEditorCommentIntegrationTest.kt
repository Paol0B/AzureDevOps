package paol0b.azuredevops.toolwindow.review

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.FileCommentRange
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.Container
import javax.swing.*

class RegularEditorCommentIntegrationTest : LightPlatformTestCase() {
    private fun components(root: Container): List<java.awt.Component> = root.components.flatMap {
        listOf(it) + if (it is Container) components(it) else emptyList()
    }
    fun testNewCommentActionIsRegisteredInRegularEditorPopup() {
        val manager = ActionManager.getInstance()
        val action = manager.getAction("AzureDevOps.AddEditorPRComment")
        assertNotNull(action)
        assertTrue((manager.getAction("EditorPopupMenu") as ActionGroup).getChildren(null).contains(action))
    }
    fun testChangedCodeRejectsSubmissionAndKeepsDraft() {
        var checks = 0
        var posted = false
        val composer = InlineCommentEditorComponent(project, AzureDevOpsApiClient(project),
            1, "/file.kt", FileCommentRange(false, 2), null, null, null, { posted = true }, {},
            beforeSubmit = { checks++; error("The file or branch changed") })
        val area = components(composer).filterIsInstance<JTextArea>().single()
        val button = components(composer).filterIsInstance<JButton>().single { it.text == "Add Review Comment" }
        area.text = "Please simplify this code"
        button.doClick()
        val deadline = System.nanoTime() + 5_000_000_000L
        while (!button.isEnabled && System.nanoTime() < deadline) {
            UIUtil.dispatchAllInvocationEvents()
            Thread.sleep(5)
        }
        assertEquals(1, checks)
        assertTrue(button.isEnabled)
        assertFalse(posted)
        assertEquals("Please simplify this code", area.text)
        assertTrue(components(composer).filterIsInstance<JLabel>().any {
            it.isVisible && it.toolTipText?.contains("file or branch changed") == true
        })
    }
}
