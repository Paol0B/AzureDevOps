package paol0b.azuredevops.toolwindow.pipeline.editor

import com.intellij.icons.AllIcons
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.BuildTimeline
import paol0b.azuredevops.model.PipelineBuild
import paol0b.azuredevops.model.TimelineRecord
import paol0b.azuredevops.services.PipelineTabService
import java.awt.*
import java.awt.geom.RoundRectangle2D
import java.beans.PropertyChangeListener
import javax.swing.*

/**
 * Editor that displays a visual stage diagram of a build pipeline,
 * showing stages as connected rounded rectangles (matching the Azure DevOps web UI style).
 */
class PipelineDiagramFileEditor(
    private val project: Project,
    private val file: PipelineDiagramVirtualFile,
    private val build: PipelineBuild,
    private val timeline: BuildTimeline
) : UserDataHolderBase(), FileEditor {

    private val logger = Logger.getInstance(PipelineDiagramFileEditor::class.java)
    private val mainPanel: JPanel

    init {
        mainPanel = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
        }

        // Header with title + tabs
        val headerPanel = JPanel(BorderLayout()).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(12, 16, 8, 16)
        }

        val titleLabel = JBLabel(
            "<html><span style='font-size:14px; color:gray;'>Pipeline Stage Diagram – " +
                    "${escapeHtml(build.getDefinitionName())} " +
                    "<span style='color:#0078D4;'>${build.buildNumber ?: build.id}</span></span></html>"
        )
        headerPanel.add(titleLabel, BorderLayout.CENTER)

        // Toolbar icons
        val toolbarPanel = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0)).apply {
            background = UIUtil.getPanelBackground()
        }
        toolbarPanel.add(JBLabel(AllIcons.Graph.Layout))
        toolbarPanel.add(JBLabel(AllIcons.General.Information))
        headerPanel.add(toolbarPanel, BorderLayout.EAST)

        mainPanel.add(headerPanel, BorderLayout.NORTH)

        // Tab bar (Stages | Tests)
        val tabBar = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply {
            background = UIUtil.getPanelBackground()
            border = JBUI.Borders.empty(0, 16)
        }
        val stagesTab = JButton("Stages").apply {
            isFocusPainted = false
            isContentAreaFilled = true
            border = JBUI.Borders.empty(6, 16)
            font = font.deriveFont(Font.BOLD, 12f)
        }
        val testsTab = JButton("Tests").apply {
            isFocusPainted = false
            isContentAreaFilled = false
            border = JBUI.Borders.empty(6, 16)
            font = font.deriveFont(Font.PLAIN, 12f)
            foreground = JBColor.GRAY
        }
        tabBar.add(stagesTab)
        tabBar.add(testsTab)

        val northPanel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            background = UIUtil.getPanelBackground()
            add(headerPanel)
            add(tabBar)
            add(JSeparator().apply { maximumSize = Dimension(Int.MAX_VALUE, 1) })
        }
        mainPanel.add(northPanel, BorderLayout.NORTH)

        // Diagram canvas
        val stages = timeline.getStages()
        val diagramPanel = PipelineStageDiagramPanel(stages)
        val scrollPane = JBScrollPane(diagramPanel).apply {
            border = JBUI.Borders.empty()
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED
            verticalScrollBarPolicy = ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED
        }
        mainPanel.add(scrollPane, BorderLayout.CENTER)
    }

    override fun getComponent(): JComponent = mainPanel
    override fun getPreferredFocusedComponent(): JComponent? = mainPanel
    override fun getName(): String = "Pipeline Diagram"
    override fun setState(state: FileEditorState) {}
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun getFile() = file

    override fun dispose() {
        PipelineTabService.getInstance(project).unregisterFile(file)
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    }
}

/**
 * Custom panel that paints the pipeline stages as a horizontal flowchart.
 */
private class PipelineStageDiagramPanel(
    private val stages: List<TimelineRecord>
) : JPanel() {

    companion object {
        private const val STAGE_WIDTH = 180
        private const val STAGE_HEIGHT = 50
        private const val ARROW_LENGTH = 40
        private const val PADDING = 40
        private const val ARC = 14
        private const val BORDER_WIDTH = 2

        private val SUCCEEDED_FILL = JBColor(Color(230, 255, 230), Color(28, 50, 28))
        private val SUCCEEDED_BORDER = JBColor(Color(50, 180, 50), Color(60, 200, 60))
        private val FAILED_FILL = JBColor(Color(255, 230, 230), Color(50, 28, 28))
        private val FAILED_BORDER = JBColor(Color(200, 50, 50), Color(220, 70, 70))
        private val RUNNING_FILL = JBColor(Color(230, 245, 255), Color(28, 40, 55))
        private val RUNNING_BORDER = JBColor(Color(50, 130, 220), Color(60, 160, 255))
        private val SKIPPED_FILL = JBColor(Color(240, 240, 240), Color(50, 50, 50))
        private val SKIPPED_BORDER = JBColor(Color(150, 150, 150), Color(100, 100, 100))
        private val CANCELED_FILL = JBColor(Color(255, 245, 220), Color(55, 45, 25))
        private val CANCELED_BORDER = JBColor(Color(200, 160, 50), Color(220, 180, 60))

        private val TEXT_COLOR = JBColor(Color(40, 40, 40), Color(220, 220, 220))
        private val ARROW_COLOR = JBColor(Color(150, 150, 150), Color(100, 100, 100))
        private val CHECK_COLOR = JBColor(Color(40, 160, 40), Color(60, 200, 60))
        private val CROSS_COLOR = JBColor(Color(200, 50, 50), Color(220, 70, 70))
    }

    init {
        isOpaque = false
        val totalWidth = if (stages.isEmpty()) 300
        else PADDING * 2 + stages.size * STAGE_WIDTH + (stages.size - 1) * ARROW_LENGTH
        val totalHeight = PADDING * 2 + STAGE_HEIGHT + 60 // extra space for labels
        preferredSize = Dimension(totalWidth, totalHeight.coerceAtLeast(200))
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_LCD_HRGB)

        if (stages.isEmpty()) {
            g2.color = JBColor.GRAY
            g2.font = g2.font.deriveFont(Font.ITALIC, 13f)
            g2.drawString("No stages in this pipeline.", PADDING, height / 2)
            return
        }

        // Center vertically
        val yCenter = height / 2

        for ((index, stage) in stages.withIndex()) {
            val x = PADDING + index * (STAGE_WIDTH + ARROW_LENGTH)
            val y = yCenter - STAGE_HEIGHT / 2

            // Draw arrow from previous stage
            if (index > 0) {
                val arrowStartX = x - ARROW_LENGTH
                val arrowEndX = x
                val arrowY = yCenter
                drawArrow(g2, arrowStartX, arrowY, arrowEndX, arrowY)
            }

            // Draw stage box
            drawStageBox(g2, x, y, stage)
        }
    }

    private fun drawStageBox(g2: Graphics2D, x: Int, y: Int, stage: TimelineRecord) {
        val (fillColor, borderColor) = when {
            stage.isSucceeded() -> SUCCEEDED_FILL to SUCCEEDED_BORDER
            stage.isFailed() -> FAILED_FILL to FAILED_BORDER
            stage.isRunning() -> RUNNING_FILL to RUNNING_BORDER
            stage.isCanceled() -> CANCELED_FILL to CANCELED_BORDER
            stage.isSkipped() -> SKIPPED_FILL to SKIPPED_BORDER
            else -> SKIPPED_FILL to SKIPPED_BORDER
        }

        val shape = RoundRectangle2D.Float(
            x.toFloat(), y.toFloat(),
            STAGE_WIDTH.toFloat(), STAGE_HEIGHT.toFloat(),
            ARC.toFloat(), ARC.toFloat()
        )

        // Fill
        g2.color = fillColor
        g2.fill(shape)

        // Border
        g2.color = borderColor
        g2.stroke = BasicStroke(BORDER_WIDTH.toFloat())
        g2.draw(shape)

        // Icon (check/cross/spinner)
        val iconX = x + 12
        val iconY = y + STAGE_HEIGHT / 2
        when {
            stage.isSucceeded() -> {
                g2.color = CHECK_COLOR
                g2.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(iconX, iconY, iconX + 5, iconY + 5)
                g2.drawLine(iconX + 5, iconY + 5, iconX + 13, iconY - 4)
            }
            stage.isFailed() -> {
                g2.color = CROSS_COLOR
                g2.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(iconX, iconY - 5, iconX + 10, iconY + 5)
                g2.drawLine(iconX, iconY + 5, iconX + 10, iconY - 5)
            }
            stage.isRunning() -> {
                g2.color = RUNNING_BORDER
                g2.stroke = BasicStroke(2f)
                g2.drawArc(iconX, iconY - 5, 10, 10, 30, 300)
            }
            stage.isCanceled() -> {
                g2.color = CANCELED_BORDER
                g2.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(iconX + 2, iconY, iconX + 10, iconY)
            }
            else -> {
                g2.color = SKIPPED_BORDER
                g2.stroke = BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                g2.drawLine(iconX + 1, iconY, iconX + 10, iconY)
            }
        }

        // Stage name text
        g2.color = TEXT_COLOR
        g2.font = g2.font.deriveFont(Font.PLAIN, 12f)
        val fm = g2.fontMetrics
        val textX = iconX + 18
        val textMaxWidth = STAGE_WIDTH - (textX - x) - 10
        val name = stage.name ?: "Stage"
        val truncatedName = truncateText(fm, name, textMaxWidth)
        g2.drawString(truncatedName, textX, y + STAGE_HEIGHT / 2 + fm.ascent / 2 - 1)

        // Duration below box
        val duration = stage.getDuration()
        if (duration.isNotBlank()) {
            g2.color = JBColor.GRAY
            g2.font = g2.font.deriveFont(Font.PLAIN, 10f)
            val durationFm = g2.fontMetrics
            val durationX = x + (STAGE_WIDTH - durationFm.stringWidth(duration)) / 2
            g2.drawString(duration, durationX, y + STAGE_HEIGHT + 16)
        }
    }

    private fun drawArrow(g2: Graphics2D, x1: Int, y1: Int, x2: Int, y2: Int) {
        g2.color = ARROW_COLOR
        g2.stroke = BasicStroke(2f)

        // Line
        g2.drawLine(x1, y1, x2 - 8, y2)

        // Arrowhead
        val arrowSize = 6
        val tipX = x2 - 2
        g2.fillPolygon(
            intArrayOf(tipX, tipX - arrowSize, tipX - arrowSize),
            intArrayOf(y2, y2 - arrowSize / 2, y2 + arrowSize / 2),
            3
        )
    }

    private fun truncateText(fm: FontMetrics, text: String, maxWidth: Int): String {
        if (fm.stringWidth(text) <= maxWidth) return text
        var truncated = text
        while (truncated.isNotEmpty() && fm.stringWidth("$truncated...") > maxWidth) {
            truncated = truncated.dropLast(1)
        }
        return "$truncated..."
    }
}
