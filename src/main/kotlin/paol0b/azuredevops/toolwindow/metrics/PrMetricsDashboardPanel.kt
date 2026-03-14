package paol0b.azuredevops.toolwindow.metrics

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.PrMetrics
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.services.PrMetricsService
import java.awt.*
import javax.swing.*

/**
 * Main dashboard panel showing PR metrics & trends.
 *
 * Layout (scrollable):
 * ┌──────────────────────────────────────────────────────┐
 * │ Summary Cards Row (total, active, merged, avg time)  │
 * ├──────────────────────────────────────────────────────┤
 * │ Weekly Volume (bar chart)  │  Status Distribution    │
 * ├──────────────────────────────────────────────────────┤
 * │ Merge Time Trend (bar)    │  Vote Distribution      │
 * ├──────────────────────────────────────────────────────┤
 * │ Top Authors (leaderboard) │  Top Reviewers (leader)  │
 * └──────────────────────────────────────────────────────┘
 */
class PrMetricsDashboardPanel(private val project: Project) {

    private val logger = Logger.getInstance(PrMetricsDashboardPanel::class.java)

    private val mainPanel: JPanel
    private val contentPanel: JPanel
    private val statusLabel: JBLabel
    private val scopeToggle: JComboBox<String>

    // Metric cards
    private val totalCard = MetricCardComponent("Total PRs", "—", "", accentColor = JBColor(Color(0x3574F0), Color(0x548AF7)))
    private val activeCard = MetricCardComponent("Active", "—", "", accentColor = JBColor(Color(0x2DA44E), Color(0x3FB950)))
    private val mergedCard = MetricCardComponent("Merged", "—", "", accentColor = JBColor(Color(0x8250DF), Color(0xA371F7)))
    private val avgTimeCard = MetricCardComponent("Avg Merge Time", "—", "", accentColor = JBColor(Color(0xBF8700), Color(0xD29922)))
    private val throughputCard = MetricCardComponent("Merges / Week", "—", "", accentColor = JBColor(Color(0x0969DA), Color(0x58A6FF)))
    private val conflictCard = MetricCardComponent("Conflict Rate", "—", "", accentColor = JBColor(Color(0xCF222E), Color(0xF85149)))

    // Charts
    private val weeklyVolumeChart = BarChartComponent("PR Volume (weekly)", JBColor(Color(0x3574F0), Color(0x548AF7)), "PRs")
    private val mergeTrendChart = BarChartComponent("Time to Merge Trend (weekly avg)", JBColor(Color(0xBF8700), Color(0xD29922)), "hours")
    private val statusDonut = DonutChartComponent("Status Distribution")
    private val voteDonut = DonutChartComponent("Review Votes")
    private val authorLeaderboard = LeaderboardComponent("Top Authors", JBColor(Color(0x3574F0), Color(0x548AF7)))
    private val reviewerLeaderboard = LeaderboardComponent("Top Reviewers", JBColor(Color(0x2DA44E), Color(0x3FB950)))

    init {
        statusLabel = JBLabel("Click Refresh to load metrics").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.PLAIN, 11f)
        }

        scopeToggle = JComboBox(arrayOf("All Organization", "Current Repository")).apply {
            selectedIndex = 0
            addActionListener { loadMetrics() }
        }

        contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(16)
            background = UIUtil.getPanelBackground()
        }

        buildLayout()

        mainPanel = JPanel(BorderLayout()).apply {
            val toolbar = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
                add(JBLabel("Scope:"))
                add(scopeToggle)
                add(statusLabel)
                border = JBUI.Borders.empty(4, 8)
            }
            add(toolbar, BorderLayout.NORTH)
            add(JBScrollPane(contentPanel).apply {
                border = JBUI.Borders.empty()
                verticalScrollBar.unitIncrement = 16
                horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            }, BorderLayout.CENTER)
        }
    }

    fun getComponent(): JPanel = mainPanel

    fun loadMetrics() {
        statusLabel.text = "Loading PR data..."
        statusLabel.icon = AllIcons.Process.Step_1


        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Computing PR Metrics...", false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val apiClient = AzureDevOpsApiClient.getInstance(project)
                    val useOrgScope = scopeToggle.selectedIndex == 0

                    indicator.text = "Fetching completed PRs..."
                    val completed = if (useOrgScope)
                        apiClient.getAllOrganizationPullRequests(status = "completed", top = 100)
                    else
                        apiClient.getPullRequests(status = "completed", top = 100)

                    indicator.text = "Fetching active PRs..."
                    val active = if (useOrgScope)
                        apiClient.getAllOrganizationPullRequests(status = "active", top = 100)
                    else
                        apiClient.getPullRequests(status = "active", top = 100)

                    indicator.text = "Fetching abandoned PRs..."
                    val abandoned = if (useOrgScope)
                        apiClient.getAllOrganizationPullRequests(status = "abandoned", top = 100)
                    else
                        apiClient.getPullRequests(status = "abandoned", top = 100)

                    val allPrs = completed + active + abandoned

                    indicator.text = "Computing metrics..."
                    val metrics = PrMetricsService.compute(allPrs)

                    ApplicationManager.getApplication().invokeLater {
                        applyMetrics(metrics, allPrs.size)
                    }
                } catch (e: Exception) {
                    logger.warn("Failed to load PR metrics", e)
                    ApplicationManager.getApplication().invokeLater {
                        statusLabel.text = "Error: ${e.message?.take(80)}"
                        statusLabel.icon = AllIcons.General.Error
                
                    }
                }
            }
        })
    }

    private fun applyMetrics(metrics: PrMetrics, totalLoaded: Int) {
        // Update cards in-place
        totalCard.update(metrics.totalPrs.toString(), "${metrics.draftPrs} drafts")
        activeCard.update(metrics.activePrs.toString(), "open now")
        mergedCard.update(metrics.completedPrs.toString(), "${metrics.abandonedPrs} abandoned")
        avgTimeCard.update(formatHours(metrics.avgHoursToMerge), "median ${formatHours(metrics.medianHoursToMerge)}")
        throughputCard.update(String.format("%.1f", metrics.mergesPerWeek), "avg reviewers: ${String.format("%.1f", metrics.avgReviewerCount)}")
        conflictCard.update(String.format("%.0f%%", metrics.conflictRate), "auto-complete: ${String.format("%.0f%%", metrics.autoCompleteRate)}")

        // Update charts
        weeklyVolumeChart.setData(metrics.weeklyVolume)
        mergeTrendChart.setData(metrics.weeklyMergeTrend)
        statusDonut.setData(metrics.statusDistribution)
        voteDonut.setData(metrics.voteDistribution)
        authorLeaderboard.setData(metrics.authorLeaderboard)
        reviewerLeaderboard.setData(metrics.reviewerLeaderboard)

        statusLabel.text = "Analyzed $totalLoaded PRs"
        statusLabel.icon = AllIcons.General.InspectionsOK


        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun buildLayout() {
        // === Summary Cards ===
        val cardsRow = JPanel(GridLayout(1, 6, JBUI.scale(8), 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(95))
            add(totalCard)
            add(activeCard)
            add(mergedCard)
            add(avgTimeCard)
            add(throughputCard)
            add(conflictCard)
        }
        contentPanel.add(cardsRow)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(16)))

        // === Row 1: Weekly Volume + Status Distribution ===
        val row1 = createChartRow(weeklyVolumeChart, statusDonut, 0.6)
        contentPanel.add(row1)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(16)))

        // === Row 2: Merge Trend + Vote Distribution ===
        val row2 = createChartRow(mergeTrendChart, voteDonut, 0.6)
        contentPanel.add(row2)
        contentPanel.add(Box.createVerticalStrut(JBUI.scale(16)))

        // === Row 3: Author + Reviewer Leaderboards ===
        val row3 = createChartRow(authorLeaderboard, reviewerLeaderboard, 0.5)
        contentPanel.add(row3)

        contentPanel.add(Box.createVerticalGlue())
    }

    private fun createChartRow(left: JPanel, right: JPanel, leftWeight: Double): JPanel {
        return JPanel(GridBagLayout()).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, JBUI.scale(220))
            preferredSize = Dimension(0, JBUI.scale(220))

            val gbc = GridBagConstraints().apply {
                fill = GridBagConstraints.BOTH
                gridy = 0
                weighty = 1.0
                insets = Insets(0, 0, 0, JBUI.scale(8))
            }

            gbc.gridx = 0
            gbc.weightx = leftWeight
            add(wrapInCard(left), gbc)

            gbc.gridx = 1
            gbc.weightx = 1.0 - leftWeight
            gbc.insets = Insets(0, 0, 0, 0)
            add(wrapInCard(right), gbc)
        }
    }

    private fun wrapInCard(inner: JPanel): JPanel {
        return JPanel(BorderLayout()).apply {
            border = BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(JBColor.border(), 1),
                JBUI.Borders.empty(10, 12)
            )
            background = UIUtil.getPanelBackground()
            add(inner, BorderLayout.CENTER)
        }
    }

    private fun formatHours(hours: Double): String {
        return when {
            hours < 1 -> "${(hours * 60).toInt()}m"
            hours < 24 -> String.format("%.1fh", hours)
            else -> String.format("%.1fd", hours / 24)
        }
    }
}
