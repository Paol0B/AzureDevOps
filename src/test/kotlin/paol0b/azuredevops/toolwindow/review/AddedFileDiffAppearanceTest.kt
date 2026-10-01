package paol0b.azuredevops.toolwindow.review

import com.intellij.diff.util.TextDiffType
import com.intellij.openapi.diff.DiffColors
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.testFramework.LightPlatformTestCase
import paol0b.azuredevops.model.PullRequestChange

class AddedFileDiffAppearanceTest : LightPlatformTestCase() {
    fun testAddedFileUsesNormalBackgroundWithoutChangingGlobalDiffColors() {
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(factory.createDocument("added code"), project) as EditorEx
        val global = EditorColorsManager.getInstance().globalScheme
        val original = global.getAttributes(DiffColors.DIFF_INSERTED).clone()
        try {
            AddedFileDiffAppearance.apply(editor, PullRequestChange(1, 1, "add, edit", null, "/new.ts"))
            assertEquals(editor.colorsScheme.defaultBackground, TextDiffType.INSERTED.getColor(editor))
            assertEquals(editor.colorsScheme.defaultBackground, TextDiffType.INSERTED.getIgnoredColor(editor))
            assertEquals(original, global.getAttributes(DiffColors.DIFF_INSERTED))
            assertEquals(original.errorStripeColor, TextDiffType.INSERTED.getMarkerColor(editor))
        } finally { factory.releaseEditor(editor) }
    }

    fun testModifiedFileKeepsNormalDiffHighlighting() {
        val factory = EditorFactory.getInstance()
        val editor = factory.createEditor(factory.createDocument("modified code"), project) as EditorEx
        try {
            val scheme = editor.colorsScheme
            val original = TextDiffType.INSERTED.getColor(editor)
            AddedFileDiffAppearance.apply(editor, PullRequestChange(1, 1, "edit", null, "/existing.ts"))
            assertSame(scheme, editor.colorsScheme)
            assertEquals(original, TextDiffType.INSERTED.getColor(editor))
        } finally { factory.releaseEditor(editor) }
    }
}
