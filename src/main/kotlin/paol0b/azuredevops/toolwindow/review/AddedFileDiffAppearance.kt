package paol0b.azuredevops.toolwindow.review

import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.ex.EditorEx
import com.intellij.openapi.diff.DiffColors
import paol0b.azuredevops.model.PullRequestChange
import paol0b.azuredevops.model.hasChangeType

internal object AddedFileDiffAppearance {
    fun apply(editor: Editor, change: PullRequestChange?) {
        if (change?.hasChangeType("add") != true || editor !is EditorEx) return
        val scheme = editor.colorsScheme
        val background = scheme.defaultBackground
        val inserted = scheme.getAttributes(DiffColors.DIFF_INSERTED)
        if (inserted.backgroundColor == background && inserted.foregroundColor == background) return
        // Diff foreground is its ignored-change fill, not the source code's syntax color.
        val attributes = inserted.clone().apply {
            backgroundColor = background
            foregroundColor = background
        }
        val localScheme = editor.createBoundColorSchemeDelegate(scheme)
        localScheme.setAttributes(DiffColors.DIFF_INSERTED, attributes)
        editor.colorsScheme = localScheme
    }
}
