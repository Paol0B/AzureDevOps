package paol0b.azuredevops.ui

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.*
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.util.NotificationUtil
import java.awt.Dimension
import javax.swing.*

/**
 * Dialog to create a new Work Item on Azure DevOps.
 */
class CreateWorkItemDialog(
    private val project: Project,
    private val workItemTypes: List<WorkItemType>,
    private val iterations: List<TeamIteration>
) : DialogWrapper(project) {

    private val logger = Logger.getInstance(CreateWorkItemDialog::class.java)
    private val apiClient = AzureDevOpsApiClient.getInstance(project)

    private val typeCombo = ComboBox(workItemTypes.mapNotNull { it.name }.toTypedArray()).apply {
        selectedItem = workItemTypes.firstOrNull { it.name == "Task" }?.name
            ?: workItemTypes.firstOrNull()?.name
    }
    private val titleField = JBTextField()
    private val descriptionArea = JBTextArea(5, 40).apply {
        lineWrap = true
        wrapStyleWord = true
    }
    private val assignedToField = JBTextField().apply {
        emptyText.text = "Search by name or email..."
    }
    private val iterationCombo = ComboBox<String>().apply {
        addItem("(Default)")
        iterations.forEach { iter ->
            val prefix = if (iter.isCurrent()) "* " else ""
            addItem("$prefix${iter.name}")
        }
        selectedIndex = 0
    }
    private val areaPathField = JBTextField()
    private val priorityCombo = ComboBox(arrayOf("1 - Critical", "2 - High", "3 - Medium", "4 - Low")).apply {
        selectedIndex = 2
    }
    private val tagsField = JBTextField().apply {
        emptyText.text = "Comma-separated tags..."
    }
    private val storyPointsField = JBTextField().apply {
        emptyText.text = "Story points (optional)"
    }

    private var createdWorkItem: WorkItem? = null

    init {
        title = "Create Work Item"
        setOKButtonText("Create")
        init()
    }

    override fun createCenterPanel(): JComponent {
        val formBuilder = FormBuilder.createFormBuilder()
            .addLabeledComponent("Type:", typeCombo)
            .addLabeledComponent("Title:", titleField)
            .addLabeledComponent("Description:", JScrollPane(descriptionArea).apply {
                preferredSize = Dimension(450, 120)
            })
            .addLabeledComponent("Assigned To:", assignedToField)
            .addLabeledComponent("Iteration:", iterationCombo)
            .addLabeledComponent("Area Path:", areaPathField)
            .addLabeledComponent("Priority:", priorityCombo)
            .addLabeledComponent("Tags:", tagsField)
            .addLabeledComponent("Story Points:", storyPointsField)

        return formBuilder.panel.apply {
            border = JBUI.Borders.empty(8)
            preferredSize = Dimension(500, 420)
        }
    }

    override fun doValidate(): ValidationInfo? {
        if (titleField.text.isBlank()) {
            return ValidationInfo("Title is required", titleField)
        }
        val sp = storyPointsField.text.trim()
        if (sp.isNotBlank()) {
            try {
                sp.toDouble()
            } catch (e: NumberFormatException) {
                return ValidationInfo("Story Points must be a number", storyPointsField)
            }
        }
        return null
    }

    override fun doOKAction() {
        val type = typeCombo.selectedItem as? String ?: return
        val operations = mutableListOf<JsonPatchOperation>()

        operations.add(JsonPatchOperation("add", "/fields/System.Title", titleField.text.trim()))

        val desc = descriptionArea.text.trim()
        if (desc.isNotBlank()) {
            operations.add(JsonPatchOperation("add", "/fields/System.Description", desc))
        }

        val assignedTo = assignedToField.text.trim()
        if (assignedTo.isNotBlank()) {
            operations.add(JsonPatchOperation("add", "/fields/System.AssignedTo", assignedTo))
        }

        val iterIdx = iterationCombo.selectedIndex
        if (iterIdx > 0 && iterIdx - 1 < iterations.size) {
            val iteration = iterations[iterIdx - 1]
            iteration.path?.let {
                operations.add(JsonPatchOperation("add", "/fields/System.IterationPath", it))
            }
        }

        val areaPath = areaPathField.text.trim()
        if (areaPath.isNotBlank()) {
            operations.add(JsonPatchOperation("add", "/fields/System.AreaPath", areaPath))
        }

        val priority = priorityCombo.selectedIndex + 1
        operations.add(JsonPatchOperation("add", "/fields/Microsoft.VSTS.Common.Priority", priority))

        val tags = tagsField.text.trim()
        if (tags.isNotBlank()) {
            operations.add(JsonPatchOperation("add", "/fields/System.Tags", tags))
        }

        val sp = storyPointsField.text.trim()
        if (sp.isNotBlank()) {
            try {
                operations.add(JsonPatchOperation("add", "/fields/Microsoft.VSTS.Scheduling.StoryPoints", sp.toDouble()))
            } catch (_: NumberFormatException) { }
        }

        isOKActionEnabled = false

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val created = apiClient.createWorkItem(type, operations)
                createdWorkItem = created
                ApplicationManager.getApplication().invokeLater {
                    NotificationUtil.info(project, "Work Item Created",
                        "${created.getWorkItemType()} #${created.id}: ${created.getTitle()}")
                    super.doOKAction()
                }
            } catch (e: Exception) {
                logger.error("Failed to create work item", e)
                ApplicationManager.getApplication().invokeLater {
                    isOKActionEnabled = true
                    setErrorText("Failed: ${e.message?.take(100)}")
                }
            }
        }
    }

    fun getCreatedWorkItem(): WorkItem? = createdWorkItem

    companion object {
        /**
         * Factory method that pre-loads types and iterations on a background thread,
         * then shows the dialog on the EDT.
         *
         * @param onCreated callback invoked on EDT with the created work item (or null if cancelled)
         */
        fun showDialog(project: Project, onCreated: ((WorkItem?) -> Unit)? = null): WorkItem? {
            // If called from EDT, load data in background first
            if (ApplicationManager.getApplication().isDispatchThread) {
                com.intellij.openapi.progress.ProgressManager.getInstance().run(
                    object : com.intellij.openapi.progress.Task.Backgroundable(
                        project, "Loading work item types...", true
                    ) {
                        override fun run(indicator: com.intellij.openapi.progress.ProgressIndicator) {
                            try {
                                val apiClient = AzureDevOpsApiClient.getInstance(project)
                                val types = apiClient.getWorkItemTypes().filter { type ->
                                    val name = type.name?.lowercase() ?: return@filter false
                                    name in listOf("bug", "task", "user story", "feature", "epic", "product backlog item", "issue")
                                }
                                val iterations = apiClient.getIterations()

                                ApplicationManager.getApplication().invokeLater {
                                    if (types.isEmpty()) {
                                        NotificationUtil.error(project, "Error", "No work item types available")
                                        onCreated?.invoke(null)
                                        return@invokeLater
                                    }
                                    val dialog = CreateWorkItemDialog(project, types, iterations)
                                    val result = if (dialog.showAndGet()) dialog.getCreatedWorkItem() else null
                                    onCreated?.invoke(result)
                                }
                            } catch (e: Exception) {
                                ApplicationManager.getApplication().invokeLater {
                                    NotificationUtil.error(project, "Error", "Failed to load work item types: ${e.message}")
                                    onCreated?.invoke(null)
                                }
                            }
                        }
                    })
                return null // result delivered via callback
            }

            // Non-EDT path (synchronous, for tests or background callers)
            val apiClient = AzureDevOpsApiClient.getInstance(project)
            val types = apiClient.getWorkItemTypes().filter { type ->
                val name = type.name?.lowercase() ?: return@filter false
                name in listOf("bug", "task", "user story", "feature", "epic", "product backlog item", "issue")
            }
            val iterations = apiClient.getIterations()
            if (types.isEmpty()) return null
            val dialog = CreateWorkItemDialog(project, types, iterations)
            return if (dialog.showAndGet()) dialog.getCreatedWorkItem() else null
        }
    }
}
