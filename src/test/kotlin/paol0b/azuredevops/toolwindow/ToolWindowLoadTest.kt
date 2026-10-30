package paol0b.azuredevops.toolwindow

import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.ui.content.ContentFactory
import javax.swing.JPanel

class ToolWindowLoadTest : LightPlatformTestCase() {
    private val factory get() = ContentFactory.getInstance()

    fun testFirstContentLoadsWhenAdded() {
        val manager = factory.createContentManager(true, project)
        val list = factory.createContent(JPanel(), "List", false)
        var loads = 0

        loadWhenSelected(project, manager, list) { loads++ }
        manager.addContent(list)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(1, loads)
    }

    fun testReselectingContentLoadsAgain() {
        val manager = factory.createContentManager(true, project)
        val list = factory.createContent(JPanel(), "List", false)
        val other = factory.createContent(JPanel(), "Other", false)
        var loads = 0

        loadWhenSelected(project, manager, list) { loads++ }
        manager.addContent(list)
        manager.addContent(other)
        manager.setSelectedContent(other)
        manager.setSelectedContent(list)
        PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()

        assertEquals(2, loads)
    }
}
