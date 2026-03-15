package paol0b.azuredevops.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.JsonPatchOperation
import paol0b.azuredevops.model.TeamIteration
import paol0b.azuredevops.model.WorkItem
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.util.NotificationUtil
import java.awt.Dimension
import javax.swing.JComponent
import javax.swing.JScrollPane

/**
 * Dialog to edit an existing Work Item on Azure DevOps.
 * Only sends changed fields as PATCH operations.
 */
class EditWorkItemDialog(
    private val project: Project,
    private val workItem: WorkItem,
    private val iterations: List<TeamIteration>
) : DialogWrapper(project) {

    private val logger = Logger.getInstance(EditWorkItemDialog::class.java)
    private val apiClient = AzureDevOpsApiClient.getInstance(project)

    private val titleField = JBTextField(workItem.getTitle())
    private val descriptionArea = JBTextArea(5, 40).apply {
        text = workItem.getDescription() ?: ""
        lineWrap = true
        wrapStyleWord = true
    }
    private val assignedToField = JBTextField(workItem.getAssignedTo() ?: "")
    private val iterationCombo = ComboBox<String>().apply {
        addItem("(Unchanged)")
        iterations.forEach { iter ->
            val prefix = if (iter.isCurrent()) "* " else ""
            addItem("$prefix${iter.name}")
        }
        selectedIndex = 0
    }
    private val priorityCombo = ComboBox(arrayOf("1 - Critical", "2 - High", "3 - Medium", "4 - Low")).apply {
        selectedIndex = (workItem.getPriority() ?: 3) - 1
    }
    private val tagsField = JBTextField(workItem.getTags() ?: "")
    private val storyPointsField = JBTextField().apply {
        text = workItem.getStoryPoints()?.let { String.format("%.0f", it) } ?: ""
    }

    private var updatedWorkItem: WorkItem? = null

    // Track original values for change detection
    private val originalTitle = workItem.getTitle()
    private val originalDesc = workItem.getDescription() ?: ""
    private val originalAssignedTo = workItem.getAssignedTo() ?: ""
    private val originalPriority = workItem.getPriority() ?: 3
    private val originalTags = workItem.getTags() ?: ""
    private val originalStoryPoints = workItem.getStoryPoints()?.let { String.format("%.0f", it) } ?: ""

    init {
        title = "Edit ${workItem.getWorkItemType()} #${workItem.id}"
        setOKButtonText("Save")
        init()
    }

    override fun createCenterPanel(): JComponent {
        return FormBuilder.createFormBuilder()
            .addLabeledComponent("Title:", titleField)
            .addLabeledComponent("Description:", JScrollPane(descriptionArea).apply {
                preferredSize = Dimension(450, 120)
            })
            .addLabeledComponent("Assigned To:", assignedToField)
            .addLabeledComponent("Iteration:", iterationCombo)
            .addLabeledComponent("Priority:", priorityCombo)
            .addLabeledComponent("Tags:", tagsField)
            .addLabeledComponent("Story Points:", storyPointsField)
            .panel.apply {
                border = JBUI.Borders.empty(8)
                preferredSize = Dimension(500, 380)
            }
    }

    override fun doValidate(): ValidationInfo? {
        if (titleField.text.isBlank()) {
            return ValidationInfo("Title is required", titleField)
        }
        val sp = storyPointsField.text.trim()
        if (sp.isNotBlank()) {
            try { sp.toDouble() } catch (e: NumberFormatException) {
                return ValidationInfo("Story Points must be a number", storyPointsField)
            }
        }
        return null
    }

    override fun doOKAction() {
        val operations = mutableListOf<JsonPatchOperation>()

        val newTitle = titleField.text.trim()
        if (newTitle != originalTitle) {
            operations.add(JsonPatchOperation("replace", "/fields/System.Title", newTitle))
        }

        val newDesc = descriptionArea.text.trim()
        if (newDesc != originalDesc) {
            operations.add(JsonPatchOperation("replace", "/fields/System.Description", newDesc))
        }

        val newAssigned = assignedToField.text.trim()
        if (newAssigned != originalAssignedTo) {
            operations.add(JsonPatchOperation("replace", "/fields/System.AssignedTo", newAssigned))
        }

        val iterIdx = iterationCombo.selectedIndex
        if (iterIdx > 0 && iterIdx - 1 < iterations.size) {
            val iteration = iterations[iterIdx - 1]
            iteration.path?.let {
                operations.add(JsonPatchOperation("replace", "/fields/System.IterationPath", it))
            }
        }

        val newPriority = priorityCombo.selectedIndex + 1
        if (newPriority != originalPriority) {
            operations.add(JsonPatchOperation("replace", "/fields/Microsoft.VSTS.Common.Priority", newPriority))
        }

        val newTags = tagsField.text.trim()
        if (newTags != originalTags) {
            operations.add(JsonPatchOperation("replace", "/fields/System.Tags", newTags))
        }

        val newSp = storyPointsField.text.trim()
        if (newSp != originalStoryPoints && newSp.isNotBlank()) {
            try {
                operations.add(JsonPatchOperation("replace", "/fields/Microsoft.VSTS.Scheduling.StoryPoints", newSp.toDouble()))
            } catch (_: NumberFormatException) { }
        }

        if (operations.isEmpty()) {
            super.doOKAction()
            return
        }

        isOKActionEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val updated = apiClient.updateWorkItem(workItem.id, operations)
                updatedWorkItem = updated
                ApplicationManager.getApplication().invokeLater {
                    NotificationUtil.info(project, "Work Item Updated",
                        "${updated.getWorkItemType()} #${updated.id} updated successfully")
                    super.doOKAction()
                }
            } catch (e: Exception) {
                logger.error("Failed to update work item", e)
                ApplicationManager.getApplication().invokeLater {
                    isOKActionEnabled = true
                    setErrorText("Failed: ${e.message?.take(100)}")
                }
            }
        }
    }

    fun getUpdatedWorkItem(): WorkItem? = updatedWorkItem
}
