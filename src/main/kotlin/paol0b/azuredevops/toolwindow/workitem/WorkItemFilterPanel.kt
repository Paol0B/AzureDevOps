package paol0b.azuredevops.toolwindow.workitem

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
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
import paol0b.azuredevops.model.TeamIteration
import paol0b.azuredevops.toolwindow.filters.FilterChipComponent
import paol0b.azuredevops.toolwindow.filters.FilterPopupUtil
import java.awt.*
import java.awt.geom.Ellipse2D
import javax.swing.*

/**
 * Filter panel for Work Items, modeled after [paol0b.azuredevops.toolwindow.filters.PullRequestFilterPanel].
 * Provides:
 * - A search text field at the top
 * - A horizontally scrollable filter bar with: Quick Filter | Type | State | Assigned To | Iteration | Priority | Sort
 */
class WorkItemFilterPanel(
    private val project: Project,
    private val onFilterChanged: (WorkItemSearchValue) -> Unit
) {

    private val panel: JPanel
    private var currentValue = WorkItemSearchValue.DEFAULT

    // Search field
    private val searchField = SearchTextField(false).apply {
        textEditor.emptyText.text = "Search work items"
    }

    // Filter chips
    private val typeChip: FilterChipComponent
    private val stateChip: FilterChipComponent
    private val assignedToChip: FilterChipComponent
    private val iterationChip: FilterChipComponent
    private val priorityChip: FilterChipComponent
    private val sortChip: FilterChipComponent

    // Quick filter button
    private val filterBadgeIcon = WorkItemFilterBadgeIcon(AllIcons.General.Filter)
    private val quickFilterButton: JButton

    // Cached iterations
    private var cachedIterations: List<TeamIteration> = emptyList()

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

        typeChip = FilterChipComponent("Type",
            onShowPopup = { chip -> showTypePopup(chip) },
            onClear = { updateFilter(currentValue.copy(type = null)) }
        )

        stateChip = FilterChipComponent("State",
            onShowPopup = { chip -> showStatePopup(chip) },
            onClear = { updateFilter(currentValue.copy(state = null)) }
        )

        assignedToChip = FilterChipComponent("Assigned",
            onShowPopup = { chip -> showAssignedToPopup(chip) },
            onClear = { updateFilter(currentValue.copy(assignedTo = null)) }
        )
        // Set default
        assignedToChip.setValue(WorkItemSearchValue.AssignedToFilter.ME.displayName)

        iterationChip = FilterChipComponent("Iteration",
            onShowPopup = { chip -> showIterationPopup(chip) },
            onClear = { updateFilter(currentValue.copy(iteration = null)) }
        )

        priorityChip = FilterChipComponent("Priority",
            onShowPopup = { chip -> showPriorityPopup(chip) },
            onClear = { updateFilter(currentValue.copy(priority = null)) }
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

        // Build filter bar
        val filtersContent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.X_AXIS)
            isOpaque = false
            add(typeChip)
            add(Box.createHorizontalStrut(JBUIScale.scale(4)))
            add(stateChip)
            add(Box.createHorizontalStrut(JBUIScale.scale(4)))
            add(assignedToChip)
            add(Box.createHorizontalStrut(JBUIScale.scale(4)))
            add(iterationChip)
            add(Box.createHorizontalStrut(JBUIScale.scale(4)))
            add(priorityChip)
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
    fun getCurrentFilter(): WorkItemSearchValue = currentValue

    fun updateIterations(iterations: List<TeamIteration>) {
        cachedIterations = iterations
    }

    // ---- Popup handlers ----

    private fun showQuickFilterPopup(button: JComponent) {
        val diffCount = diffFromDefaultCount()
        val items = mutableListOf<WorkItemQuickFilterItem>(
            WorkItemQuickFilterItem.Filter(WorkItemQuickFilter.MY_WORK_ITEMS),
            WorkItemQuickFilterItem.Filter(WorkItemQuickFilter.ALL_ACTIVE),
            WorkItemQuickFilterItem.Filter(WorkItemQuickFilter.MY_BUGS),
            WorkItemQuickFilterItem.Filter(WorkItemQuickFilter.CURRENT_SPRINT)
        )
        if (diffCount > 0) items += WorkItemQuickFilterItem.ClearFilters(diffCount)

        val step = object : BaseListPopupStep<WorkItemQuickFilterItem>("Quick Filters", items) {
            override fun getTextFor(value: WorkItemQuickFilterItem): String = when (value) {
                is WorkItemQuickFilterItem.Filter -> "  ${value.filter.displayName}"
                is WorkItemQuickFilterItem.ClearFilters -> "Clear ${value.count} Filters"
            }

            override fun getSeparatorAbove(value: WorkItemQuickFilterItem): ListSeparator? =
                if (value is WorkItemQuickFilterItem.ClearFilters) ListSeparator() else null

            override fun onChosen(selectedValue: WorkItemQuickFilterItem, finalChoice: Boolean): PopupStep<*>? {
                when (selectedValue) {
                    is WorkItemQuickFilterItem.Filter -> when (selectedValue.filter) {
                        WorkItemQuickFilter.MY_WORK_ITEMS -> applyQuickFilter(
                            WorkItemSearchValue(assignedTo = WorkItemSearchValue.AssignedToFilter.ME)
                        )
                        WorkItemQuickFilter.ALL_ACTIVE -> applyQuickFilter(
                            WorkItemSearchValue(state = WorkItemSearchValue.StateFilter.ACTIVE)
                        )
                        WorkItemQuickFilter.MY_BUGS -> applyQuickFilter(
                            WorkItemSearchValue(
                                assignedTo = WorkItemSearchValue.AssignedToFilter.ME,
                                type = WorkItemSearchValue.TypeFilter.BUG
                            )
                        )
                        WorkItemQuickFilter.CURRENT_SPRINT -> {
                            val currentIter = cachedIterations.firstOrNull { it.isCurrent() }
                            applyQuickFilter(
                                WorkItemSearchValue(
                                    assignedTo = WorkItemSearchValue.AssignedToFilter.ME,
                                    iteration = currentIter?.let {
                                        WorkItemSearchValue.IterationFilter(it.path, it.name ?: "", true)
                                    }
                                )
                            )
                        }
                    }
                    is WorkItemQuickFilterItem.ClearFilters -> applyQuickFilter(WorkItemSearchValue.EMPTY)
                }
                return PopupStep.FINAL_CHOICE
            }
        }

        val popup = JBPopupFactory.getInstance().createListPopup(step)
        popup.show(RelativePoint(button, Point(0, button.height + JBUIScale.scale(2))))
    }

    private fun applyQuickFilter(value: WorkItemSearchValue) {
        currentValue = value
        syncChipsFromValue()
        onFilterChanged(currentValue)
        updateBadge()
    }

    private fun showTypePopup(chip: FilterChipComponent) {
        FilterPopupUtil.showSimplePopup(
            component = chip,
            items = WorkItemSearchValue.TypeFilter.entries.toList(),
            presenter = { it.displayName },
            onSelected = { type ->
                chip.setValue(type.displayName)
                updateFilter(currentValue.copy(type = type))
            }
        )
    }

    private fun showStatePopup(chip: FilterChipComponent) {
        FilterPopupUtil.showSimplePopup(
            component = chip,
            items = WorkItemSearchValue.StateFilter.entries.toList(),
            presenter = { it.displayName },
            onSelected = { state ->
                chip.setValue(state.displayName)
                updateFilter(currentValue.copy(state = state))
            }
        )
    }

    private fun showAssignedToPopup(chip: FilterChipComponent) {
        FilterPopupUtil.showSimplePopup(
            component = chip,
            items = WorkItemSearchValue.AssignedToFilter.entries.toList(),
            presenter = { it.displayName },
            onSelected = { assignedTo ->
                chip.setValue(assignedTo.displayName)
                updateFilter(currentValue.copy(assignedTo = assignedTo))
            }
        )
    }

    private fun showIterationPopup(chip: FilterChipComponent) {
        if (cachedIterations.isEmpty()) {
            FilterPopupUtil.showSimplePopup(
                component = chip,
                items = listOf("No iterations available"),
                presenter = { it },
                onSelected = {}
            )
            return
        }

        val iterItems = cachedIterations.map { iter ->
            WorkItemSearchValue.IterationFilter(iter.path, iter.name ?: "", iter.isCurrent())
        }

        FilterPopupUtil.showSimplePopup(
            component = chip,
            items = iterItems,
            presenter = { iter ->
                val prefix = if (iter.isCurrent) "* " else ""
                "$prefix${iter.name}"
            },
            onSelected = { iter ->
                chip.setValue(iter.name)
                updateFilter(currentValue.copy(iteration = iter))
            }
        )
    }

    private fun showPriorityPopup(chip: FilterChipComponent) {
        FilterPopupUtil.showSimplePopup(
            component = chip,
            items = WorkItemSearchValue.PriorityFilter.entries.toList(),
            presenter = { it.displayName },
            onSelected = { priority ->
                chip.setValue(priority.displayName)
                updateFilter(currentValue.copy(priority = priority))
            }
        )
    }

    private fun showSortPopup(chip: FilterChipComponent) {
        FilterPopupUtil.showSimplePopup(
            component = chip,
            items = WorkItemSearchValue.Sort.entries.toList(),
            presenter = { it.displayName },
            onSelected = { sort ->
                chip.setValue(sort.displayName)
                updateFilter(currentValue.copy(sort = sort))
            }
        )
    }

    private fun updateFilter(newValue: WorkItemSearchValue) {
        currentValue = newValue
        onFilterChanged(currentValue)
        updateBadge()
    }

    private fun syncChipsFromValue() {
        val v = currentValue
        if (v.type != null) typeChip.setValue(v.type.displayName) else typeChip.clearValue()
        if (v.state != null) stateChip.setValue(v.state.displayName) else stateChip.clearValue()
        if (v.assignedTo != null) assignedToChip.setValue(v.assignedTo.displayName) else assignedToChip.clearValue()
        if (v.iteration != null) iterationChip.setValue(v.iteration.name) else iterationChip.clearValue()
        if (v.priority != null) priorityChip.setValue(v.priority.displayName) else priorityChip.clearValue()
        if (v.sort != null) sortChip.setValue(v.sort.displayName) else sortChip.clearValue()
        if (v.searchQuery != null) searchField.text = v.searchQuery else searchField.text = ""
    }

    private fun diffFromDefaultCount(): Int {
        val def = WorkItemSearchValue.DEFAULT
        var count = 0
        if (currentValue.type != null) count++
        if (currentValue.state != null) count++
        if (currentValue.assignedTo != def.assignedTo) count++
        if (currentValue.iteration != null) count++
        if (currentValue.priority != null) count++
        if (currentValue.sort != null) count++
        if (currentValue.searchQuery != null) count++
        return count
    }

    private fun updateBadge() {
        filterBadgeIcon.showBadge = diffFromDefaultCount() > 0
        quickFilterButton.repaint()
    }
}

private class WorkItemFilterBadgeIcon(private val base: Icon) : Icon {
    var showBadge: Boolean = false

    override fun getIconWidth(): Int = base.iconWidth
    override fun getIconHeight(): Int = base.iconHeight

    override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
        base.paintIcon(c, g, x, y)
        if (!showBadge) return
        val g2 = g.create() as Graphics2D
        try {
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            val dotSize = JBUIScale.scale(6)
            g2.color = JBColor(Color(71, 136, 227), Color(75, 110, 175))
            g2.fill(Ellipse2D.Double(
                (x + iconWidth - dotSize).toDouble(), y.toDouble(),
                dotSize.toDouble(), dotSize.toDouble()
            ))
        } finally {
            g2.dispose()
        }
    }
}

private sealed class WorkItemQuickFilterItem {
    data class Filter(val filter: WorkItemQuickFilter) : WorkItemQuickFilterItem()
    data class ClearFilters(val count: Int) : WorkItemQuickFilterItem()
}
