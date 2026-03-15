package paol0b.azuredevops.statusbar

import com.intellij.icons.AllIcons
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.wm.StatusBar
import com.intellij.openapi.wm.StatusBarWidget
import com.intellij.openapi.wm.ToolWindowManager
import paol0b.azuredevops.AzureDevOpsIcons
import paol0b.azuredevops.services.AzureDevOpsStatusBarService
import paol0b.azuredevops.services.AzureDevOpsStatusBarService.BuildStatusSummary
import javax.swing.Icon

class AzureDevOpsStatusBarWidget(private val project: Project) :
    StatusBarWidget,
    StatusBarWidget.MultipleTextValuesPresentation {

    private var myStatusBar: StatusBar? = null
    private val statusBarService = AzureDevOpsStatusBarService.getInstance(project)

    private val updateListener: () -> Unit = {
        myStatusBar?.updateWidget(ID())
    }

    override fun ID(): String = AzureDevOpsStatusBarWidgetFactory.WIDGET_ID

    override fun install(statusBar: StatusBar) {
        myStatusBar = statusBar
        statusBarService.addUpdateListener(updateListener)
        statusBarService.startPolling()
    }

    override fun dispose() {
        statusBarService.removeUpdateListener(updateListener)
        myStatusBar = null
    }

    override fun getPresentation(): StatusBarWidget.WidgetPresentation = this

    // --- MultipleTextValuesPresentation ---

    override fun getTooltipText(): String {
        val data = statusBarService.getData()
        return buildString {
            append("Azure DevOps\n")
            append("Build: ${data.buildStatus.name}\n")
            append("Pull Requests: ${data.activePrCount}\n")
            append("Work Items: ${data.activeWorkItemCount}")
        }
    }

    override fun getPopup(): JBPopup? {
        val data = statusBarService.getData()

        val items = listOf(
            "Build: ${data.buildStatus.name}" to "Azure DevOps Pipelines",
            "Pull Requests: ${data.activePrCount}" to "Azure DevOps PRs",
            "Work Items: ${data.activeWorkItemCount}" to "Azure DevOps Work Items"
        )

        return JBPopupFactory.getInstance()
            .createPopupChooserBuilder(items.map { it.first })
            .setTitle("Azure DevOps")
            .setItemChosenCallback { selected ->
                val toolWindowId = items.firstOrNull { it.first == selected }?.second ?: return@setItemChosenCallback
                activateToolWindow(toolWindowId)
            }
            .createPopup()
    }

    override fun getSelectedValue(): String {
        val data = statusBarService.getData()
        val buildSymbol = when (data.buildStatus) {
            BuildStatusSummary.Succeeded -> "\u2713"
            BuildStatusSummary.Failed -> "\u2717"
            BuildStatusSummary.PartiallySucceeded -> "!"
            BuildStatusSummary.InProgress -> "\u21BB"
            BuildStatusSummary.Unknown -> "-"
        }
        return "$buildSymbol | PR ${data.activePrCount} | WI ${data.activeWorkItemCount}"
    }

    override fun getIcon(): Icon? {
        val data = statusBarService.getData()
        return getBuildIcon(data.buildStatus)
    }

    private fun getBuildIcon(status: BuildStatusSummary): Icon = when (status) {
        BuildStatusSummary.Succeeded -> AllIcons.RunConfigurations.TestPassed
        BuildStatusSummary.Failed -> AllIcons.RunConfigurations.TestFailed
        BuildStatusSummary.PartiallySucceeded -> AllIcons.RunConfigurations.TestCustom
        BuildStatusSummary.InProgress -> AllIcons.Process.Step_1
        BuildStatusSummary.Unknown -> AzureDevOpsIcons.Logo
    }

    private fun activateToolWindow(id: String) {
        ToolWindowManager.getInstance(project).getToolWindow(id)?.activate(null)
    }
}
