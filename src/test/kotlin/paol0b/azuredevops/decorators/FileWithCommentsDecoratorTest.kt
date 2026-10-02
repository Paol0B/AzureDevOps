package paol0b.azuredevops.decorators

import com.intellij.ide.projectView.*
import com.intellij.ide.util.treeView.AbstractTreeNode
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.SimpleTextAttributes
import paol0b.azuredevops.model.CommentThread
import paol0b.azuredevops.model.ThreadStatus
import paol0b.azuredevops.services.PullRequestCommentsTracker

class FileWithCommentsDecoratorTest : LightPlatformTestCase() {
    override fun tearDown() {
        try { PullRequestCommentsTracker.getInstance(project).clearAllComments() } finally { super.tearDown() }
    }
    private fun node(file: VirtualFile) = object : ProjectViewNode<VirtualFile>(project, file, ViewSettings.DEFAULT) {
        override fun getVirtualFile() = file
        override fun contains(candidate: VirtualFile) = file == candidate
        override fun getChildren(): Collection<AbstractTreeNode<*>> = emptyList()
        override fun update(data: PresentationData) {}
    }
    private fun addComments(file: VirtualFile) {
        PullRequestCommentsTracker.getInstance(project).setCommentsForFile(file, listOf(
            CommentThread(1, null, emptyList(), ThreadStatus.Active, null, false),
            CommentThread(2, null, emptyList(), ThreadStatus.Active, null, false)))
    }
    fun testFileNameRemainsBeforeBadgeWhenNameUsesPlainPresentation() {
        val file = LightVirtualFile("file.ts")
        addComments(file)
        val data = PresentationData().apply { presentableText = "file.ts" }
        FileWithCommentsDecorator(project).decorate(node(file), data)
        assertEquals("file.ts (2)", data.coloredText.joinToString("") { it.text })
    }
    fun testFolderDisplayNameRemainsBeforeBadge() {
        val file = LightVirtualFile("file.ts")
        addComments(file)
        val directory = object : LightVirtualFile("components") {
            override fun isDirectory() = true
            override fun getChildren(): Array<VirtualFile> = arrayOf(file)
        }
        val data = PresentationData().apply { presentableText = "components/src" }
        FileWithCommentsDecorator(project).decorate(node(directory), data)
        assertEquals("components/src (2)", data.coloredText.joinToString("") { it.text })
    }
    fun testExistingColoredNameAndAttributesArePreserved() {
        val file = LightVirtualFile("file.ts")
        addComments(file)
        val data = PresentationData().apply {
            presentableText = "file.ts"
            addText("custom.ts", SimpleTextAttributes.ERROR_ATTRIBUTES)
        }
        FileWithCommentsDecorator(project).decorate(node(file), data)
        assertEquals("custom.ts (2)", data.coloredText.joinToString("") { it.text })
        assertEquals(SimpleTextAttributes.ERROR_ATTRIBUTES, data.coloredText.first().attributes)
    }
}
