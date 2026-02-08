package paol0b.azuredevops.toolwindow.review.timeline

import com.intellij.diff.comparison.ComparisonManager
import com.intellij.diff.comparison.ComparisonPolicy
import com.intellij.diff.fragments.LineFragment
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.colors.EditorColorsManager
import com.intellij.openapi.progress.DumbProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.PullRequest
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.*
import javax.swing.*

/**
 * Diff line types for unified diff rendering.
 */
enum class DiffLineType { CONTEXT, ADDED, REMOVED, HEADER }

/**
 * A single line in the unified diff output.
 */
data class DiffLine(
    val type: DiffLineType,
    val text: String,
    val oldLineNumber: Int? = null,
    val newLineNumber: Int? = null
)

/**
 * An expandable accordion component that shows a unified diff preview
 * for file-scoped comments in the PR timeline.
 *
 * Layout (collapsed):
 * ┌──────────────────────────────────┐
 * │ ▶ View changes                   │
 * └──────────────────────────────────┘
 *
 * Layout (expanded):
 * ┌──────────────────────────────────┐
 * │ ▼ View changes                   │
 * │ ┌────────────────────────────────┐│
 * │ │ @@ lines 3–17 @@              ││
 * │ │  3  3   context line           ││
 * │ │  4     - old line              ││
 * │ │     4  + new line              ││
 * │ │  5  5   context line           ││
 * │ └────────────────────────────────┘│
 * └──────────────────────────────────┘
 *
 * Data is loaded lazily on first expand via the Azure DevOps API.
 */
class FileDiffPreviewComponent(
    private val project: Project,
    private val pullRequest: PullRequest,
    private val filePath: String,
    private val lineStart: Int,       // 1-based inclusive
    private val lineEnd: Int,         // 1-based inclusive
    private val isLeftSide: Boolean = false,
    private val contextLines: Int = 7
) : JPanel() {

    private val logger = Logger.getInstance(FileDiffPreviewComponent::class.java)
    private val apiClient = AzureDevOpsApiClient.getInstance(project)

    private var expanded = false
    private var loaded = false

    private val contentPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        isVisible = false
        border = JBUI.Borders.empty(4, 0, 0, 0)
    }

    private val chevronLabel = JBLabel(AllIcons.General.ArrowRight)

    // ── Colours ──
    private val addedBg = JBColor(Color(220, 255, 220), Color(35, 60, 35))
    private val removedBg = JBColor(Color(255, 220, 220), Color(60, 35, 35))
    private val headerBg = JBColor(Color(230, 238, 250), Color(40, 48, 65))
    private val lineNumFg = JBColor(Color(150, 150, 150), Color(100, 100, 100))
    private val diffBorderColor = JBColor(Color(210, 215, 220), Color(60, 63, 68))
    private val addedFg = JBColor(Color(30, 100, 30), Color(170, 230, 170))
    private val removedFg = JBColor(Color(150, 30, 30), Color(230, 170, 170))
    private val headerFg = JBColor(Color(100, 100, 180), Color(140, 140, 200))

    init {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
        alignmentX = Component.LEFT_ALIGNMENT

        // ── Toggle header ──
        val header = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
            isOpaque = false
            alignmentX = Component.LEFT_ALIGNMENT
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
        }
        header.add(chevronLabel)
        header.add(JBLabel("View changes").apply {
            foreground = JBColor(Color(70, 130, 180), Color(100, 149, 237))
            font = font.deriveFont(Font.BOLD, 11f)
        })

        header.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                toggle()
            }
        })

        add(header)
        add(contentPanel)
    }

    // ================================================================
    //  Toggle expand / collapse
    // ================================================================

    private fun toggle() {
        expanded = !expanded
        chevronLabel.icon = if (expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        contentPanel.isVisible = expanded

        if (expanded && !loaded) {
            loadDiffAsync()
        }

        revalidate()
        repaint()
    }

    // ================================================================
    //  Load diff data in background
    // ================================================================

    private fun loadDiffAsync() {
        contentPanel.removeAll()
        contentPanel.add(JBLabel("Loading diff\u2026").apply {
            foreground = JBColor.GRAY
            font = font.deriveFont(Font.ITALIC, 11f)
            icon = AllIcons.Process.Step_1
        })
        contentPanel.revalidate()
        contentPanel.repaint()

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val projectName = pullRequest.repository?.project?.name
                val repositoryId = pullRequest.repository?.id
                val sourceCommit = pullRequest.lastMergeSourceCommit?.commitId
                val targetCommit = pullRequest.lastMergeTargetCommit?.commitId

                val oldContent = if (targetCommit != null) {
                    try { apiClient.getFileContent(targetCommit, filePath, projectName, repositoryId) }
                    catch (_: Exception) { "" }
                } else ""

                val newContent = if (sourceCommit != null) {
                    try { apiClient.getFileContent(sourceCommit, filePath, projectName, repositoryId) }
                    catch (_: Exception) { "" }
                } else ""

                val diffLines = buildUnifiedDiff(oldContent, newContent)

                ApplicationManager.getApplication().invokeLater {
                    renderDiff(diffLines)
                    loaded = true
                }
            } catch (e: Exception) {
                logger.error("Failed to load diff preview for $filePath", e)
                ApplicationManager.getApplication().invokeLater {
                    contentPanel.removeAll()
                    contentPanel.add(JBLabel("Failed to load diff: ${e.message}").apply {
                        foreground = JBColor.RED
                        font = font.deriveFont(11f)
                    })
                    contentPanel.revalidate()
                    contentPanel.repaint()
                }
            }
        }
    }

    // ================================================================
    //  Build unified diff lines
    // ================================================================

    /**
     * Compares old and new file content, then extracts a windowed unified diff
     * centred on the comment's line range with [contextLines] of context.
     */
    private fun buildUnifiedDiff(oldContent: String, newContent: String): List<DiffLine> {
        if (oldContent.isEmpty() && newContent.isEmpty()) return emptyList()

        val oldLines = if (oldContent.isEmpty()) emptyList() else oldContent.lines()
        val newLines = if (newContent.isEmpty()) emptyList() else newContent.lines()

        val fragments: List<LineFragment> = try {
            ComparisonManager.getInstance().compareLines(
                oldContent.ifEmpty { "\n" },
                newContent.ifEmpty { "\n" },
                ComparisonPolicy.DEFAULT,
                DumbProgressIndicator.INSTANCE
            )
        } catch (e: Exception) {
            logger.warn("ComparisonManager failed, falling back to simple preview", e)
            return buildSimplePreview(if (isLeftSide) oldLines else newLines)
        }

        // No changes? Just show line context.
        if (fragments.isEmpty()) {
            return buildSimplePreview(if (isLeftSide) oldLines else newLines)
        }

        // Calculate visible window on the reference side
        val refSize = if (isLeftSide) oldLines.size else newLines.size
        val winStart = maxOf(0, lineStart - 1 - contextLines)        // 0-based inclusive
        val winEnd = minOf(refSize, lineEnd + contextLines)           // 0-based exclusive

        val result = mutableListOf<DiffLine>()
        result.add(DiffLine(DiffLineType.HEADER, "@@ lines ${winStart + 1}\u2013$winEnd @@"))

        var newIdx = 0
        var oldIdx = 0

        for (frag in fragments) {
            // ── Context lines between previous fragment and this one ──
            while (newIdx < frag.startLine2 && oldIdx < frag.startLine1) {
                if (oldIdx < oldLines.size && newIdx < newLines.size) {
                    val inWindow = if (isLeftSide) oldIdx in winStart until winEnd
                                   else newIdx in winStart until winEnd
                    if (inWindow) {
                        result.add(DiffLine(DiffLineType.CONTEXT, newLines[newIdx], oldIdx + 1, newIdx + 1))
                    }
                }
                newIdx++
                oldIdx++
            }

            // ── Fragment: only render if it overlaps the visible window ──
            val fragOverlaps = if (isLeftSide) {
                frag.startLine1 < winEnd && maxOf(frag.endLine1, frag.startLine1 + 1) > winStart
            } else {
                frag.startLine2 < winEnd && maxOf(frag.endLine2, frag.startLine2 + 1) > winStart
            }

            if (fragOverlaps) {
                // Removed lines (old file)
                for (i in frag.startLine1 until frag.endLine1) {
                    if (i < oldLines.size) {
                        result.add(DiffLine(DiffLineType.REMOVED, oldLines[i], i + 1, null))
                    }
                }
                // Added lines (new file)
                for (i in frag.startLine2 until frag.endLine2) {
                    if (i < newLines.size) {
                        result.add(DiffLine(DiffLineType.ADDED, newLines[i], null, i + 1))
                    }
                }
            }

            newIdx = frag.endLine2
            oldIdx = frag.endLine1
        }

        // ── Remaining context after last fragment ──
        while (newIdx < newLines.size && oldIdx < oldLines.size) {
            val inWindow = if (isLeftSide) oldIdx in winStart until winEnd
                           else newIdx in winStart until winEnd
            if (inWindow) {
                result.add(DiffLine(DiffLineType.CONTEXT, newLines[newIdx], oldIdx + 1, newIdx + 1))
            }
            newIdx++
            oldIdx++
        }

        return result
    }

    /**
     * Fallback: plain lines without diff colouring when comparison fails or files are identical.
     */
    private fun buildSimplePreview(lines: List<String>): List<DiffLine> {
        if (lines.isEmpty()) return emptyList()
        val visStart = maxOf(0, lineStart - 1 - contextLines)
        val visEnd = minOf(lines.size, lineEnd + contextLines)
        val result = mutableListOf<DiffLine>()
        result.add(DiffLine(DiffLineType.HEADER, "@@ lines ${visStart + 1}\u2013$visEnd @@"))
        for (i in visStart until visEnd) {
            result.add(DiffLine(DiffLineType.CONTEXT, lines[i], i + 1, i + 1))
        }
        return result
    }

    // ================================================================
    //  Render diff into Swing components
    // ================================================================

    private fun renderDiff(diffLines: List<DiffLine>) {
        contentPanel.removeAll()

        if (diffLines.size <= 1) {
            contentPanel.add(JBLabel("No changes in this range").apply {
                foreground = JBColor.GRAY
                font = font.deriveFont(11f)
            })
            contentPanel.revalidate()
            contentPanel.repaint()
            return
        }

        val editorFontName = EditorColorsManager.getInstance().globalScheme.editorFontName
        val monoFont = Font(editorFontName, Font.PLAIN, 11)

        val diffPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
        }

        for (line in diffLines) {
            diffPanel.add(createDiffLineRow(line, monoFont))
        }

        // Wrap in a rounded bordered container
        val wrapper = RoundedPanel(6, UIUtil.getPanelBackground(), diffBorderColor).apply {
            layout = BorderLayout()
            border = JBUI.Borders.empty(4, 4)
        }
        wrapper.add(diffPanel, BorderLayout.CENTER)

        contentPanel.add(wrapper)
        contentPanel.revalidate()
        contentPanel.repaint()
    }

    private fun createDiffLineRow(line: DiffLine, monoFont: Font): JPanel {
        val bg = when (line.type) {
            DiffLineType.ADDED   -> addedBg
            DiffLineType.REMOVED -> removedBg
            DiffLineType.HEADER  -> headerBg
            DiffLineType.CONTEXT -> UIUtil.getPanelBackground()
        }

        val row = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = bg
            maximumSize = Dimension(Int.MAX_VALUE, 20)
            alignmentX = Component.LEFT_ALIGNMENT
        }

        if (line.type == DiffLineType.HEADER) {
            row.add(JBLabel(line.text).apply {
                font = monoFont.deriveFont(Font.ITALIC)
                foreground = headerFg
                border = JBUI.Borders.empty(1, 8)
            }, BorderLayout.CENTER)
        } else {
            // ── Line numbers ──
            val oldNum = line.oldLineNumber?.toString()?.padStart(4) ?: "    "
            val newNum = line.newLineNumber?.toString()?.padStart(4) ?: "    "
            row.add(JBLabel("$oldNum $newNum".replace(' ', '\u00A0')).apply {
                font = monoFont
                foreground = lineNumFg
                border = JBUI.Borders.empty(1, 4, 1, 4)
            }, BorderLayout.WEST)

            // ── Prefix + code text ──
            val prefix = when (line.type) {
                DiffLineType.ADDED   -> "+"
                DiffLineType.REMOVED -> "-"
                else                 -> " "
            }

            val displayText = "$prefix ${line.text}".replace("\t", "    ").replace(' ', '\u00A0')
            row.add(JBLabel(displayText).apply {
                font = monoFont
                foreground = when (line.type) {
                    DiffLineType.ADDED   -> addedFg
                    DiffLineType.REMOVED -> removedFg
                    else                 -> UIUtil.getLabelForeground()
                }
                border = JBUI.Borders.empty(1, 2, 1, 4)
            }, BorderLayout.CENTER)
        }

        return row
    }
}
