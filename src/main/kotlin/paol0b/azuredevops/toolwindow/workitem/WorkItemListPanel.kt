package paol0b.azuredevops.toolwindow.workitem

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.JBPopupMenu
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.actions.ChangeWorkItemStateAction
import paol0b.azuredevops.actions.CreateBranchFromWorkItemAction
import paol0b.azuredevops.model.TeamIteration
import paol0b.azuredevops.model.WorkItem
import paol0b.azuredevops.services.AvatarService
import paol0b.azuredevops.services.AzureDevOpsApiClient
import com.intellij.ide.BrowserUtil
import java.awt.*
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*

/**
 * Panel that shows the list of Work Items using a JBList with
 * GitHub-style two-line cell rendering and a filter bar.
 * Mirrors the pattern of [paol0b.azuredevops.toolwindow.PullRequestListPanel].
 */
class WorkItemListPanel(
    private val project: Project
) {

    private val logger = Logger.getInstance(WorkItemListPanel::class.java)
    private val panel: JPanel
    private val listModel: DefaultListModel<WorkItem>
    private val workItemList: JBList<WorkItem>
    private val statusLabel: JLabel
    private val filterPanel: WorkItemFilterPanel

    private var lastSelectedId: Int? = null
    private var lastLoadedWorkItems: List<WorkItem> = emptyList()
    private var lastLoadedHash: String = ""
    private var isErrorState: Boolean = false

    // Filter state
    private var currentSearchValue = WorkItemSearchValue.DEFAULT

    // Auto-refresh
    private var refreshTimer: Timer? = null
    private val REFRESH_INTERVAL = 30_000

    init {
        listModel = DefaultListModel()

        val avatarService = AvatarService.getInstance(project)
        val cellRenderer = WorkItemListCellRenderer(avatarService)

        workItemList = JBList(listModel).apply {
            this.cellRenderer = cellRenderer
            selectionMode = ListSelectionModel.SINGLE_SELECTION
            border = JBUI.Borders.empty()
        }

        workItemList.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                lastSelectedId = workItemList.selectedValue?.id
            }
        }

        workItemList.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) showContextMenu(e)
            }
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val wi = workItemList.selectedValue ?: return
                    WorkItemToolWindowFactory.openWorkItemDetailTab(project, wi)
                }
            }
        })

        statusLabel = JLabel("Ready").apply {
            border = JBUI.Borders.empty(8, 12)
            font = font.deriveFont(Font.PLAIN, 11f)
            foreground = JBColor.GRAY
        }

        // Filter panel (search + chips)
        filterPanel = WorkItemFilterPanel(project) { newFilter ->
            onFilterChanged(newFilter)
        }

        val scrollPane = JBScrollPane(workItemList).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 16
        }

        panel = JPanel(BorderLayout()).apply {
            add(filterPanel.getComponent(), BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
            add(statusLabel, BorderLayout.SOUTH)
            minimumSize = Dimension(250, 0)
        }
    }

    fun getComponent(): JPanel = panel

    fun updateIterations(iterations: List<TeamIteration>) {
        filterPanel.updateIterations(iterations)
    }

    /**
     * Called when the filter panel reports a new filter value.
     */
    private fun onFilterChanged(newValue: WorkItemSearchValue) {
        val needsServerRefresh = currentSearchValue.assignedTo != newValue.assignedTo ||
                currentSearchValue.state != newValue.state ||
                currentSearchValue.type != newValue.type ||
                currentSearchValue.iteration != newValue.iteration ||
                currentSearchValue.priority != newValue.priority
        currentSearchValue = newValue

        if (needsServerRefresh) {
            refreshWorkItems()
        } else {
            applyClientFilters()
        }
    }

    fun refreshWorkItems() {
        statusLabel.text = "Loading work items..."
        statusLabel.icon = AllIcons.Process.Step_1

        val selectedId = getSelectedWorkItem()?.id ?: lastSelectedId
        val sv = currentSearchValue

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Loading Work Items...", false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val apiClient = AzureDevOpsApiClient.getInstance(project)

                    val useMe = sv.assignedTo == null || sv.assignedTo == WorkItemSearchValue.AssignedToFilter.ME
                    val stateFilter = if (sv.state == WorkItemSearchValue.StateFilter.ALL) null else sv.state?.displayName
                    val typeFilter = sv.type?.displayName
                    val iterationPath = sv.iteration?.path

                    val workItems = if (useMe) {
                        apiClient.getMyWorkItems(
                            iterationPath = iterationPath,
                            state = stateFilter,
                            type = typeFilter
                        )
                    } else {
                        apiClient.getAllWorkItems(
                            iterationPath = iterationPath,
                            state = stateFilter,
                            type = typeFilter
                        )
                    }

                    // Also sort by priority if filter requests it
                    val priorityFiltered = if (sv.priority != null) {
                        workItems.filter { it.getPriority() == sv.priority.value }
                    } else workItems

                    ApplicationManager.getApplication().invokeLater {
                        val newHash = computeHash(priorityFiltered)
                        if (!isErrorState && newHash == lastLoadedHash && lastLoadedWorkItems.isNotEmpty()) {
                            updateStatusLabel(priorityFiltered.size, priorityFiltered.size, upToDate = true)
                            return@invokeLater
                        }
                        lastLoadedWorkItems = priorityFiltered
                        lastLoadedHash = newHash
                        val filtered = applyAllFilters(priorityFiltered)
                        updateList(filtered, selectedId)
                        updateStatusLabel(filtered.size, priorityFiltered.size)
                        isErrorState = false
                    }
                } catch (e: Exception) {
                    ApplicationManager.getApplication().invokeLater {
                        isErrorState = true
                        listModel.clear()
                        val isConfigError = e.message?.contains("not configured", ignoreCase = true) == true ||
                                e.message?.contains("Authentication required", ignoreCase = true) == true
                        if (isConfigError) {
                            statusLabel.text = "Azure DevOps not configured"
                            statusLabel.icon = AllIcons.General.Warning
                        } else {
                            statusLabel.text = "Error: ${e.message?.take(80)}"
                            statusLabel.icon = AllIcons.General.Error
                        }
                    }
                }
            }
        })
    }

    fun getSelectedWorkItem(): WorkItem? = workItemList.selectedValue

    // ---- Client-side filtering ----

    private fun applyClientFilters() {
        if (isErrorState) return
        val selectedId = getSelectedWorkItem()?.id ?: lastSelectedId
        val filtered = applyAllFilters(lastLoadedWorkItems)
        updateList(filtered, selectedId)
        updateStatusLabel(filtered.size, lastLoadedWorkItems.size)
    }

    private fun applyAllFilters(workItems: List<WorkItem>): List<WorkItem> {
        var result = workItems
        val sv = currentSearchValue

        // Text search
        val query = sv.searchQuery
        if (!query.isNullOrBlank()) {
            val normalizedQuery = query.lowercase()
            result = result.filter { wi ->
                wi.getTitle().lowercase().contains(normalizedQuery) ||
                wi.id.toString().contains(normalizedQuery) ||
                (wi.getAssignedTo()?.lowercase()?.contains(normalizedQuery) == true) ||
                (wi.getTags()?.lowercase()?.contains(normalizedQuery) == true)
            }
        }

        // Sort
        result = when (sv.sort) {
            WorkItemSearchValue.Sort.OLDEST -> result.sortedBy { it.id }
            WorkItemSearchValue.Sort.PRIORITY -> result.sortedBy { it.getPriority() ?: 99 }
            else -> result // default: server order (recently changed)
        }

        return result
    }

    // ---- List management ----

    private fun updateList(workItems: List<WorkItem>, previouslySelectedId: Int? = null) {
        listModel.clear()
        workItems.forEach { listModel.addElement(it) }

        if (previouslySelectedId != null) {
            for (i in 0 until listModel.size) {
                if (listModel.getElementAt(i).id == previouslySelectedId) {
                    workItemList.selectedIndex = i
                    workItemList.ensureIndexIsVisible(i)
                    break
                }
            }
        }
    }

    private fun showContextMenu(e: MouseEvent) {
        val index = workItemList.locationToIndex(e.point)
        if (index < 0) return
        val cellBounds = workItemList.getCellBounds(index, index) ?: return
        if (!cellBounds.contains(e.point)) return
        val workItem = listModel.getElementAt(index)
        workItemList.selectedIndex = index

        val popup = JBPopupMenu()

        popup.add(JMenuItem("Open Details").apply {
            addActionListener { WorkItemToolWindowFactory.openWorkItemDetailTab(project, workItem) }
        })

        popup.addSeparator()

        popup.add(JMenuItem("Change State...").apply {
            icon = AllIcons.Actions.SwapPanels
            addActionListener {
                ChangeWorkItemStateAction.showStatePopup(project, workItem, workItemList) {
                    refreshWorkItems()
                }
            }
        })

        popup.add(JMenuItem("Create Branch...").apply {
            icon = AllIcons.Vcs.Branch
            addActionListener {
                CreateBranchFromWorkItemAction.execute(project, workItem) {
                    refreshWorkItems()
                }
            }
        })

        val webUrl = workItem.getWebUrl()
        if (webUrl.isNotBlank()) {
            popup.addSeparator()
            popup.add(JMenuItem("Open in Browser").apply {
                icon = AllIcons.Ide.External_link_arrow
                addActionListener { BrowserUtil.browse(java.net.URI(webUrl)) }
            })
        }

        popup.show(workItemList, e.x, e.y)
    }

    private fun computeHash(workItems: List<WorkItem>): String {
        if (workItems.isEmpty()) return ""
        val sb = StringBuilder()
        for (wi in workItems) {
            sb.append(wi.id).append(':')
            sb.append(wi.rev?.toString() ?: "0").append(':')
            sb.append(wi.getState()).append('|')
        }
        return sb.toString().hashCode().toString()
    }

    private fun updateStatusLabel(filteredCount: Int, totalCount: Int, upToDate: Boolean = false) {
        statusLabel.icon = AllIcons.General.InspectionsOK
        statusLabel.text = when {
            upToDate -> "$totalCount work item(s) — up to date"
            filteredCount < totalCount -> "Showing $filteredCount of $totalCount work item(s)"
            else -> "Loaded $totalCount work item(s)"
        }
    }

    // ---- Auto-refresh ----

    fun startAutoRefresh() {
        if (refreshTimer != null) return
        refreshTimer = Timer(REFRESH_INTERVAL) {
            refreshWorkItems()
        }.apply {
            isRepeats = true
            start()
        }
    }

    fun stopAutoRefresh() {
        refreshTimer?.stop()
        refreshTimer = null
    }
}
