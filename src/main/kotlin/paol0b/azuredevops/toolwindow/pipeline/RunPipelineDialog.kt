package paol0b.azuredevops.toolwindow.pipeline

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.BuildDefinition
import paol0b.azuredevops.services.AzureDevOpsApiClient
import java.awt.BorderLayout
import java.awt.Dimension
import javax.swing.*

/**
 * Dialog for running (queueing) a new pipeline build.
 * Presents a definition selector and an optional branch field.
 */
class RunPipelineDialog(
    private val project: Project,
    initialDefinitions: List<BuildDefinition>
) : DialogWrapper(project) {

    private val logger = Logger.getInstance(RunPipelineDialog::class.java)

    private val definitionComboBox = ComboBox<BuildDefinition>()
    private val branchField = JBTextField().apply {
        emptyText.text = "Leave empty for default branch"
        toolTipText = "Source branch (e.g., refs/heads/main). Leave empty for the pipeline's default."
    }

    private var definitions: List<BuildDefinition> = initialDefinitions

    init {
        title = "Run Pipeline"
        setOKButtonText("Run")
        init()
        loadDefinitions()
    }

    override fun createCenterPanel(): JComponent {
        val panel = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 12, 8, 12)
            preferredSize = Dimension(420, 150)
        }

        // Definition selector
        panel.add(JBLabel("Pipeline:").apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(4)
        })
        definitionComboBox.apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 32)
            renderer = object : DefaultListCellRenderer() {
                override fun getListCellRendererComponent(
                    list: JList<*>?, value: Any?, index: Int,
                    isSelected: Boolean, cellHasFocus: Boolean
                ): java.awt.Component {
                    val component = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus)
                    if (value is BuildDefinition) {
                        text = value.getDisplayName()
                    }
                    return component
                }
            }
        }
        // Populate with initial definitions
        definitions.sortedBy { it.name }.forEach { definitionComboBox.addItem(it) }
        panel.add(definitionComboBox)

        panel.add(Box.createVerticalStrut(12))

        // Branch field
        panel.add(JBLabel("Branch (optional):").apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            border = JBUI.Borders.emptyBottom(4)
        })
        branchField.apply {
            alignmentX = JComponent.LEFT_ALIGNMENT
            maximumSize = Dimension(Int.MAX_VALUE, 32)
        }
        panel.add(branchField)

        return panel
    }

    /**
     * If definitions were empty on init, try loading them now.
     */
    private fun loadDefinitions() {
        if (definitions.isNotEmpty()) return

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                definitions = apiClient.getBuildDefinitions().sortedBy { it.name }

                ApplicationManager.getApplication().invokeLater {
                    definitionComboBox.removeAllItems()
                    definitions.forEach { definitionComboBox.addItem(it) }
                }
            } catch (e: Exception) {
                logger.warn("Failed to load definitions in dialog: ${e.message}")
            }
        }
    }

    fun getSelectedDefinitionId(): Int? {
        return (definitionComboBox.selectedItem as? BuildDefinition)?.id
    }

    fun getSelectedBranch(): String? {
        val text = branchField.text.trim()
        if (text.isBlank()) return null
        // If user didn't specify refs/heads/ prefix, add it
        return if (text.startsWith("refs/")) text else "refs/heads/$text"
    }
}
