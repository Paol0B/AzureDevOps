package paol0b.azuredevops.actions

import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.ui.TestDialog
import com.intellij.openapi.ui.TestDialogManager
import com.intellij.openapi.util.io.FileUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.util.ui.UIUtil
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AddEditorPullRequestCommentActionTest : LightPlatformTestCase() {
    fun testRepositoryFailureIsReportedAfterActionReturnsAndOnUiThread() {
        val directory = FileUtil.createTempDirectory("pr-comment-action", "", true)
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(factory.createDocument("code"), project)
        val reported = AtomicBoolean()
        val reportedOnUiThread = AtomicBoolean()
        val oldDialog = TestDialogManager.setTestDialog(TestDialog {
            reportedOnUiThread.set(ApplicationManager.getApplication().isDispatchThread)
            reported.set(true)
            0
        })
        try {
            val path = File(directory, "source.txt").apply { writeText("code") }
            val file = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(path)!!
            val context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(CommonDataKeys.EDITOR, editor)
                .add(CommonDataKeys.VIRTUAL_FILE, file).build()
            val action = AddEditorPullRequestCommentAction()
            action.actionPerformed(AnActionEvent.createFromAnAction(action, null, ActionPlaces.EDITOR_POPUP, context))
            assertFalse("Repository resolution must not block the UI action", reported.get())
            val deadline = System.nanoTime() + 5_000_000_000L
            while (!reported.get() && System.nanoTime() < deadline) {
                UIUtil.dispatchAllInvocationEvents()
                Thread.sleep(5)
            }
            assertTrue("Missing repository must still be reported", reported.get())
            assertTrue("Error dialogs must be shown on the UI thread", reportedOnUiThread.get())
        } finally {
            TestDialogManager.setTestDialog(oldDialog)
            factory.releaseEditor(editor)
            FileUtil.delete(directory)
        }
    }
}
