package paol0b.azuredevops.toolwindow.filters

import com.intellij.icons.AllIcons
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SearchTextField
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.awt.RelativePoint
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import javax.swing.*

/**
 * Utility object for creating filter selection popups,
 * modeled after ChooserPopupUtil from the JetBrains GitHub plugin.
 */
object FilterPopupUtil {

    private const val MULTI_SELECT_MAX_VISIBLE_ROWS = 12

    /**
     * Shows a multi-select popup (checkbox list) with Select All / Clear buttons and an
     * optional "exclude" toggle that inverts the selection.
     *
     * The result is delivered once, through [onApply], when the popup closes.
     *
     * @param component        The component to position the popup relative to
     * @param items            The items to choose from (displayed in this order)
     * @param presenter        Function to get display text for each item
     * @param initiallySelected Items checked when the popup opens
     * @param excludeInitially Initial value of the exclude toggle
     * @param excludeLabel     Label of the exclude toggle; null hides the toggle
     * @param onApply          Callback with the checked items (in [items] order) and the exclude flag
     */
    fun <T> showMultiSelectPopup(
        component: JComponent,
        items: List<T>,
        presenter: (T) -> String,
        initiallySelected: Collection<T>,
        excludeInitially: Boolean = false,
        excludeLabel: String? = "Exclude selected",
        onApply: (selected: List<T>, exclude: Boolean) -> Unit
    ) {
        val selection = LinkedHashSet(initiallySelected)

        val checkBoxList = CheckBoxList<T>()
        checkBoxList.setCheckBoxListListener { index, checked ->
            val item = checkBoxList.getItemAt(index) ?: return@setCheckBoxListListener
            if (checked) selection.add(item) else selection.remove(item)
        }

        fun rebuild() {
            checkBoxList.clear()
            items.forEach { item -> checkBoxList.addItem(item, presenter(item), selection.contains(item)) }
        }
        rebuild()

        val rowsToShow = items.size.coerceIn(1, MULTI_SELECT_MAX_VISIBLE_ROWS)
        val rowHeight = if (items.isEmpty()) JBUIScale.scale(24)
        else (checkBoxList.preferredSize.height + items.size - 1) / items.size
        val scrollPane = JBScrollPane(checkBoxList).apply {
            border = JBUI.Borders.empty()
            preferredSize = Dimension(JBUIScale.scale(260), rowsToShow * rowHeight + JBUIScale.scale(8))
        }

        val selectAll = JButton("Select All").apply {
            addActionListener {
                selection.clear()
                selection.addAll(items)
                rebuild()
            }
        }
        val clear = JButton("Clear").apply {
            addActionListener {
                selection.clear()
                rebuild()
            }
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUIScale.scale(4), 0)).apply {
            border = JBUI.Borders.empty(4)
            add(selectAll)
            add(clear)
        }

        val excludeCheckBox = excludeLabel?.let { JBCheckBox(it, excludeInitially) }
        val south = JPanel(BorderLayout()).apply {
            add(buttons, BorderLayout.NORTH)
            if (excludeCheckBox != null) {
                add(JPanel(FlowLayout(FlowLayout.LEFT, JBUIScale.scale(4), 0)).apply {
                    border = JBUI.Borders.empty(0, 4, 4, 4)
                    add(excludeCheckBox)
                }, BorderLayout.SOUTH)
            }
        }

        val content = JPanel(BorderLayout()).apply {
            add(scrollPane, BorderLayout.CENTER)
            add(south, BorderLayout.SOUTH)
        }

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, checkBoxList)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(false)
            .setResizable(true)
            .setCancelOnClickOutside(true)
            .setCancelOnWindowDeactivation(true)
            .addListener(object : JBPopupListener {
                override fun onClosed(event: LightweightWindowEvent) {
                    val ordered = items.filter { selection.contains(it) }
                    onApply(ordered, excludeCheckBox?.isSelected ?: false)
                }
            })
            .createPopup()

        val point = RelativePoint(component, Point(0, component.height + JBUIScale.scale(2)))
        popup.show(point)
    }

    /**
     * Shows a simple list selection popup for enum-based filters.
     *
     * @param component The component to position the popup relative to
     * @param title Popup title (null for no title)
     * @param items The list of items to choose from
     * @param presenter Function to get display text for each item
     * @param onSelected Callback when an item is selected
     */
    fun <T> showSimplePopup(
        component: JComponent,
        title: String? = null,
        items: List<T>,
        presenter: (T) -> String,
        onSelected: (T) -> Unit
    ) {
        val step = object : BaseListPopupStep<T>(title, items) {
            override fun getTextFor(value: T): String = presenter(value)

            override fun onChosen(selectedValue: T, finalChoice: Boolean): PopupStep<*>? {
                onSelected(selectedValue)
                return FINAL_CHOICE
            }
        }

        val popup = JBPopupFactory.getInstance().createListPopup(step)
        val point = RelativePoint(component, Point(0, component.height + JBUIScale.scale(2)))
        popup.show(point)
    }

    /**
     * Shows a searchable list popup with an icon for each item.
     * Used for Project and Repository filters.
     */
    fun <T> showSearchablePopup(
        component: JComponent,
        items: List<T>,
        presenter: (T) -> String,
        icon: Icon,
        onSelected: (T) -> Unit
    ) {
        val searchField = SearchTextField(false).apply {
            textEditor.emptyText.text = "Search"
            textEditor.border = JBUI.Borders.empty(4)
        }

        val listModel = DefaultListModel<T>()
        items.forEach { listModel.addElement(it) }

        val list = JBList(listModel).apply {
            cellRenderer = object : ColoredListCellRenderer<T>() {
                override fun customizeCellRenderer(
                    list: JList<out T>,
                    value: T?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean
                ) {
                    if (value == null) return
                    this.icon = icon
                    append(presenter(value), SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
            selectionMode = ListSelectionModel.SINGLE_SELECTION
        }

        val scrollPane = JBScrollPane(list).apply {
            preferredSize = Dimension(JBUIScale.scale(250), JBUIScale.scale(200))
            border = JBUI.Borders.empty()
        }

        val content = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                val query = searchField.text.trim().lowercase()
                listModel.clear()
                items.filter { item ->
                    query.isBlank() || presenter(item).lowercase().contains(query)
                }.forEach { listModel.addElement(it) }
            }
        })

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, searchField.textEditor)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(false)
            .setResizable(false)
            .createPopup()

        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 1) {
                    val selected = list.selectedValue
                    if (selected != null) {
                        onSelected(selected)
                        popup.closeOk(null)
                    }
                }
            }
        })

        val point = RelativePoint(component, Point(0, component.height + JBUIScale.scale(2)))
        popup.show(point)
    }

    /**
     * Shows a searchable user picker popup with avatar icons for the Author filter.
     *
     * @param component The component to position the popup relative to
     * @param users List of available users
     * @param avatarProvider Function to get an avatar icon for a user
     * @param onSelected Callback when a user is selected
     */
    fun showUserPopup(
        component: JComponent,
        users: List<PullRequestSearchValue.AuthorFilter>,
        avatarProvider: (PullRequestSearchValue.AuthorFilter) -> Icon,
        onSelected: (PullRequestSearchValue.AuthorFilter) -> Unit
    ) {
        val searchField = SearchTextField(false).apply {
            textEditor.emptyText.text = "Search"
            textEditor.border = JBUI.Borders.empty(4)
        }

        val listModel = DefaultListModel<PullRequestSearchValue.AuthorFilter>()
        users.forEach { listModel.addElement(it) }

        val list = JBList(listModel).apply {
            cellRenderer = object : ColoredListCellRenderer<PullRequestSearchValue.AuthorFilter>() {
                override fun customizeCellRenderer(
                    list: JList<out PullRequestSearchValue.AuthorFilter>,
                    value: PullRequestSearchValue.AuthorFilter?,
                    index: Int,
                    selected: Boolean,
                    hasFocus: Boolean
                ) {
                    if (value == null) return
                    icon = avatarProvider(value)
                    append(value.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                }
            }
            selectionMode = ListSelectionModel.SINGLE_SELECTION
        }

        val scrollPane = JBScrollPane(list).apply {
            preferredSize = Dimension(JBUIScale.scale(220), JBUIScale.scale(200))
            border = JBUI.Borders.empty()
        }

        val content = JPanel(BorderLayout()).apply {
            add(searchField, BorderLayout.NORTH)
            add(scrollPane, BorderLayout.CENTER)
        }

        // Search filtering
        searchField.addDocumentListener(object : com.intellij.ui.DocumentAdapter() {
            override fun textChanged(e: javax.swing.event.DocumentEvent) {
                val query = searchField.text.trim().lowercase()
                listModel.clear()
                users.filter { user ->
                    query.isBlank() ||
                    user.displayName.lowercase().contains(query) ||
                    (user.uniqueName?.lowercase()?.contains(query) == true)
                }.forEach { listModel.addElement(it) }
            }
        })

        val popup = JBPopupFactory.getInstance()
            .createComponentPopupBuilder(content, searchField.textEditor)
            .setRequestFocus(true)
            .setFocusable(true)
            .setMovable(false)
            .setResizable(false)
            .createPopup()

        list.addListSelectionListener { e ->
            if (!e.valueIsAdjusting) {
                val selected = list.selectedValue
                if (selected != null) {
                    onSelected(selected)
                    popup.closeOk(null)
                }
            }
        }

        // Also handle double-click (some users prefer double-click)
        list.addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 1) {
                    val selected = list.selectedValue
                    if (selected != null) {
                        onSelected(selected)
                        popup.closeOk(null)
                    }
                }
            }
        })

        val point = RelativePoint(component, Point(0, component.height + JBUIScale.scale(2)))
        popup.show(point)
    }
}
