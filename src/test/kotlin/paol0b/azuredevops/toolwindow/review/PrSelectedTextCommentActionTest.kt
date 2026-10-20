package paol0b.azuredevops.toolwindow.review

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.editor.EditorFactory
import com.intellij.testFramework.LightPlatformTestCase

class PrSelectedTextCommentActionTest : LightPlatformTestCase() {
    fun testDiffEditorPopupContainsSelectionAction() {
        val manager = ActionManager.getInstance()
        val action = manager.getAction("AzureDevOps.CommentSelectedText")
        assertNotNull(action)
        val group = manager.getAction("Diff.EditorPopupMenu") as ActionGroup
        assertTrue(group.getChildren(null).contains(action))
    }

    fun testActionOnlyEnabledForReadyPrEditorAndInvokesItsCallback() {
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(factory.createDocument("selected"), project)
        try {
            val action = PrSelectedTextCommentAction()
            val context = DataContext { key -> if (CommonDataKeys.EDITOR.`is`(key)) editor else null }
            val event = AnActionEvent.createFromAnAction(action, null, ActionPlaces.EDITOR_POPUP, context)
            action.update(event)
            assertFalse(event.presentation.isEnabledAndVisible)
            var ready = true
            var invoked = false
            editor.putUserData(PrSelectedTextCommentAction.CAN_COMMENT) { ready }
            editor.putUserData(PrSelectedTextCommentAction.COMMENT) { invoked = true }
            action.update(event)
            assertTrue(event.presentation.isEnabledAndVisible)
            action.actionPerformed(event)
            assertTrue(invoked)
            ready = false
            invoked = false
            action.update(event)
            assertFalse(event.presentation.isEnabledAndVisible)
            action.actionPerformed(event)
            assertFalse(invoked)
        } finally { factory.releaseEditor(editor) }
    }
}
