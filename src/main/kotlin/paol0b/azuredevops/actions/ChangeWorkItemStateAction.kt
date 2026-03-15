package paol0b.azuredevops.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.ListPopup
import com.intellij.openapi.ui.popup.PopupStep
import com.intellij.openapi.ui.popup.util.BaseListPopupStep
import paol0b.azuredevops.model.JsonPatchOperation
import paol0b.azuredevops.model.WorkItem
import paol0b.azuredevops.services.AzureDevOpsApiClient
import paol0b.azuredevops.util.NotificationUtil
import java.awt.Component

/**
 * Shows a popup to change a work item's state.
 * Can be invoked from context menus in list panel or detail panel.
 */
object ChangeWorkItemStateAction {

    private val logger = Logger.getInstance(ChangeWorkItemStateAction::class.java)

    // Standard states for common work item types
    private val COMMON_STATES = listOf("New", "Active", "Resolved", "Closed", "Removed")

    /**
     * Show a popup allowing the user to select a new state for the work item.
     *
     * @param project The current project
     * @param workItem The work item to update
     * @param component The component to show the popup relative to
     * @param onUpdated Callback invoked on EDT after successful update
     */
    fun showStatePopup(
        project: Project,
        workItem: WorkItem,
        component: Component,
        onUpdated: ((WorkItem) -> Unit)? = null
    ) {
        val currentState = workItem.getState()
        val availableStates = COMMON_STATES.filter { it != currentState }

        val step = object : BaseListPopupStep<String>("Change State", availableStates) {
            override fun onChosen(selectedValue: String, finalChoice: Boolean): PopupStep<*>? {
                if (finalChoice) {
                    changeState(project, workItem, selectedValue, onUpdated)
                }
                return PopupStep.FINAL_CHOICE
            }

            override fun getTextFor(value: String): String = value
        }

        val popup: ListPopup = JBPopupFactory.getInstance().createListPopup(step)
        popup.showUnderneathOf(component)
    }

    private fun changeState(
        project: Project,
        workItem: WorkItem,
        newState: String,
        onUpdated: ((WorkItem) -> Unit)?
    ) {
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val apiClient = AzureDevOpsApiClient.getInstance(project)
                val operations = listOf(
                    JsonPatchOperation("replace", "/fields/System.State", newState)
                )
                val updated = apiClient.updateWorkItem(workItem.id, operations)

                ApplicationManager.getApplication().invokeLater {
                    NotificationUtil.info(project, "State Changed",
                        "#${workItem.id} → $newState")
                    onUpdated?.invoke(updated)
                }
            } catch (e: Exception) {
                logger.error("Failed to change state for work item #${workItem.id}", e)
                ApplicationManager.getApplication().invokeLater {
                    NotificationUtil.error(project, "State Change Failed",
                        "Could not change state: ${e.message?.take(100)}")
                }
            }
        }
    }
}
