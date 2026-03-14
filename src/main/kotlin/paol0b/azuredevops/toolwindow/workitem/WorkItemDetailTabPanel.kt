package paol0b.azuredevops.toolwindow.workitem

import com.intellij.icons.AllIcons
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.WorkItem
import paol0b.azuredevops.services.AvatarService
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.*
import javax.swing.*

/**
 * Detail view for a single Work Item.
 * Shows all fields, description (HTML), actions, and discussion.
 * Mirrors [paol0b.azuredevops.toolwindow.pipeline.PipelineDetailTabPanel].
 */
class WorkItemDetailTabPanel(
    private val project: Project,
    @Volatile
    private var workItem: WorkItem
) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(WorkItemDetailTabPanel::class.java)
    private val apiClient = AzureDevOpsApiClient.getInstance(project)
    private val avatarService = AvatarService.getInstance(project)

    // Header
    private val titleLabel = JBLabel().apply {
        font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 16f)
    }
    private val typeIdLabel = JBLabel()
    private val stateLabel = JBLabel()

    // Info fields
    private val assignedToLabel = JBLabel()
    private val assignedToAvatar = JBLabel()
    private val iterationLabel = JBLabel()
    private val areaLabel = JBLabel()
    private val priorityLabel = JBLabel()
    private val storyPointsLabel = JBLabel()
    private val tagsLabel = JBLabel()
    private val createdLabel = JBLabel()
    private val changedLabel = JBLabel()
    private val reasonLabel = JBLabel()

    // Description
    private val descriptionPane = JEditorPane("text/html", "").apply {
        isEditable = false
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(8)
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true)
        font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 12f)
    }

    // Comments
    private val commentPanel = WorkItemCommentPanel(project, workItem.id)

    // Auto-refresh
    private var refreshTimer: Timer? = null
    private var cachedHash: String = ""

    init {
        background = UIUtil.getPanelBackground()
        buildUI()
        applyWorkItem()
        startAutoRefresh()
    }

    private fun buildUI() {
        val scrollContent = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(16)
            background = UIUtil.getPanelBackground()
        }

        // Header
        val headerPanel = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(60))

            val leftHeader = JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                isOpaque = false
                add(typeIdLabel)
                add(Box.createVerticalStrut(4))
                add(titleLabel)
            }
            add(leftHeader, BorderLayout.CENTER)

            val rightHeader = JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = false
                add(stateLabel)

                val browserButton = JButton("Open in Browser", AllIcons.Ide.External_link_arrow).apply {
                    addActionListener {
                        val url = workItem.getWebUrl()
                        if (url.isNotBlank()) BrowserUtil.browse(url)
                    }
                }
                add(browserButton)
            }
            add(rightHeader, BorderLayout.EAST)
        }
        scrollContent.add(headerPanel)
        scrollContent.add(Box.createVerticalStrut(JBUI.scale(12)))
        scrollContent.add(createSeparator())
        scrollContent.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Info section
        val infoPanel = createInfoPanel()
        infoPanel.alignmentX = Component.LEFT_ALIGNMENT
        scrollContent.add(infoPanel)
        scrollContent.add(Box.createVerticalStrut(JBUI.scale(12)))
        scrollContent.add(createSeparator())
        scrollContent.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Description section
        val descSection = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(250))
            preferredSize = Dimension(0, JBUI.scale(200))

            val descHeader = JBLabel("Description").apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 13f)
                border = JBUI.Borders.empty(0, 0, 6, 0)
            }
            add(descHeader, BorderLayout.NORTH)

            val descScroll = JBScrollPane(descriptionPane).apply {
                border = BorderFactory.createLineBorder(JBColor.border(), 1)
                verticalScrollBar.unitIncrement = 16
            }
            add(descScroll, BorderLayout.CENTER)
        }
        scrollContent.add(descSection)
        scrollContent.add(Box.createVerticalStrut(JBUI.scale(12)))
        scrollContent.add(createSeparator())
        scrollContent.add(Box.createVerticalStrut(JBUI.scale(12)))

        // Discussion/Comments section
        val commentsSection = JPanel(BorderLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT

            val commentsHeader = JBLabel("Discussion").apply {
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 13f)
                border = JBUI.Borders.empty(0, 0, 6, 0)
            }
            add(commentsHeader, BorderLayout.NORTH)
            add(commentPanel, BorderLayout.CENTER)
        }
        scrollContent.add(commentsSection)
        scrollContent.add(Box.createVerticalGlue())

        val mainScroll = JBScrollPane(scrollContent).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 16
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(mainScroll, BorderLayout.CENTER)
    }

    private fun createInfoPanel(): JPanel {
        return JPanel(GridBagLayout()).apply {
            isOpaque = false
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(200))

            val gbc = GridBagConstraints().apply {
                anchor = GridBagConstraints.WEST
                insets = Insets(2, 0, 2, JBUI.scale(16))
            }

            var row = 0

            // Assigned To
            addInfoRow(this, gbc, row++, "Assigned To:", JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(assignedToAvatar)
                add(assignedToLabel)
            })

            // State + Reason
            addInfoRow(this, gbc, row++, "State:", JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
                isOpaque = false
                add(stateLabel)
                add(reasonLabel)
            })

            // Iteration Path
            addInfoRow(this, gbc, row++, "Iteration:", iterationLabel)

            // Area Path
            addInfoRow(this, gbc, row++, "Area:", areaLabel)

            // Priority
            addInfoRow(this, gbc, row++, "Priority:", priorityLabel)

            // Story Points
            addInfoRow(this, gbc, row++, "Story Points:", storyPointsLabel)

            // Tags
            addInfoRow(this, gbc, row++, "Tags:", tagsLabel)

            // Created
            addInfoRow(this, gbc, row++, "Created:", createdLabel)

            // Changed
            addInfoRow(this, gbc, row++, "Changed:", changedLabel)
        }
    }

    private fun addInfoRow(panel: JPanel, gbc: GridBagConstraints, row: Int, label: String, component: JComponent) {
        gbc.gridx = 0
        gbc.gridy = row
        gbc.weightx = 0.0
        gbc.fill = GridBagConstraints.NONE
        val labelComponent = JBLabel(label).apply {
            foreground = JBColor.GRAY
            font = UIUtil.getLabelFont().deriveFont(Font.PLAIN, 11f)
        }
        panel.add(labelComponent, gbc)

        gbc.gridx = 1
        gbc.weightx = 1.0
        gbc.fill = GridBagConstraints.HORIZONTAL
        panel.add(component, gbc)
    }

    private fun applyWorkItem() {
        // Header
        titleLabel.text = workItem.getTitle()
        typeIdLabel.apply {
            text = "${workItem.getWorkItemType()} #${workItem.id}"
            foreground = workItem.getTypeColor()
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 12f)
        }
        stateLabel.apply {
            text = "  ${workItem.getState()}  "
            foreground = Color.WHITE
            isOpaque = true
            background = workItem.getStateColor()
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 11f)
            border = JBUI.Borders.empty(2, 6)
        }

        // Info fields
        val assignedTo = workItem.getAssignedTo()
        assignedToLabel.text = assignedTo ?: "Unassigned"
        workItem.getAssignedToImageUrl()?.let { url ->
            assignedToAvatar.icon = avatarService.getAvatar(url, 20) {
                assignedToAvatar.icon = avatarService.getAvatar(url, 20)
            }
        }

        reasonLabel.apply {
            val reason = workItem.getReason()
            text = if (reason != null) "($reason)" else ""
            foreground = JBColor.GRAY
        }

        iterationLabel.text = workItem.getIterationPath() ?: "—"
        areaLabel.text = workItem.getAreaPath() ?: "—"

        val priority = workItem.getPriority()
        priorityLabel.apply {
            text = if (priority != null) "P$priority" else "—"
            foreground = WorkItem.priorityColor(priority) ?: UIUtil.getLabelForeground()
            font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 12f)
        }

        val storyPoints = workItem.getStoryPoints()
        storyPointsLabel.text = if (storyPoints != null) String.format("%.0f", storyPoints) else "—"

        tagsLabel.text = workItem.getTags()?.ifBlank { "—" } ?: "—"
        createdLabel.text = "${workItem.getCreatedBy() ?: "Unknown"} · ${workItem.getCreatedRelativeDate()}"
        changedLabel.text = "${workItem.getChangedBy() ?: "Unknown"} · ${workItem.getRelativeDate()}"

        // Description
        val desc = workItem.getDescription()
        if (!desc.isNullOrBlank()) {
            val styledHtml = """
                <html><body style="font-family: ${UIUtil.getLabelFont().family}; font-size: 12px; margin: 4px;">
                $desc
                </body></html>
            """.trimIndent()
            descriptionPane.text = styledHtml
            descriptionPane.caretPosition = 0
        } else {
            descriptionPane.text = "<html><body><i style='color: gray;'>No description</i></body></html>"
        }
    }

    private fun createSeparator(): JSeparator {
        return JSeparator(SwingConstants.HORIZONTAL).apply {
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 1)
        }
    }

    private fun startAutoRefresh() {
        refreshTimer = Timer(30_000) {
            refreshData()
        }.apply {
            isRepeats = true
            start()
        }
    }

    private fun refreshData() {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val updated = apiClient.getWorkItem(workItem.id)
                val newHash = "${updated.rev}:${updated.getState()}:${updated.getTitle().hashCode()}:${updated.getChangedDate()}"
                if (newHash != cachedHash) {
                    cachedHash = newHash
                    ApplicationManager.getApplication().invokeLater {
                        workItem = updated
                        applyWorkItem()
                        commentPanel.refresh()
                    }
                }
            } catch (e: Exception) {
                logger.warn("Failed to refresh work item #${workItem.id}: ${e.message}")
            }
        }
    }

    fun dispose() {
        refreshTimer?.stop()
        refreshTimer = null
    }
}
