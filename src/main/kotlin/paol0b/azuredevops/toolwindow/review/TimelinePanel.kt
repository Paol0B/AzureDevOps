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
import java.awt.*
import java.text.SimpleDateFormat
import java.util.*
import javax.swing.*

/**
 * Panel that shows the chronological timeline of a Pull Request:
 * comments, votes, status changes, iterations, etc.
 *
 * Each entry has an avatar, author name, timestamp, and content.
 */
class TimelinePanel(
    private val project: Project,
    private val pullRequest: PullRequest
) : JPanel(BorderLayout()) {

    private val logger = Logger.getInstance(TimelinePanel::class.java)
    private val apiClient = AzureDevOpsApiClient.getInstance(project)
    private val avatarService = AvatarService.getInstance(project)

    private val timelineContainer = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        background = UIUtil.getPanelBackground()
        border = JBUI.Borders.empty(10, 14)
    }

    init {
        background = UIUtil.getPanelBackground()
        setupUI()
        loadTimeline()
    }

    private fun setupUI() {
        // Header
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 8, 4)).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(8, 14, 4, 14)
        }
        header.add(JBLabel("Timeline — PR #${pullRequest.pullRequestId}").apply {
            icon = AllIcons.Vcs.History
            font = font.deriveFont(Font.BOLD, 14f)
        })
        add(header, BorderLayout.NORTH)

        // Loading state
        timelineContainer.add(JBLabel("Loading timeline...").apply {
            foreground = JBColor.GRAY
            alignmentX = Component.LEFT_ALIGNMENT
        })

        val scrollPane = JBScrollPane(timelineContainer).apply {
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = 16
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scrollPane, BorderLayout.CENTER)
    }

    private fun loadTimeline() {
        val projectName = pullRequest.repository?.project?.name
        val repositoryId = pullRequest.repository?.id

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val threads = apiClient.getCommentThreads(pullRequest.pullRequestId, projectName, repositoryId)

                // Build timeline entries from threads
                val entries = mutableListOf<TimelineEntry>()

                // PR creation event
                entries.add(TimelineEntry(
                    type = EntryType.CREATED,
                    author = pullRequest.createdBy?.displayName ?: "Unknown",
                    authorImageUrl = pullRequest.createdBy?.imageUrl,
                    timestamp = pullRequest.creationDate,
                    content = "created this pull request",
                    filePath = null
                ))

                // Comment threads → entries
                for (thread in threads) {
                    if (thread.isDeleted == true) continue
                    val comments = thread.comments ?: continue

                    for (comment in comments) {
                        if (comment.isDeleted == true) continue
                        val authorName = comment.author?.displayName ?: "Unknown"
                        val authorImg = comment.author?.imageUrl
                        val isSystem = comment.commentType?.equals("system", ignoreCase = true) == true

                        val entryType = when {
                            isSystem && comment.content?.contains("voted", ignoreCase = true) == true -> EntryType.VOTE
                            isSystem -> EntryType.SYSTEM
                            else -> EntryType.COMMENT
                        }

                        entries.add(TimelineEntry(
                            type = entryType,
                            author = authorName,
                            authorImageUrl = authorImg,
                            timestamp = comment.publishedDate ?: comment.lastUpdatedDate,
                            content = comment.content ?: "",
                            filePath = thread.getFilePath(),
                            threadId = thread.id,
                            threadStatus = thread.status?.getDisplayName()
                        ))
                    }
                }

                // Sort chronologically
                entries.sortBy { it.timestamp ?: "" }

                // Preload avatars
                avatarService.preloadAvatars(entries.mapNotNull { it.authorImageUrl })

                ApplicationManager.getApplication().invokeLater {
                    renderTimeline(entries)
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

    private fun renderTimeline(entries: List<TimelineEntry>) {
        timelineContainer.removeAll()

        if (entries.isEmpty()) {
            timelineContainer.add(JBLabel("No timeline events found.").apply {
                foreground = JBColor.GRAY
                alignmentX = Component.LEFT_ALIGNMENT
            })
        } else {
            for (entry in entries) {
                timelineContainer.add(createEntryPanel(entry))
                timelineContainer.add(Box.createVerticalStrut(6))
            }
        }

        timelineContainer.revalidate()
        timelineContainer.repaint()
    }

    private fun createEntryPanel(entry: TimelineEntry): JPanel {
        val panel = JPanel(BorderLayout(8, 0)).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(6, 0, 6, 0)
            alignmentX = Component.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 200)
        }

        // Avatar (left)
        val avatarIcon = avatarService.getAvatar(entry.authorImageUrl, 28) {
            panel.repaint()
        }
        val avatarLabel = JBLabel(avatarIcon).apply {
            verticalAlignment = SwingConstants.TOP
            border = JBUI.Borders.emptyTop(2)
        }
        panel.add(avatarLabel, BorderLayout.WEST)

        // Content area (center)
        val contentPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
        }

        // Author + timestamp line
        val headerLine = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply {
            background = UIUtil.getPanelBackground()
            alignmentX = Component.LEFT_ALIGNMENT
        }
        headerLine.add(JBLabel(entry.author).apply {
            font = font.deriveFont(Font.BOLD, 12f)
        })
        val formattedTime = formatTimestamp(entry.timestamp)
        if (formattedTime.isNotEmpty()) {
            headerLine.add(JBLabel(formattedTime).apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(11f)
            })
        }
        contentPanel.add(headerLine)

        // Entry icon + type indicator
        val typeIcon = when (entry.type) {
            EntryType.CREATED -> AllIcons.General.Add
            EntryType.COMMENT -> AllIcons.General.Balloon
            EntryType.VOTE -> AllIcons.Actions.Checked
            EntryType.SYSTEM -> AllIcons.General.Information
        }

        // Content text
        val contentText = when (entry.type) {
            EntryType.COMMENT -> {
                val loc = if (entry.filePath != null) " on ${entry.filePath}" else ""
                val statusBadge = if (!entry.threadStatus.isNullOrBlank() && entry.threadStatus != "Unknown") " [${entry.threadStatus}]" else ""
                "${entry.content}$loc$statusBadge"
            }
            else -> entry.content
        }

        val contentLine = JPanel(FlowLayout(FlowLayout.LEFT, 4, 2)).apply {
            background = UIUtil.getPanelBackground()
            alignmentX = Component.LEFT_ALIGNMENT
        }
        contentLine.add(JBLabel(typeIcon))

        val textColor = when (entry.type) {
            EntryType.SYSTEM, EntryType.VOTE -> JBColor.GRAY
            else -> UIUtil.getLabelForeground()
        }
        contentLine.add(JBLabel("<html>${escapeHtml(contentText)}</html>").apply {
            foreground = textColor
            font = if (entry.type == EntryType.SYSTEM || entry.type == EntryType.VOTE)
                font.deriveFont(Font.ITALIC, 11f) else font.deriveFont(12f)
        })
        contentPanel.add(contentLine)

        panel.add(contentPanel, BorderLayout.CENTER)

        return panel
    }

    private fun formatTimestamp(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            parser.timeZone = TimeZone.getTimeZone("UTC")
            val date = parser.parse(raw.take(19))
            val formatter = SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US)
            formatter.format(date)
        } catch (_: Exception) {
            raw.take(16).replace('T', ' ')
        }
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }

    // ------------------------------------------------------------------
    //  Data classes
    // ------------------------------------------------------------------

    private enum class EntryType { CREATED, COMMENT, VOTE, SYSTEM }

    private data class TimelineEntry(
        val type: EntryType,
        val author: String,
        val authorImageUrl: String?,
        val timestamp: String?,
        val content: String,
        val filePath: String?,
        val threadId: Int? = null,
        val threadStatus: String? = null
    )
}
