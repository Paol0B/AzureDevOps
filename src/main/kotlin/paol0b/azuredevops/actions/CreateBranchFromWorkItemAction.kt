package paol0b.azuredevops.actions

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import paol0b.azuredevops.model.WorkItem
import paol0b.azuredevops.services.WorkItemBranchService
import java.awt.Dimension
import javax.swing.JComponent

/**
 * Shows a dialog to confirm branch name and create a branch from a work item.
 */
object CreateBranchFromWorkItemAction {

    /**
     * Show branch creation dialog and create the branch if confirmed.
     */
    fun execute(project: Project, workItem: WorkItem, onSuccess: (() -> Unit)? = null) {
        val branchService = WorkItemBranchService.getInstance(project)
        val defaultName = branchService.generateBranchName(workItem)

        val dialog = CreateBranchDialog(project, workItem, defaultName)
        if (dialog.showAndGet()) {
            val branchName = dialog.getBranchName()
            val pushToRemote = dialog.isPushToRemote()
            branchService.createBranchFromWorkItem(workItem, branchName, pushToRemote, onSuccess)
        }
    }

    private class CreateBranchDialog(
        project: Project,
        private val workItem: WorkItem,
        defaultBranchName: String
    ) : DialogWrapper(project) {

        private val branchNameField = JBTextField(defaultBranchName).apply {
            preferredSize = Dimension(400, 30)
        }
        private val pushCheckBox = JBCheckBox("Push to remote", true)

        init {
            title = "Create Branch from ${workItem.getWorkItemType()} #${workItem.id}"
            setOKButtonText("Create Branch")
            init()
        }

        override fun createCenterPanel(): JComponent {
            return FormBuilder.createFormBuilder()
                .addLabeledComponent("Work Item:", com.intellij.ui.components.JBLabel(
                    "${workItem.getWorkItemType()} #${workItem.id}: ${workItem.getTitle()}"
                ))
                .addLabeledComponent("Branch Name:", branchNameField)
                .addComponent(pushCheckBox)
                .panel.apply {
                    border = JBUI.Borders.empty(8)
                }
        }

        override fun doValidate(): com.intellij.openapi.ui.ValidationInfo? {
            val name = branchNameField.text.trim()
            if (name.isBlank()) {
                return com.intellij.openapi.ui.ValidationInfo("Branch name is required", branchNameField)
            }
            if (name.contains(" ")) {
                return com.intellij.openapi.ui.ValidationInfo("Branch name cannot contain spaces", branchNameField)
            }
            return null
        }

        fun getBranchName(): String = branchNameField.text.trim()
        fun isPushToRemote(): Boolean = pushCheckBox.isSelected
    }
}
