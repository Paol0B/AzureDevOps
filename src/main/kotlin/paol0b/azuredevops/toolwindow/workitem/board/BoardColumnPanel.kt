package paol0b.azuredevops.toolwindow.workitem.board

import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import paol0b.azuredevops.model.WorkItem
import java.awt.*
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.Transferable
import java.awt.dnd.*
import javax.swing.*

/**
 * A single column in the Kanban board representing one state.
 * Accepts drag-and-drop of [BoardCardComponent] items.
 */
class BoardColumnPanel(
    private val project: Project,
    val stateName: String,
    items: List<WorkItem>,
    private val columnColor: Color,
    private val onItemDropped: (WorkItem, String) -> Unit
) : JPanel(BorderLayout()) {

    private val cardsPanel = JPanel().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    init {
        preferredSize = Dimension(JBUI.scale(250), 0)
        minimumSize = Dimension(JBUI.scale(200), JBUI.scale(100))
        isOpaque = true
        background = UIUtil.getPanelBackground()
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(JBColor.border(), 1),
            JBUI.Borders.empty(0)
        )

        // Header with colored top bar
        val header = JPanel(BorderLayout()).apply {
            isOpaque = true
            background = columnColor
            preferredSize = Dimension(0, JBUI.scale(32))
            border = JBUI.Borders.empty(4, 10)

            val titleLabel = JBLabel("$stateName (${items.size})").apply {
                foreground = Color.WHITE
                font = UIUtil.getLabelFont().deriveFont(Font.BOLD, 12f)
            }
            add(titleLabel, BorderLayout.CENTER)
        }
        add(header, BorderLayout.NORTH)

        // Cards area
        items.forEach { item ->
            val card = BoardCardComponent(project, item)
            setupDragSource(card)
            cardsPanel.add(card)
            cardsPanel.add(Box.createVerticalStrut(JBUI.scale(4)))
        }

        val scrollPane = JBScrollPane(cardsPanel).apply {
            border = JBUI.Borders.empty(4)
            verticalScrollBar.unitIncrement = 16
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
        }
        add(scrollPane, BorderLayout.CENTER)

        // Set up drop target
        setupDropTarget()
    }

    private fun setupDragSource(card: BoardCardComponent) {
        val dragSource = DragSource.getDefaultDragSource()
        dragSource.createDefaultDragGestureRecognizer(card, DnDConstants.ACTION_MOVE,
            DragGestureListener { event ->
                val transferable = WorkItemTransferable(card.workItem)
                event.startDrag(DragSource.DefaultMoveDrop, transferable)
            }
        )
    }

    private fun setupDropTarget() {
        DropTarget(this, DnDConstants.ACTION_MOVE, object : DropTargetAdapter() {
            override fun dragEnter(dtde: DropTargetDragEvent) {
                if (dtde.transferable.isDataFlavorSupported(WorkItemTransferable.FLAVOR)) {
                    dtde.acceptDrag(DnDConstants.ACTION_MOVE)
                    border = BorderFactory.createCompoundBorder(
                        BorderFactory.createLineBorder(columnColor, 2),
                        JBUI.Borders.empty(0)
                    )
                } else {
                    dtde.rejectDrag()
                }
            }

            override fun dragExit(dte: DropTargetEvent) {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border(), 1),
                    JBUI.Borders.empty(0)
                )
            }

            override fun drop(dtde: DropTargetDropEvent) {
                border = BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(JBColor.border(), 1),
                    JBUI.Borders.empty(0)
                )

                try {
                    if (dtde.transferable.isDataFlavorSupported(WorkItemTransferable.FLAVOR)) {
                        dtde.acceptDrop(DnDConstants.ACTION_MOVE)
                        val workItem = dtde.transferable.getTransferData(WorkItemTransferable.FLAVOR) as WorkItem
                        dtde.dropComplete(true)
                        onItemDropped(workItem, stateName)
                    } else {
                        dtde.rejectDrop()
                    }
                } catch (e: Exception) {
                    dtde.rejectDrop()
                }
            }
        })
    }
}

/**
 * Transferable wrapper for work items during drag-and-drop.
 */
class WorkItemTransferable(private val workItem: WorkItem) : Transferable {

    companion object {
        val FLAVOR = DataFlavor(WorkItem::class.java, "WorkItem")
    }

    override fun getTransferDataFlavors(): Array<DataFlavor> = arrayOf(FLAVOR)
    override fun isDataFlavorSupported(flavor: DataFlavor): Boolean = flavor == FLAVOR
    override fun getTransferData(flavor: DataFlavor): Any = workItem
}
