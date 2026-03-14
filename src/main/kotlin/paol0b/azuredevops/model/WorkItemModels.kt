package paol0b.azuredevops.model

import com.google.gson.annotations.SerializedName
import com.intellij.icons.AllIcons
import com.intellij.ui.JBColor
import paol0b.azuredevops.util.DateUtil
import java.awt.Color
import javax.swing.Icon

// region Work Item

/**
 * Represents a single Azure DevOps Work Item.
 * API: GET https://dev.azure.com/{org}/{project}/_apis/wit/workitems/{id}
 */
data class WorkItem(
    @SerializedName("id") val id: Int,
    @SerializedName("rev") val rev: Int?,
    @SerializedName("fields") val fields: WorkItemFields?,
    @SerializedName("url") val url: String?,
    @SerializedName("_links") val links: WorkItemLinks?,
    @SerializedName("relations") val relations: List<WorkItemRelation>?
) {
    fun getTitle(): String = fields?.title ?: "Untitled"

    fun getState(): String = fields?.state ?: "Unknown"

    fun getWorkItemType(): String = fields?.workItemType ?: "Unknown"

    fun getAssignedTo(): String? = fields?.assignedTo?.displayName

    fun getAssignedToImageUrl(): String? = fields?.assignedTo?.imageUrl

    fun getIterationPath(): String? = fields?.iterationPath

    fun getAreaPath(): String? = fields?.areaPath

    fun getPriority(): Int? = fields?.priority

    fun getStoryPoints(): Double? = fields?.storyPoints

    fun getTags(): String? = fields?.tags

    fun getDescription(): String? = fields?.description

    fun getCreatedDate(): String? = fields?.createdDate

    fun getChangedDate(): String? = fields?.changedDate

    fun getCreatedBy(): String? = fields?.createdBy?.displayName

    fun getChangedBy(): String? = fields?.changedBy?.displayName

    fun getBoardColumn(): String? = fields?.boardColumn

    fun getCommentCount(): Int = fields?.commentCount ?: 0

    fun getReason(): String? = fields?.reason

    fun getTeamProject(): String? = fields?.teamProject

    fun getRelativeDate(): String = DateUtil.relativeDate(fields?.changedDate ?: fields?.createdDate)

    fun getCreatedRelativeDate(): String = DateUtil.relativeDate(fields?.createdDate)

    fun getWebUrl(): String = links?.html?.href ?: ""

    fun getStateColor(): JBColor = stateColor(getState())

    fun getTypeColor(): JBColor = typeColor(getWorkItemType())

    fun getTypeIcon(): Icon = typeIcon(getWorkItemType())

    fun getPriorityColor(): JBColor? = priorityColor(getPriority())

    companion object {
        fun stateColor(state: String): JBColor = when (state.lowercase()) {
            "new", "to do" -> JBColor(Color(0x0969DA), Color(0x58A6FF))
            "active", "in progress", "committed", "doing" -> JBColor(Color(0x2DA44E), Color(0x3FB950))
            "resolved" -> JBColor(Color(0x8250DF), Color(0xA371F7))
            "closed", "done" -> JBColor(Color(0x6E7781), Color(0x8B949E))
            "removed" -> JBColor(Color(0xCF222E), Color(0xF85149))
            else -> JBColor(Color(0xBF8700), Color(0xD29922))
        }

        fun typeColor(type: String): JBColor = when (type.lowercase()) {
            "bug" -> JBColor(Color(0xCF222E), Color(0xF85149))
            "task" -> JBColor(Color(0xBF8700), Color(0xD29922))
            "user story", "product backlog item" -> JBColor(Color(0x0969DA), Color(0x58A6FF))
            "feature" -> JBColor(Color(0x8250DF), Color(0xA371F7))
            "epic" -> JBColor(Color(0xE16F24), Color(0xF0883E))
            else -> JBColor(Color(0x6E7781), Color(0x8B949E))
        }

        fun typeIcon(type: String): Icon = when (type.lowercase()) {
            "bug" -> AllIcons.Nodes.ErrorMark
            "task" -> AllIcons.Nodes.Tag
            "user story", "product backlog item" -> AllIcons.FileTypes.Text
            "feature" -> AllIcons.Nodes.Module
            "epic" -> AllIcons.Hierarchy.Supertypes
            else -> AllIcons.Nodes.Tag
        }

        fun priorityColor(priority: Int?): JBColor? = when (priority) {
            1 -> JBColor(Color(0xCF222E), Color(0xF85149))
            2 -> JBColor(Color(0xBF8700), Color(0xD29922))
            3 -> JBColor(Color(0x2DA44E), Color(0x3FB950))
            4 -> JBColor(Color(0x6E7781), Color(0x8B949E))
            else -> null
        }
    }
}

data class WorkItemFields(
    @SerializedName("System.Id") val id: Int?,
    @SerializedName("System.Title") val title: String?,
    @SerializedName("System.State") val state: String?,
    @SerializedName("System.WorkItemType") val workItemType: String?,
    @SerializedName("System.AssignedTo") val assignedTo: IdentityRef?,
    @SerializedName("System.IterationPath") val iterationPath: String?,
    @SerializedName("System.AreaPath") val areaPath: String?,
    @SerializedName("System.CreatedDate") val createdDate: String?,
    @SerializedName("System.ChangedDate") val changedDate: String?,
    @SerializedName("System.CreatedBy") val createdBy: IdentityRef?,
    @SerializedName("System.ChangedBy") val changedBy: IdentityRef?,
    @SerializedName("System.Description") val description: String?,
    @SerializedName("System.Tags") val tags: String?,
    @SerializedName("System.Reason") val reason: String?,
    @SerializedName("System.BoardColumn") val boardColumn: String?,
    @SerializedName("System.CommentCount") val commentCount: Int?,
    @SerializedName("System.TeamProject") val teamProject: String?,
    @SerializedName("Microsoft.VSTS.Common.Priority") val priority: Int?,
    @SerializedName("Microsoft.VSTS.Scheduling.StoryPoints") val storyPoints: Double?
)

data class IdentityRef(
    @SerializedName("displayName") val displayName: String?,
    @SerializedName("uniqueName") val uniqueName: String?,
    @SerializedName("id") val id: String?,
    @SerializedName("imageUrl") val imageUrl: String?
)

data class WorkItemRelation(
    @SerializedName("rel") val rel: String?,
    @SerializedName("url") val url: String?,
    @SerializedName("attributes") val attributes: Map<String, Any>?
)

data class WorkItemLinks(
    @SerializedName("html") val html: WorkItemHtmlLink?
)

data class WorkItemHtmlLink(
    @SerializedName("href") val href: String?
)

// endregion

// region WIQL

data class WiqlRequest(
    @SerializedName("query") val query: String
)

data class WiqlResponse(
    @SerializedName("workItems") val workItems: List<WiqlWorkItemRef>?,
    @SerializedName("columns") val columns: List<WiqlColumn>?
)

data class WiqlWorkItemRef(
    @SerializedName("id") val id: Int,
    @SerializedName("url") val url: String?
)

data class WiqlColumn(
    @SerializedName("referenceName") val referenceName: String?,
    @SerializedName("name") val name: String?
)

// endregion

// region Work Item List Response

data class WorkItemListResponse(
    @SerializedName("value") val value: List<WorkItem>?,
    @SerializedName("count") val count: Int?
)

// endregion

// region Work Item Types

data class WorkItemType(
    @SerializedName("name") val name: String?,
    @SerializedName("description") val description: String?,
    @SerializedName("color") val color: String?,
    @SerializedName("icon") val icon: WorkItemTypeIcon?,
    @SerializedName("states") val states: List<WorkItemTypeState>?
)

data class WorkItemTypeIcon(
    @SerializedName("id") val id: String?,
    @SerializedName("url") val url: String?
)

data class WorkItemTypeState(
    @SerializedName("name") val name: String?,
    @SerializedName("color") val color: String?,
    @SerializedName("category") val category: String?
)

data class WorkItemTypeListResponse(
    @SerializedName("value") val value: List<WorkItemType>?,
    @SerializedName("count") val count: Int?
)

// endregion

// region Iterations / Sprints

data class TeamIteration(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("path") val path: String?,
    @SerializedName("attributes") val attributes: IterationAttributes?
) {
    fun getDateRange(): String {
        val start = attributes?.startDate?.take(10) ?: return ""
        val end = attributes.finishDate?.take(10) ?: return start
        return "$start → $end"
    }

    fun isCurrent(): Boolean = attributes?.timeFrame == "current"
}

data class IterationAttributes(
    @SerializedName("startDate") val startDate: String?,
    @SerializedName("finishDate") val finishDate: String?,
    @SerializedName("timeFrame") val timeFrame: String?
)

data class TeamIterationListResponse(
    @SerializedName("value") val value: List<TeamIteration>?,
    @SerializedName("count") val count: Int?
)

// endregion

// region Work Item Comments

data class WorkItemComment(
    @SerializedName("id") val id: Int?,
    @SerializedName("text") val text: String?,
    @SerializedName("createdBy") val createdBy: IdentityRef?,
    @SerializedName("createdDate") val createdDate: String?,
    @SerializedName("modifiedBy") val modifiedBy: IdentityRef?,
    @SerializedName("modifiedDate") val modifiedDate: String?
) {
    fun getRelativeDate(): String = DateUtil.relativeDate(createdDate)
}

data class WorkItemCommentListResponse(
    @SerializedName("totalCount") val totalCount: Int?,
    @SerializedName("count") val count: Int?,
    @SerializedName("comments") val comments: List<WorkItemComment>?
)

// endregion

// region JSON Patch Operations (for create/update)

data class JsonPatchOperation(
    @SerializedName("op") val op: String,
    @SerializedName("path") val path: String,
    @SerializedName("value") val value: Any?
)

// endregion

// region Git Refs (for branch creation)

data class GitRefUpdate(
    @SerializedName("name") val name: String,
    @SerializedName("oldObjectId") val oldObjectId: String,
    @SerializedName("newObjectId") val newObjectId: String
)

data class GitRefUpdateResponse(
    @SerializedName("value") val value: List<GitRefUpdateResult>?
)

data class GitRefUpdateResult(
    @SerializedName("name") val name: String?,
    @SerializedName("success") val success: Boolean?,
    @SerializedName("updateStatus") val updateStatus: String?
)

// endregion
