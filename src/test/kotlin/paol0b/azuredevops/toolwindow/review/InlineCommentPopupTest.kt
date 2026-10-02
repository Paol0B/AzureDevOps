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
            assertTrue("Escape remains an explicit way to close the comment editor", popup.isCancelKeyEnabled)
        } finally { Disposer.dispose(popup) }
    }
}
