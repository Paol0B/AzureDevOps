package paol0b.azuredevops.services

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project
import com.intellij.util.xmlb.XmlSerializerUtil

/**
 * Project-level settings service for Azure DevOps plugin.
 * Stores all configurable options (polling intervals, future settings, etc.)
 */
@Service(Service.Level.PROJECT)
@State(
    name = "AzureDevOpsSettings",
    storages = [Storage("azureDevOpsSettings.xml")]
)
class AzureDevOpsSettingsService(private val project: Project) : PersistentStateComponent<AzureDevOpsSettingsService.State> {

    data class State(
        // --- Polling ---
        var pullRequestIntervalSeconds: Long = 30,
        var commentsIntervalSeconds: Long = 15,
        var timelineIntervalSeconds: Long = 15,
        var statusBarIntervalSeconds: Long = 60
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    companion object {
        fun getInstance(project: Project): AzureDevOpsSettingsService {
            return project.service()
        }
    }
}
