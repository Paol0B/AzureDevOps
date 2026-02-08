package paol0b.azuredevops.toolwindow.review

import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.*
import paol0b.azuredevops.services.AvatarService
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.toolwindow.review.timeline.*
import java.awt.*
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.swing.*

/**
 * Panel that shows the chronological timeline of a Pull Request:
 * comments (with nested replies), votes, status changes, iterations, etc.
 *
 * Features:
 * - Card-based comment threads with nested reply components
 * - Reviewer vote badge panel at the top
 * - Time direction indicator (oldest → newest, scroll down for newer)
 * - Hash-based polling: re-renders only when data actually changes
 * - Dropdown menu (⋮) per comment for reply / change status
 * - Inline reply text field per comment card
 */
class TimelinePanel(
    private val project: Project,
    private val pullRequest: PullRequest
) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(TimelinePanel::class.java)
    private val apiClient = AzureDevOpsApiClient.getInstance(project)
    private val avatarService = AvatarService.getInstance(project)

    // ── UI containers ──
    private val voteBadgePanel = VoteBadgePanel(project)

    private val timelineContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(8, 14)
    }

    private lateinit var scrollPane: JBScrollPane

    // ── Polling state ──
    private var scheduler: ScheduledExecutorService? = null
    private var lastDataHash: Int = 0
    @Volatile private var latestPullRequest: PullRequest = pullRequest

    companion object {
        private const val POLLING_INTERVAL_SECONDS = 8L
    }

    init {
        background = UIUtil.getPanelBackground()
        setupUI()
        loadTimeline()
        startPolling()
    }

    // ==================================================================
    //  UI Setup
    // ==================================================================

    private fun setupUI() {
        val topPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
        }

        // ── Header row ──
        val header = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(8, 14, 2, 14)
            maximumSize = Dimension(Int.MAX_VALUE, 36)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        header.add(JBLabel("Timeline — PR #${pullRequest.pullRequestId}").apply {
            icon = AllIcons.Vcs.History
            font = font.deriveFont(Font.BOLD, 14f)
        }, BorderLayout.WEST)

        // Refresh button
        val refreshBtn = JButton().apply {
            icon = AllIcons.Actions.Refresh
            toolTipText = "Refresh timeline"
            isBorderPainted = false
            isContentAreaFilled = false
            preferredSize = Dimension(28, 28)
            addActionListener { loadTimeline() }
        }
        header.add(refreshBtn, BorderLayout.EAST)

        topPanel.add(header)

        // ── Vote badges ──
        val badgeWrapper = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(0, 14, 0, 14)
            alignmentX = Component.LEFT_ALIGNMENT
        }
        badgeWrapper.add(voteBadgePanel, BorderLayout.CENTER)
        topPanel.add(badgeWrapper)

        // ── Separator ──
        topPanel.add(JSeparator().apply {
            maximumSize = Dimension(Int.MAX_VALUE, 1)
            alignmentX = Component.LEFT_ALIGNMENT
        })

        // ── Time direction indicator ──
        val dirLabel = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            background = UIUtil.getPanelBackground()
            alignmentX = Component.LEFT_ALIGNMENT
            border = JBUI.Borders.empty(4, 14, 0, 14)
        }
        dirLabel.add(JBLabel("▲ Older").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.ITALIC, 10f)
        })
        topPanel.add(dirLabel)

        add(topPanel, BorderLayout.NORTH)

        // ── Loading placeholder ──
        timelineContainer.add(createLoadingLabel())

        scrollPane = JBScrollPane(timelineContainer).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 20
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scrollPane, BorderLayout.CENTER)

        // ── Bottom direction indicator ──
        val bottomDir = JPanel(FlowLayout(FlowLayout.LEFT, 6, 2)).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(2, 14, 6, 14)
        }
        bottomDir.add(JBLabel("▼ Newer").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.ITALIC, 10f)
        })
        add(bottomDir, BorderLayout.SOUTH)
    }

    // ==================================================================
    //  Data loading
    // ==================================================================

    private fun loadTimeline() {
        val projectName = pullRequest.repository?.project?.name
        val repositoryId = pullRequest.repository?.id

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val threads = apiClient.getCommentThreads(pullRequest.pullRequestId, projectName, repositoryId)
                // Re-fetch PR for up-to-date reviewers
                val freshPr = try {
                    apiClient.getPullRequest(pullRequest.pullRequestId, projectName, repositoryId)
                } catch (_: Exception) { pullRequest }
                latestPullRequest = freshPr

                val hash = TimelineConverter.calculateHash(threads, freshPr.reviewers)
                if (hash == lastDataHash) return@executeOnPooledThread    // nothing changed
                lastDataHash = hash

                val entries = TimelineConverter.buildEntries(freshPr, threads)
                val voteSummaries = TimelineConverter.buildVoteSummaries(freshPr.reviewers)

                // Preload all avatars
                val urls = mutableListOf<String?>()
                urls += entries.map { it.authorImageUrl }
                urls += entries.flatMap { it.replies.map { r -> r.authorImageUrl } }
                urls += (freshPr.reviewers ?: emptyList()).map { it.imageUrl }
                avatarService.preloadAvatars(urls)

                ApplicationManager.getApplication().invokeLater {
                    renderTimeline(entries, voteSummaries)
                }
            } catch (e: Exception) {
                logger.error("Failed to load timeline", e)
                ApplicationManager.getApplication().invokeLater {
                    timelineContainer.removeAll()
                    timelineContainer.add(JBLabel("Failed to load timeline: ${e.message}").apply {
                        foreground = JBColor.RED
                        alignmentX = Component.LEFT_ALIGNMENT
                    })
                    timelineContainer.revalidate()
                    timelineContainer.repaint()
                }
            }
        }
    }

    // ==================================================================
    //  Rendering
    // ==================================================================

    private fun renderTimeline(entries: List<TimelineEntry>, voteSummaries: List<ReviewerVoteSummary>) {
        // Remember scroll position
        val scrollBar = scrollPane.verticalScrollBar
        val wasAtBottom = scrollBar.value + scrollBar.visibleAmount >= scrollBar.maximum - 20
        val oldScroll = scrollBar.value

        // Update vote badges
        voteBadgePanel.update(voteSummaries)

        // Rebuild timeline
        timelineContainer.removeAll()

        if (entries.isEmpty()) {
            timelineContainer.add(JBLabel("No timeline events found.").apply {
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
            })
        } else {
            for (entry in entries) {
                when (entry.type) {
                    TimelineEntryType.COMMENT_THREAD -> {
                        timelineContainer.add(CommentCardComponent(
                            project = project,
                            entry = entry,
                            pullRequest = latestPullRequest,
                            onReply = { threadId, content -> handleReply(threadId, content) },
                            onStatusChange = { threadId, status -> handleStatusChange(threadId, status) }
                        ))
                    }
                    TimelineEntryType.VOTE_EVENT -> {
                        timelineContainer.add(createVoteEventRow(entry))
                    }
                    else -> {
                        timelineContainer.add(createSystemEventRow(entry))
                    }
                }
                timelineContainer.add(Box.createVerticalStrut(6))
            }
        }

        timelineContainer.revalidate()
        timelineContainer.repaint()

        // Restore / auto-scroll
        SwingUtilities.invokeLater {
            if (wasAtBottom) {
                scrollBar.value = scrollBar.maximum
            } else {
                scrollBar.value = oldScroll
            }
        }
    }

    // ── System / vote / created event (simple row, no card) ──

    private fun createSystemEventRow(entry: TimelineEntry): JPanel {
        val row = JPanel(BorderLayout(8, 0)).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4, 0)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 48)
        }

        // Avatar
        val avatarIcon = avatarService.getAvatar(entry.authorImageUrl, 24) { row.repaint() }
        row.add(JBLabel(avatarIcon).apply {
            verticalAlignment = SwingConstants.TOP
            border = JBUI.Borders.emptyTop(2)
        }, BorderLayout.WEST)

        // Content
        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
        }

        val headerLine = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        headerLine.add(JBLabel(entry.author).apply {
            font = font.deriveFont(Font.BOLD, 11f)
        })

        val typeIcon = when (entry.type) {
            TimelineEntryType.PR_CREATED -> AllIcons.General.Add
            TimelineEntryType.VOTE_EVENT -> AllIcons.Actions.Checked
            TimelineEntryType.SYSTEM_EVENT -> AllIcons.General.Information
            else -> AllIcons.General.Balloon
        }
        headerLine.add(JBLabel(typeIcon))

        headerLine.add(JBLabel(entry.content).apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.ITALIC, 11f)
        })

        val ts = TimelineUtils.formatTimeAgo(entry.timestamp)
        if (ts.isNotEmpty()) {
            headerLine.add(JBLabel("· $ts").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(10f)
            })
        }
        center.add(headerLine)
        row.add(center, BorderLayout.CENTER)

        return row
    }

    private fun createVoteEventRow(entry: TimelineEntry): JPanel {
        val row = JPanel(BorderLayout(8, 0)).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(4, 0)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 48)
        }

        // Avatar
        val avatarIcon = avatarService.getAvatar(entry.authorImageUrl, 24) { row.repaint() }
        row.add(JBLabel(avatarIcon).apply {
            verticalAlignment = SwingConstants.TOP
            border = JBUI.Borders.emptyTop(2)
        }, BorderLayout.WEST)

        // Content
        val center = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
        }

        val headerLine = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
        }
        
        // Author name
        headerLine.add(JBLabel(entry.author).apply {
            font = font.deriveFont(Font.BOLD, 11f)
        })

        val voteStatus = ReviewerVote.fromVoteValue(entry.voteValue)
        
        // Vote icon
        val voteIcon = when (voteStatus) {
            ReviewerVote.Approved -> AllIcons.RunConfigurations.TestPassed
            ReviewerVote.ApprovedWithSuggestions -> AllIcons.General.Information
            ReviewerVote.WaitingForAuthor -> AllIcons.General.Warning
            ReviewerVote.Rejected -> AllIcons.RunConfigurations.TestFailed
            ReviewerVote.NoVote -> AllIcons.Debugger.ThreadSuspended
        }
        
        // Vote color
        val voteColor = when (voteStatus) {
            ReviewerVote.Approved -> JBColor(Color(34, 139, 34), Color(50, 200, 50))
            ReviewerVote.ApprovedWithSuggestions -> JBColor(Color(255, 165, 0), Color(255, 140, 0))
            ReviewerVote.WaitingForAuthor -> JBColor(Color(255, 165, 0), Color(255, 140, 0))
            ReviewerVote.Rejected -> JBColor(Color(220, 53, 69), Color(200, 35, 51))
            ReviewerVote.NoVote -> JBColor.GRAY
        }
        
        headerLine.add(JBLabel(voteIcon))
        
        // Vote display name
        headerLine.add(JBLabel(voteStatus.getDisplayName()).apply {
            foreground = voteColor
            font = font.deriveFont(Font.BOLD, 11f)
        })

        // Timestamp
        val ts = TimelineUtils.formatTimeAgo(entry.timestamp)
        if (ts.isNotEmpty()) {
            headerLine.add(JBLabel("· $ts").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(10f)
            })
        }
        
        center.add(headerLine)
        row.add(center, BorderLayout.CENTER)

        return row
    }

    // ==================================================================
    //  Actions (reply, status change)
    // ==================================================================

    private fun handleReply(threadId: Int, content: String) {
        val projectName = pullRequest.repository?.project?.name
        val repositoryId = pullRequest.repository?.id

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                apiClient.addCommentToThread(
                    pullRequest.pullRequestId,
                    threadId,
                    content,
                    projectName,
                    repositoryId
                )
                logger.info("Reply added to thread #$threadId")
                // Force hash reset so next poll picks up change immediately
                lastDataHash = 0
                loadTimeline()
            } catch (e: Exception) {
                logger.error("Failed to reply to thread #$threadId", e)
            }
        }
    }

    private fun handleStatusChange(threadId: Int, newStatus: ThreadStatus) {
        val projectName = pullRequest.repository?.project?.name
        val repositoryId = pullRequest.repository?.id

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                apiClient.updateThreadStatus(
                    pullRequest.pullRequestId,
                    threadId,
                    newStatus,
                    projectName,
                    repositoryId
                )
                logger.info("Thread #$threadId status → $newStatus")
                lastDataHash = 0
                loadTimeline()
            } catch (e: Exception) {
                logger.error("Failed to update thread #$threadId status", e)
            }
        }
    }

    // ==================================================================
    //  Polling (hash-based)
    // ==================================================================

    private fun startPolling() {
        scheduler = ScheduledThreadPoolExecutor(1).apply {
            scheduleAtFixedRate(
                { loadTimeline() },
                POLLING_INTERVAL_SECONDS,
                POLLING_INTERVAL_SECONDS,
                TimeUnit.SECONDS
            )
        }
    }

    fun stopPolling() {
        scheduler?.shutdown()
        scheduler = null
    }

    fun dispose() {
        stopPolling()
    }

    // ==================================================================
    //  Helpers
    // ==================================================================

    private fun createLoadingLabel(): JComponent {
        return JBLabel("Loading timeline...").apply {
            foreground = JBColor.GRAY
            alignmentX = Component.LEFT_ALIGNMENT
            icon = AllIcons.Process.Step_1
        }
    }
}
