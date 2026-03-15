package paol0b.azuredevops.toolwindow.pipeline

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.SearchTextField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBThinOverlappingScrollBar
import com.intellij.ui.scale.JBUIScale
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListSeparator
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.awt.RelativePoint
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.BuildDefinition
import paol0b.azuredevops.toolwindow.filters.FilterBadgeIcon
import paol0b.azuredevops.toolwindow.filters.FilterChipComponent
import paol0b.azuredevops.toolwindow.filters.FilterPopupUtil
import java.awt.*
import javax.swing.*

/**
 * Filter panel for Pipelines, using the same [FilterChipComponent] and [FilterPopupUtil]
 * as the PR filter panel. Provides:
 * - A search text field at the top
 * - A horizontally scrollable filter bar with: Quick Filter | Result | Pipeline | Branch | Requested By | Sort
 */
class PipelineFilterPanel(
    private val project: Project,
    private val onFilterChanged: (PipelineSearchValue) -> Unit
) {

    private val panel: JPanel
    private var currentValue = PipelineSearchValue.DEFAULT

    // Search field
    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search pipelines"
    }

    // Filter chips
    private val resultChip: FilterChipComponent
    private val definitionChip: FilterChipComponent
    private val branchChip: FilterChipComponent
    private val requestedByChip: FilterChipComponent
    private val sortChip: FilterChipComponent

    // Quick filter button
    private val filterBadgeIcon = FilterBadgeIcon(AllIcons.General.Filter)
    private val quickFilterButton: JButton

    // Cached definitions (populated externally)
    private var cachedDefinitions: List<BuildDefinition> = emptyList()

    init {
        quickFilterButton = JButton().apply {
            icon = filterBadgeIcon
            toolTipText = "Quick Filters"
            isFocusPainted = false
            isContentAreaFilled = false
            border = JBUI.Borders.empty(2, 4)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
            preferredSize = Dimension(JBUIScale.scale(28), JBUIScale.scale(24))
            addActionListener { showQuickFilterPopup(this) }
        }

        resultChip = FilterChipComponent("Result",
            onShowPopup = { chip -> showResultPopup(chip) },
            onClear = { updateFilter(currentValue.copy(result = null)) }
        )

        definitionChip = FilterChipComponent("Pipeline",
            onShowPopup = { chip -> showDefinitionPopup(chip) },
            onClear = { updateFilter(currentValue.copy(definition = null)) }
        )

        branchChip = FilterChipComponent("Branch",
            onShowPopup = { chip -> showBranchPopup(chip) },
            onClear = { updateFilter(currentValue.copy(branch = null)) }
        )

        requestedByChip = FilterChipComponent("Requested by",
            onShowPopup = { chip -> showRequestedByPopup(chip) },
            onClear = { updateFilter(currentValue.copy(requestedBy = null)) }
        )

        sortChip = FilterChipComponent("Sort",
            onShowPopup = { chip -> showSortPopup(chip) },
            onClear = { updateFilter(currentValue.copy(sort = null)) }
        )

        // Search field listener
        searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                val query = searchField.text.trim()
                updateFilter(currentValue.copy(searchQuery = query.ifBlank { null }))
            }
        })

        // Build the filter bar
        val filtersContent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(resultChip)
            add(Box.createHorizontalStrut(JBUIScale.scale(4)))
            add(definitionChip)
            add(Box.createHorizontalStrut(JBUIScale.scale(4)))
            add(branchChip)
            add(Box.createHorizontalStrut(JBUIScale.scale(4)))
            add(requestedByChip)
            add(Box.createHorizontalStrut(JBUIScale.scale(4)))
            add(sortChip)
        }

        val filtersScrollPane = JBScrollPane(filtersContent).apply {
            border = JBUI.Borders.empty()
            isOpaque = false
            viewport.isOpaque = false
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            horizontalScrollBar = JBThinOverlappingScrollBar(Adjustable.HORIZONTAL)
        }

        val filterBar = JPanel(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(4)
            add(quickFilterButton, BorderLayout.WEST)
            add(filtersScrollPane, BorderLayout.CENTER)
        }

        panel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.empty(8, 10, 4, 10)
            isOpaque = false
            add(searchField, BorderLayout.CENTER)
            add(filterBar, BorderLayout.SOUTH)
        }
    }

    fun getComponent(): JPanel = panel
    fun getCurrentFilter(): PipelineSearchValue = currentValue

    /** Called by PipelineToolWindow when definitions are loaded. */
    fun updateDefinitions(definitions: List<BuildDefinition>) {
        cachedDefinitions = definitions
    }

    // ---- Quick Filter Popup ----

    private fun showQuickFilterPopup(button: JComponent) {
        val diffCount = currentValue.filterCount
        val items = mutableListOf<PipelineQuickFilterItem>(
            PipelineQuickFilterItem.Filter(PipelineQuickFilter.ALL_RUNS),
            PipelineQuickFilterItem.Filter(PipelineQuickFilter.MY_RUNS),
            PipelineQuickFilterItem.Filter(PipelineQuickFilter.FAILED_RUNS),
            PipelineQuickFilterItem.Filter(PipelineQuickFilter.RUNNING)
        )
        if (diffCount > 0) items += PipelineQuickFilterItem.ClearFilters(diffCount)

        val step = object : BaseListPopupStep<PipelineQuickFilterItem>("Quick Filters", items) {
            override fun getTextFor(value: PipelineQuickFilterItem): String = when (value) {
                is PipelineQuickFilterItem.Filter -> "  ${value.filter.displayName}"
                is PipelineQuickFilterItem.ClearFilters -> "Clear ${value.count} Filters"
            }

            override fun getSeparatorAbove(value: PipelineQuickFilterItem): ListSeparator? =
                if (value is PipelineQuickFilterItem.ClearFilters) ListSeparator() else null

            override fun onChosen(selectedValue: PipelineQuickFilterItem, finalChoice: Boolean): PopupStep<*>? {
                when (selectedValue) {
                    is PipelineQuickFilterItem.Filter -> when (selectedValue.filter) {
                        PipelineQuickFilter.ALL_RUNS -> applyQuickFilter(PipelineSearchValue())
                        PipelineQuickFilter.MY_RUNS -> applyQuickFilter(
                            PipelineSearchValue(requestedBy = PipelineSearchValue.RequestedByFilter.ME)
                        )
                        PipelineQuickFilter.FAILED_RUNS -> applyQuickFilter(
                            PipelineSearchValue(result = PipelineSearchValue.ResultFilter.FAILED)
                        )
                        PipelineQuickFilter.RUNNING -> applyQuickFilter(
                            PipelineSearchValue(result = PipelineSearchValue.ResultFilter.IN_PROGRESS)
                        )
                    }
                    is PipelineQuickFilterItem.ClearFilters -> applyQuickFilter(PipelineSearchValue.EMPTY)
                }
                return PopupStep.FINAL_CHOICE
            }
        }

        val popup = JBPopupFactory.getInstance().createListPopup(step)
        popup.show(RelativePoint(button, Point(0, button.height + JBUIScale.scale(2))))
    }

    private fun applyQuickFilter(value: PipelineSearchValue) {
        currentValue = value
        syncChipsFromValue()
        onFilterChanged(currentValue)
        updateBadge()
    }

    // ---- Filter Popups ----

    private fun showResultPopup(chip: FilterChipComponent) {
        FilterPopupUtil.showSimplePopup(
            component = chip,
            items = PipelineSearchValue.ResultFilter.entries.toList(),
            presenter = { it.displayName },
            onSelected = { result ->
                chip.setValue(result.displayName)
                updateFilter(currentValue.copy(result = result))
            }
        )
    }

    private fun showDefinitionPopup(chip: FilterChipComponent) {
        if (cachedDefinitions.isEmpty()) {
            FilterPopupUtil.showSimplePopup(
                component = chip,
                items = listOf("No pipelines available"),
                presenter = { it },
                onSelected = {}
            )
            return
        }

        val defFilters = cachedDefinitions.map {
            PipelineSearchValue.DefinitionFilter(it.id ?: 0, it.getDisplayName())
        }

        FilterPopupUtil.showSearchablePopup(
            component = chip,
            items = defFilters,
            presenter = { it.name },
            icon = AllIcons.Actions.Execute,
            onSelected = { def ->
                chip.setValue(def.name)
                updateFilter(currentValue.copy(definition = def))
            }
        )
    }

    private fun showBranchPopup(chip: FilterChipComponent) {
        // Show a text input popup for branch name
        val input = JTextField().apply {
            text = currentValue.branch ?: ""
            columns = 20
        }
        val content = JPanel(BorderLayout(0, 4)).apply {
            border = JBUI.Borders.empty(8)
            add(javax.swing.JLabel("Branch name:"), BorderLayout.NORTH)
            add(input, BorderLayout.CENTER)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, input)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(false)
            .createPopup()

        input.addActionListener {
            val branch = input.text.trim().ifBlank { null }
            if (branch != null) {
                chip.setValue(branch)
            } else {
                chip.clearValue()
            }
            updateFilter(currentValue.copy(branch = branch))
            popup.closeOk(null)
        }

        popup.show(RelativePoint(chip, Point(0, chip.height + JBUIScale.scale(2))))
    }

    private fun showRequestedByPopup(chip: FilterChipComponent) {
        FilterPopupUtil.showSimplePopup(
            component = chip,
            items = PipelineSearchValue.RequestedByFilter.entries.toList(),
            presenter = { it.displayName },
            onSelected = { requestedBy ->
                chip.setValue(requestedBy.displayName)
                updateFilter(currentValue.copy(requestedBy = requestedBy))
            }
        )
    }

    private fun showSortPopup(chip: FilterChipComponent) {
        FilterPopupUtil.showSimplePopup(
            component = chip,
            items = PipelineSearchValue.Sort.entries.toList(),
            presenter = { it.displayName },
            onSelected = { sort ->
                chip.setValue(sort.displayName)
                updateFilter(currentValue.copy(sort = sort))
            }
        )
    }

    // ---- State management ----

    private fun updateFilter(newValue: PipelineSearchValue) {
        currentValue = newValue
        onFilterChanged(currentValue)
        updateBadge()
    }

    private fun syncChipsFromValue() {
        val v = currentValue
        if (v.result != null) resultChip.setValue(v.result.displayName) else resultChip.clearValue()
        if (v.definition != null) definitionChip.setValue(v.definition.name) else definitionChip.clearValue()
        if (v.branch != null) branchChip.setValue(v.branch) else branchChip.clearValue()
        if (v.requestedBy != null) requestedByChip.setValue(v.requestedBy.displayName) else requestedByChip.clearValue()
        if (v.sort != null) sortChip.setValue(v.sort.displayName) else sortChip.clearValue()
        if (v.searchQuery != null) searchField.text = v.searchQuery else searchField.text = ""
    }

    private fun updateBadge() {
        filterBadgeIcon.showBadge = currentValue.filterCount > 0
        quickFilterButton.repaint()
    }
}

private sealed class PipelineQuickFilterItem {
    data class Filter(val filter: PipelineQuickFilter) : PipelineQuickFilterItem()
    data class ClearFilters(val count: Int) : PipelineQuickFilterItem()
}
