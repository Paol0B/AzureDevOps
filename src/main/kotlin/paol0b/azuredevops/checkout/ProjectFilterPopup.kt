package paol0b.azuredevops.checkout

import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.CheckBoxList
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.SearchTextField
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.Timer
import javax.swing.event.DocumentEvent

/**
 * Multi-select popup for picking which Azure DevOps projects should be loaded.
 *
 * Shows a search field, a checkbox list of every project, and Select All / Deselect All
 * helpers. The final selection is delivered via [onApply] when the popup closes, so the
 * caller can debounce repo fetching to one batch per popup interaction (not per click).
 *
 * The popup resizes itself to fit however many items the search filter matches (capped at
 * [MAX_VISIBLE_ROWS]), and search-field typing is debounced so a fast typist doesn't
 * trigger a rebuild on every keystroke.
 */
internal object ProjectFilterPopup {

    private const val MAX_VISIBLE_ROWS = 14
    private const val SEARCH_DEBOUNCE_MS = 200

    fun show(
        anchor: JComponent,
        projects: List<AzureDevOpsCloneApiClient.Project>,
        initiallySelected: Set<String>,
        onApply: (Set<String>) -> Unit
    ) {
        val sortedProjects = projects.sortedBy { it.name.lowercase() }
        val selection = HashSet(initiallySelected)

        val checkBoxList = CheckBoxList<AzureDevOpsCloneApiClient.Project>()
        checkBoxList.setCheckBoxListListener { index, value ->
            val proj = checkBoxList.getItemAt(index) ?: return@setCheckBoxListListener
            if (value) selection.add(proj.id) else selection.remove(proj.id)
        }

        val scrollPane = JBScrollPane(checkBoxList).apply {
            border = JBUI.Borders.empty()
        }

        val popupWidth = JBUIScale.scale(320)

        // The popup itself is created below; we hold the reference so [rebuild] can ask it
        // to re-pack whenever the visible item count changes.
        var currentPopup: com.intellij.openapi.ui.popup.JBPopup? = null

        // Derived from the list's own preferred height after items are added, so the row
        // height we use to size the popup matches whatever the current LAF/DPI is rendering
        // (any constant we pick here tends to clip the last row at non-100% scaling).
        fun computeScrollHeight(visibleCount: Int): Int {
            if (visibleCount == 0) return JBUIScale.scale(40)
            val listHeight = checkBoxList.preferredSize.height
            val rowsToShow = visibleCount.coerceAtMost(MAX_VISIBLE_ROWS)
            val perRow = (listHeight + visibleCount - 1) / visibleCount  // ceil per-row height
            return rowsToShow * perRow + JBUIScale.scale(8)              // scroll-pane chrome
        }

        fun rebuild(filter: String) {
            checkBoxList.clear()
            val q = filter.trim().lowercase()
            val visible = sortedProjects.filter { q.isEmpty() || it.name.lowercase().contains(q) }
            visible.forEach { proj ->
                checkBoxList.addItem(proj, proj.name, selection.contains(proj.id))
            }
            scrollPane.preferredSize = Dimension(popupWidth, computeScrollHeight(visible.size))
            currentPopup?.pack(false, true)
        }
        rebuild("")

        val searchField = SearchTextField(false).apply {
            textEditor.emptyText.text = "Search projects"
            textEditor.border = JBUI.Borders.empty(4)
        }
        val debounceTimer = Timer(SEARCH_DEBOUNCE_MS) { rebuild(searchField.text) }.apply {
            isRepeats = false
        }
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                debounceTimer.restart()
            }
        })

        val selectAll = JButton("Select All").apply {
            addActionListener {
                selection.clear()
                selection.addAll(sortedProjects.map { it.id })
                rebuild(searchField.text)
            }
        }
        val deselectAll = JButton("Deselect All").apply {
            addActionListener {
                selection.clear()
                rebuild(searchField.text)
            }
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUIScale.scale(4), 0)).apply {
            border = JBUI.Borders.empty(4)
            add(selectAll)
            add(deselectAll)
        }

        val content = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(buttons, BorderLayout.SOUTH)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, searchField.textEditor)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(false)
            .setResizable(true)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    debounceTimer.stop()
                    onApply(selection.toSet())
                }
            })
            .createPopup()
        currentPopup = popup

        val pt = RelativePoint(anchor, Point(0, anchor.height + JBUIScale.scale(2)))
        popup.show(pt)
    }

    /**
     * Helper for the trigger button label, e.g. "All projects", "5 of 27 projects", "No projects".
     */
    fun label(selectedCount: Int, totalCount: Int): String = when {
        totalCount == 0 -> "No projects"
        selectedCount == 0 -> "No projects selected"
        selectedCount >= totalCount -> "All $totalCount projects"
        else -> "$selectedCount of $totalCount projects"
    }
}
