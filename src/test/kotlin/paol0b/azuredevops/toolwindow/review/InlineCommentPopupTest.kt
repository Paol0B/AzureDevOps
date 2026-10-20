package paol0b.azuredevops.toolwindow.review

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.popup.AbstractPopup
import paol0b.azuredevops.model.FileCommentRange
import paol0b.azuredevops.services.AzureDevOpsApiClient

class InlineCommentPopupTest : LightPlatformTestCase() {
    fun testCommentEditorSurvivesOutsideClicksAndWindowChanges() {
        val component = InlineCommentEditorComponent(project, AzureDevOpsApiClient(project),
            1, "/file.kt", FileCommentRange(false, 2, 3, 2, 5), null, null, null, {}, {})
        val popup = component.createPopup()
        try {
            assertTrue("Draft editor must not dismiss on outside clicks or other windows opening", popup.isPersistent)
            val deactivation = AbstractPopup::class.java.getDeclaredMethod("isCancelOnWindowDeactivation")
            deactivation.isAccessible = true
            assertFalse("Switching windows must not discard the draft", deactivation.invoke(popup) as Boolean)
            assertFalse("Native Escape must not bypass the guarded composer cancellation", popup.isCancelKeyEnabled)
        } finally { Disposer.dispose(popup) }
    }
    fun testHeaderNamesPullRequest() {
        val component = InlineCommentEditorComponent(project, AzureDevOpsApiClient(project),
            84, "/file.kt", FileCommentRange(false, 2), null, null, null, {}, {})
        fun labels(container: java.awt.Container): List<javax.swing.JLabel> = container.components.flatMap {
            (if (it is javax.swing.JLabel) listOf(it) else emptyList()) +
                (if (it is java.awt.Container) labels(it) else emptyList())
        }
        assertTrue("Composer must identify its PR", labels(component).any { it.text.contains("PR #84") })
    }
    fun testPopupReplacementRetainsTextAndPendingComposer() {
        val drafts = InlineCommentDrafts<InlineCommentEditorComponent>()
        val key = InlineCommentDraftKey(84, "project", "repo", "/file.kt",
            FileCommentRange(false, 2), "source", "target")
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        var cancellations = 0
        val component = drafts.getOrCreate(key) {
            InlineCommentEditorComponent(project, AzureDevOpsApiClient(project),
                84, "/file.kt", key.range, "project", "repo", null, {}, { cancellations++ }, {
                    entered.countDown()
                    release.await(10, java.util.concurrent.TimeUnit.SECONDS)
                    throw IllegalStateException("Intentional validation rejection: no server call")
                })
        }
        fun components(container: java.awt.Container): List<java.awt.Component> = container.components.flatMap {
            listOf(it) + (if (it is java.awt.Container) components(it) else emptyList())
        }
        val area = components(component).filterIsInstance<javax.swing.JTextArea>().single()
        val submit = components(component).filterIsInstance<javax.swing.JButton>().single { it.text == "Add Review Comment" }
        area.text = "Unsent review text"
        val first = component.createPopup()
        try {
            submit.doClick()
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse("Native Escape must stay disabled during posting", first.isCancelKeyEnabled)
            val escape = java.awt.event.KeyEvent(area, java.awt.event.KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), 0, java.awt.event.KeyEvent.VK_ESCAPE, java.awt.event.KeyEvent.CHAR_UNDEFINED)
            area.keyListeners.forEach { it.keyPressed(escape) }
            assertEquals("Escape must not discard a comment while posting", 0, cancellations)
            first.cancel()
            val reopened = drafts.getOrCreate(key) { error("Draft must already exist") }
            assertSame(component, reopened)
            assertEquals("Unsent review text", area.text)
            assertFalse("Reopening must not enable a duplicate post", submit.isEnabled)
            assertFalse("Text must be frozen while its captured content is being posted", area.isEditable)
            val replacement = reopened.createPopup()
            Disposer.dispose(replacement)
        } finally {
            release.countDown()
            Disposer.dispose(first)
        }
    }
}
