package paol0b.azuredevops.toolwindow.list

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.DocumentAdapter
import com.intellij.ui.JBColor
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.*
import javax.swing.*
import javax.swing.event.DocumentEvent

/**
 * GitHub-style search/filter panel for the Pull Request list.
 * Inspired by GHPRSearchPanelFactory from the JetBrains GitHub plugin.
 *
 * Layout:
 * ┌────────────────────────────────────────────────────────────────┐
 * │ Quick: [Active] [My PRs] [Assigned] [Review Requested]       │
 * ├────────────────────────────────────────────────────────────────┤
 * │ [🔍 Search...                                               ] │
 * │ [Status ▼] [Author ▼] [Reviewer ▼] [Sort ▼]  [📦 Org] [⟳]   │
 * └────────────────────────────────────────────────────────────────┘
 */
class PullRequestSearchPanel(
    private val searchStateHolder: PullRequestSearchStateHolder,
    private val onRefreshRequested: () -> Unit
) : JPanel() {

    // ── Components ──
    private val searchField = SearchTextField(true).apply {
        textEditor.emptyText.text = "Search pull requests..."
    }

    private val statusCombo = ComboBox(StatusFilter.entries.toTypedArray()).apply {
        selectedItem = StatusFilter.ACTIVE
        renderer = FilterComboRenderer("Status")
        toolTipText = "Filter by PR status"
    }

    private val authorCombo = ComboBox<String>().apply {
        isEditable = false
        renderer = FilterComboRenderer("Author")
        toolTipText = "Filter by author"
    }

    private val reviewerCombo = ComboBox<String>().apply {
        isEditable = false
        renderer = FilterComboRenderer("Reviewer")
        toolTipText = "Filter by reviewer"
    }

    private val sortCombo = ComboBox(SortOrder.entries.toTypedArray()).apply {
        selectedItem = SortOrder.NEWEST
        renderer = FilterComboRenderer("Sort")
        toolTipText = "Sort order"
    }

    private val orgModeButton = JToggleButton().apply {
        icon = AllIcons.Nodes.Module
        toolTipText = "Organization mode: Show PRs from all repos"
        isSelected = true // Default: org mode on
        isFocusPainted = false
        preferredSize = Dimension(JBUI.scale(32), JBUI.scale(28))
    }

    private val refreshButton = JButton().apply {
        icon = AllIcons.Actions.Refresh
        toolTipText = "Refresh pull requests"
        isFocusPainted = false
        isContentAreaFilled = false
        isBorderPainted = false
        preferredSize = Dimension(JBUI.scale(28), JBUI.scale(28))
    }

    // Quick filter buttons
    private val quickFilterButtons = QuickFilter.entries.map { filter ->
        createQuickFilterButton(filter)
    }

    private var activeQuickFilter: QuickFilter? = QuickFilter.OPEN
    private var suppressEvents = false

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = true
        background = UIUtil.getListBackground()
        border = JBUI.Borders.empty(JBUI.scale(8), JBUI.scale(10), JBUI.scale(4), JBUI.scale(10))

        // ── Quick filters row ──
        val quickFilterPanel = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(2), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            val label = JBLabel("Quick filters:").apply {
                foreground = JBColor.GRAY
                font = UIUtil.getLabelFont().deriveFont(UIUtil.getFontSize(UIUtil.FontSize.SMALL))
                border = JBUI.Borders.emptyRight(JBUI.scale(6))
            }
            add(label)
            quickFilterButtons.forEach { add(it) }
        }
        add(quickFilterPanel)
        add(Box.createVerticalStrut(JBUI.scale(6)))

        // ── Search field row ──
        val searchRow = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(30))
            add(searchField, BorderLayout.CENTER)
        }
        add(searchRow)
        add(Box.createVerticalStrut(JBUI.scale(6)))

        // ── Filters row ──
        val filtersRow = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            add(statusCombo)
            add(authorCombo)
            add(reviewerCombo)
            add(sortCombo)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(orgModeButton)
            add(refreshButton)
        }
        add(filtersRow)

        // ── Bottom separator ──
        add(Box.createVerticalStrut(JBUI.scale(4)))
        add(JSeparator(SwingConstants.HORIZONTAL).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 1)
        })

        // ── Wire up listeners ──
        setupListeners()
        updateQuickFilterHighlight()
    }

    private fun setupListeners() {
        // Search text
        searchField.addDocumentListener(object : DocumentAdapter() {
            override fun textChanged(e: DocumentEvent) {
                if (!suppressEvents) {
                    searchStateHolder.updateTextSearch(searchField.text.trim())
                    clearQuickFilterHighlight()
                }
            }
        })

        // Status filter
        statusCombo.addActionListener {
            if (!suppressEvents) {
                val selected = statusCombo.selectedItem as? StatusFilter ?: StatusFilter.ACTIVE
                searchStateHolder.updateStatusFilter(selected)
                clearQuickFilterHighlight()
            }
        }

        // Author filter
        authorCombo.addActionListener {
            if (!suppressEvents) {
                val selected = authorCombo.selectedItem as? String
                searchStateHolder.updateAuthorFilter(if (selected == ALL_AUTHORS) null else selected)
                clearQuickFilterHighlight()
            }
        }

        // Reviewer filter
        reviewerCombo.addActionListener {
            if (!suppressEvents) {
                val selected = reviewerCombo.selectedItem as? String
                searchStateHolder.updateReviewerFilter(if (selected == ALL_REVIEWERS) null else selected)
                clearQuickFilterHighlight()
            }
        }

        // Sort order
        sortCombo.addActionListener {
            if (!suppressEvents) {
                val selected = sortCombo.selectedItem as? SortOrder ?: SortOrder.NEWEST
                searchStateHolder.updateSortOrder(selected)
            }
        }

        // Org mode toggle
        orgModeButton.addActionListener {
            if (!suppressEvents) {
                searchStateHolder.updateOrgMode(orgModeButton.isSelected)
                updateOrgModeButtonText()
            }
        }

        // Refresh
        refreshButton.addActionListener { onRefreshRequested() }
    }

    /**
     * Update the author and reviewer dropdown options based on loaded PRs.
     */
    fun updateFilterOptions(authors: List<String>, reviewers: List<String>) {
        suppressEvents = true
        try {
            val currentAuthor = authorCombo.selectedItem as? String
            val currentReviewer = reviewerCombo.selectedItem as? String

            authorCombo.removeAllItems()
            authorCombo.addItem(ALL_AUTHORS)
            authors.forEach { authorCombo.addItem(it) }
            if (currentAuthor != null && currentAuthor != ALL_AUTHORS) {
                authorCombo.selectedItem = currentAuthor
            } else {
                authorCombo.selectedItem = ALL_AUTHORS
            }

            reviewerCombo.removeAllItems()
            reviewerCombo.addItem(ALL_REVIEWERS)
            reviewers.forEach { reviewerCombo.addItem(it) }
            if (currentReviewer != null && currentReviewer != ALL_REVIEWERS) {
                reviewerCombo.selectedItem = currentReviewer
            } else {
                reviewerCombo.selectedItem = ALL_REVIEWERS
            }
        } finally {
            suppressEvents = false
        }
    }

    /**
     * Update org mode button visual state.
     */
    private fun updateOrgModeButtonText() {
        if (orgModeButton.isSelected) {
            orgModeButton.toolTipText = "✓ Organization mode: Showing PRs from all repos"
        } else {
            orgModeButton.toolTipText = "Organization mode: Show PRs from all repos"
        }
    }

    /**
     * Set the search state from external (e.g., restoring state).
     */
    fun setState(state: PullRequestSearchState) {
        suppressEvents = true
        try {
            searchField.text = state.textSearch
            statusCombo.selectedItem = state.statusFilter
            sortCombo.selectedItem = state.sortOrder
            orgModeButton.isSelected = state.orgMode
            updateOrgModeButtonText()
            if (state.authorFilter != null) {
                authorCombo.selectedItem = state.authorFilter
            } else {
                authorCombo.selectedItem = ALL_AUTHORS
            }
            if (state.reviewerFilter != null) {
                reviewerCombo.selectedItem = state.reviewerFilter
            } else {
                reviewerCombo.selectedItem = ALL_REVIEWERS
            }
        } finally {
            suppressEvents = false
        }
    }

    // ── Quick Filter support ──

    private fun createQuickFilterButton(filter: QuickFilter): JButton {
        return object : JButton(filter.displayName) {
            init {
                isFocusPainted = false
                font = UIUtil.getLabelFont().deriveFont(UIUtil.getFontSize(UIUtil.FontSize.SMALL))
                border = JBUI.Borders.empty(JBUI.scale(2), JBUI.scale(8), JBUI.scale(2), JBUI.scale(8))
                isContentAreaFilled = false
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

                addActionListener {
                    applyQuickFilter(filter)
                }
            }

            override fun paintComponent(g: Graphics) {
                val g2 = g.create() as Graphics2D
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)

                if (activeQuickFilter == filter) {
                    g2.color = JBColor(Color(0, 122, 204, 40), Color(0, 164, 239, 40))
                    g2.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
                    foreground = JBColor(Color(0, 122, 204), Color(0, 164, 239))
                } else if (model.isRollover) {
                    g2.color = JBColor(Color(0, 0, 0, 15), Color(255, 255, 255, 15))
                    g2.fillRoundRect(0, 0, width, height, JBUI.scale(6), JBUI.scale(6))
                    foreground = UIUtil.getLabelForeground()
                } else {
                    foreground = JBColor.GRAY
                }
                g2.dispose()
                super.paintComponent(g)
            }
        }
    }

    private fun applyQuickFilter(filter: QuickFilter) {
        activeQuickFilter = filter
        searchStateHolder.applyQuickFilter(filter)
        suppressEvents = true
        try {
            searchField.text = ""
            statusCombo.selectedItem = StatusFilter.ACTIVE
            authorCombo.selectedItem = ALL_AUTHORS
            reviewerCombo.selectedItem = ALL_REVIEWERS
            sortCombo.selectedItem = SortOrder.NEWEST
        } finally {
            suppressEvents = false
        }
        updateQuickFilterHighlight()
    }

    private fun clearQuickFilterHighlight() {
        activeQuickFilter = null
        updateQuickFilterHighlight()
    }

    private fun updateQuickFilterHighlight() {
        quickFilterButtons.forEach { it.repaint() }
    }

    /**
     * Custom combo box renderer that shows the filter category name as prefix.
     */
    private inner class FilterComboRenderer(private val filterName: String) : DefaultListCellRenderer() {
        override fun getListCellRendererComponent(
            list: JList<*>,
            value: Any?,
            index: Int,
            isSelected: Boolean,
            cellHasFocus: Boolean
        ): Component {
            val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus) as JLabel
            val displayText = when (value) {
                is StatusFilter -> value.displayName
                is SortOrder -> value.displayName
                is String -> if (value == ALL_AUTHORS || value == ALL_REVIEWERS) value else value
                else -> value?.toString() ?: ""
            }

            component.text = if (index == -1) {
                // Selected item display (shows in the combo box itself)
                "$filterName: $displayText"
            } else {
                displayText.toString()
            }
            component.font = UIUtil.getLabelFont().deriveFont(UIUtil.getFontSize(UIUtil.FontSize.SMALL))
            return component
        }
    }

    companion object {
        private const val ALL_AUTHORS = "All Authors"
        private const val ALL_REVIEWERS = "All Reviewers"
    }
}
