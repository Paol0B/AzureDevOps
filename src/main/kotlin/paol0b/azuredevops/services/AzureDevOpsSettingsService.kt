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
        var statusBarIntervalSeconds: Long = 60,

        // --- PR list pagination ---
        // pullRequestPageSize: how many PRs to ask Azure DevOps for per HTTP call.
        // pullRequestMaxTotal: safety cap on the total accumulated across pages, so a
        // misconfigured account or runaway loop can never blow up the IDE on huge orgs.
        var pullRequestPageSize: Int = 200,
        var pullRequestMaxTotal: Int = 2000,

        // --- PR list project filter ---
        // Project ids that the user has chosen to scope the PR list to. Empty means
        // "no filter — use the org-wide/repo-scoped endpoint as before". Persisted across
        // restarts so the user's narrowed view of a large org is restored on reopen.
        var prFilterSelectedProjectIds: MutableList<String> = mutableListOf()
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
