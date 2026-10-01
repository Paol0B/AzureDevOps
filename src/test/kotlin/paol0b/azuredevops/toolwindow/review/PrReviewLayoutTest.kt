package paol0b.azuredevops.toolwindow.review

import com.intellij.ide.util.PropertiesComponent
import com.intellij.testFramework.LightPlatformTestCase
import com.intellij.ui.JBSplitter
import com.intellij.util.ui.UIUtil
import javax.swing.JPanel

class PrReviewLayoutTest : LightPlatformTestCase() {
    fun testFileHeightCanChangeAndDividerPositionIsRemembered() {
        val properties = PropertiesComponent.getInstance()
        val key = "AzureDevOps.PRReview.FilesDetails.Proportion"
        properties.unsetValue(key)
        val first = PrReviewLayout(JPanel(), JPanel(), JPanel(), JPanel())
        val second = PrReviewLayout(JPanel(), JPanel(), JPanel(), JPanel())
        try {
            first.addNotify()
            val splitter = UIUtil.findComponentOfType(first, JBSplitter::class.java)!!
            first.setSize(500, 800)
            first.doLayout()
            splitter.doLayout()
            val initialHeight = splitter.firstComponent.height
            splitter.proportion = 0.75f
            splitter.doLayout()
            assertTrue("File area must expand when divider moves", splitter.firstComponent.height > initialHeight)
            second.addNotify()
            val reopened = UIUtil.findComponentOfType(second, JBSplitter::class.java)!!
            assertEquals(0.75f, reopened.proportion)
        } finally {
            first.removeNotify()
            second.removeNotify()
            properties.unsetValue(key)
        }
    }
}
