package paol0b.azuredevops.toolwindow.review

import com.intellij.ui.JBSplitter
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel

/** Keeps PR actions visible while files and review details resize independently. */
internal class PrReviewLayout(
    header: JComponent,
    files: JComponent,
    details: JComponent,
    vote: JComponent
) : JPanel(BorderLayout()) {
    init {
        add(header, BorderLayout.NORTH)
        add(JBSplitter(true, "AzureDevOps.PRReview.FilesDetails.Proportion", 0.6f).apply {
            firstComponent = files
            secondComponent = details
            dividerWidth = 6
        }, BorderLayout.CENTER)
        add(vote, BorderLayout.SOUTH)
    }
}
