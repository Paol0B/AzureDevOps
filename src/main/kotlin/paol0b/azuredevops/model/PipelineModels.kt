package paol0b.azuredevops.model

import com.google.gson.annotations.SerializedName
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

// region Build / Pipeline Run

/**
 * Represents a single build (pipeline run) from Azure DevOps Build API.
 * API: GET https://dev.azure.com/{org}/{project}/_apis/build/builds
 */
data class PipelineBuild(
    @SerializedName("id") val id: Int,
    @SerializedName("buildNumber") val buildNumber: String?,
    @SerializedName("status") val status: BuildStatus?,
    @SerializedName("result") val result: BuildResult?,
    @SerializedName("definition") val definition: BuildDefinitionRef?,
    @SerializedName("requestedFor") val requestedFor: User?,
    @SerializedName("requestedBy") val requestedBy: User?,
    @SerializedName("sourceBranch") val sourceBranch: String?,
    @SerializedName("sourceVersion") val sourceVersion: String?,
    @SerializedName("queueTime") val queueTime: String?,
    @SerializedName("startTime") val startTime: String?,
    @SerializedName("finishTime") val finishTime: String?,
    @SerializedName("repository") val repository: BuildRepository?,
    @SerializedName("project") val project: Project?,
    @SerializedName("url") val url: String?,
    @SerializedName("_links") val links: BuildLinks?
) {
    fun getBranchName(): String = sourceBranch?.removePrefix("refs/heads/") ?: ""

    fun isSucceeded(): Boolean = result == BuildResult.Succeeded
    fun isFailed(): Boolean = result == BuildResult.Failed
    fun isPartiallySucceeded(): Boolean = result == BuildResult.PartiallySucceeded
    fun isCanceled(): Boolean = result == BuildResult.Canceled
    fun isRunning(): Boolean = status == BuildStatus.InProgress

    /**
     * Returns a human-readable relative date string (e.g., "today", "yesterday", "3 days ago").
     */
    fun getRelativeDate(): String {
        val dateStr = startTime ?: queueTime ?: return ""
        return try {
            val dateTime = OffsetDateTime.parse(dateStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            val buildDate = dateTime.toLocalDate()
            val today = LocalDate.now()
            val daysBetween = java.time.temporal.ChronoUnit.DAYS.between(buildDate, today)
            when {
                daysBetween == 0L -> "Today"
                daysBetween == 1L -> "Yesterday"
                daysBetween < 30L -> "$daysBetween days ago"
                else -> {
                    val formatter = DateTimeFormatter.ofPattern("MMM d, yyyy")
                    buildDate.format(formatter)
                }
            }
        } catch (e: DateTimeParseException) {
            dateStr.take(10) // fallback: show raw date portion
        }
    }

    /**
     * Returns a formatted start time string.
     */
    fun getFormattedStartTime(): String = formatDateTime(startTime)

    /**
     * Returns a formatted finish time string.
     */
    fun getFormattedFinishTime(): String = formatDateTime(finishTime)

    /**
     * Returns the total duration as a human-readable string.
     */
    fun getDuration(): String {
        val start = parseDateTime(startTime) ?: return ""
        val end = parseDateTime(finishTime) ?: return "running..."
        return formatDuration(Duration.between(start, end))
    }

    fun getDefinitionName(): String = definition?.name ?: "Unknown Pipeline"

    fun getWebUrl(): String {
        return links?.web?.href ?: ""
    }

    private fun formatDateTime(dateStr: String?): String {
        if (dateStr.isNullOrBlank()) return "—"
        return try {
            val dt = OffsetDateTime.parse(dateStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            dt.format(DateTimeFormatter.ofPattern("EEE MMM d, yyyy 'at' HH:mm:ss"))
        } catch (e: DateTimeParseException) {
            dateStr
        }
    }
}

data class BuildLinks(
    @SerializedName("web") val web: BuildLink?
)

data class BuildLink(
    @SerializedName("href") val href: String?
)

data class BuildRepository(
    @SerializedName("id") val id: String?,
    @SerializedName("name") val name: String?,
    @SerializedName("type") val type: String?
)

// endregion

// region Build Status & Result

enum class BuildStatus {
    @SerializedName("all") All,
    @SerializedName("inProgress") InProgress,
    @SerializedName("completed") Completed,
    @SerializedName("cancelling") Cancelling,
    @SerializedName("postponed") Postponed,
    @SerializedName("notStarted") NotStarted,
    @SerializedName("none") None;

    fun toApiValue(): String = when (this) {
        All -> "all"
        InProgress -> "inProgress"
        Completed -> "completed"
        Cancelling -> "cancelling"
        Postponed -> "postponed"
        NotStarted -> "notStarted"
        None -> "none"
    }
}

enum class BuildResult {
    @SerializedName("succeeded") Succeeded,
    @SerializedName("partiallySucceeded") PartiallySucceeded,
    @SerializedName("failed") Failed,
    @SerializedName("canceled") Canceled,
    @SerializedName("none") None;

    fun toApiValue(): String = when (this) {
        Succeeded -> "succeeded"
        PartiallySucceeded -> "partiallySucceeded"
        Failed -> "failed"
        Canceled -> "canceled"
        None -> "none"
    }

    fun getDisplayName(): String = when (this) {
        Succeeded -> "Succeeded"
        PartiallySucceeded -> "Partially Succeeded"
        Failed -> "Failed"
        Canceled -> "Canceled"
        None -> "None"
    }
}

// endregion

// region Build Definition

data class BuildDefinitionRef(
    @SerializedName("id") val id: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("path") val path: String?
)

data class BuildDefinition(
    @SerializedName("id") val id: Int?,
    @SerializedName("name") val name: String?,
    @SerializedName("path") val path: String?,
    @SerializedName("queueStatus") val queueStatus: String?
) {
    fun getDisplayName(): String = if (path.isNullOrBlank() || path == "\\") {
        name ?: "Unknown"
    } else {
        "$path\\$name"
    }
}

// endregion

// region Build Timeline (Stages, Jobs, Tasks)

/**
 * Build timeline containing all records (stages, jobs, tasks).
 * API: GET https://dev.azure.com/{org}/{project}/_apis/build/builds/{buildId}/timeline
 */
data class BuildTimeline(
    @SerializedName("records") val records: List<TimelineRecord>?,
    @SerializedName("id") val id: String?,
    @SerializedName("lastChangedBy") val lastChangedBy: String?,
    @SerializedName("lastChangedOn") val lastChangedOn: String?
) {
    /**
     * Returns records organized in a hierarchy: Stage → Job → Task.
     */
    fun getStages(): List<TimelineRecord> {
        return records?.filter { it.type == "Stage" }?.sortedBy { it.order } ?: emptyList()
    }

    fun getJobsForStage(stageId: String): List<TimelineRecord> {
        return records?.filter { it.type == "Job" && it.parentId == stageId }?.sortedBy { it.order } ?: emptyList()
    }

    fun getTasksForJob(jobId: String): List<TimelineRecord> {
        return records?.filter { it.type == "Task" && it.parentId == jobId }?.sortedBy { it.order } ?: emptyList()
    }

    /**
     * Returns top-level timeline records (no parentId), ordered by `order`.
     */
    fun getRootRecords(): List<TimelineRecord> {
        val all = records ?: return emptyList()
        val ids = all.mapNotNull { it.id }.toSet()
        return all.filter { it.parentId == null || (it.parentId != null && it.parentId !in ids) }
            .sortedBy { it.order }
    }

    /**
     * Returns child records for a parent id, ordered by `order`.
     */
    fun getChildren(parentId: String): List<TimelineRecord> {
        return records?.filter { it.parentId == parentId }?.sortedBy { it.order } ?: emptyList()
    }
}

/**
 * A single record in the build timeline. Can be a Stage, Job, or Task.
 */
data class TimelineRecord(
    @SerializedName("id") val id: String?,
    @SerializedName("parentId") val parentId: String?,
    @SerializedName("type") val type: String?, // "Stage", "Job", "Task", "Phase", "Checkpoint"
    @SerializedName("name") val name: String?,
    @SerializedName("state") val state: String?, // "completed", "inProgress", "pending"
    @SerializedName("result") val result: String?, // "succeeded", "failed", "canceled", "skipped", "abandoned"
    @SerializedName("startTime") val startTime: String?,
    @SerializedName("finishTime") val finishTime: String?,
    @SerializedName("order") val order: Int?,
    @SerializedName("log") val log: TimelineRecordLog?,
    @SerializedName("workerName") val workerName: String?,
    @SerializedName("errorCount") val errorCount: Int?,
    @SerializedName("warningCount") val warningCount: Int?,
    @SerializedName("percentComplete") val percentComplete: Int?
) {
    fun isSucceeded(): Boolean = result == "succeeded"
    fun isFailed(): Boolean = result == "failed"
    fun isCanceled(): Boolean = result == "canceled" || result == "abandoned"
    fun isSkipped(): Boolean = result == "skipped"
    fun isRunning(): Boolean = state == "inProgress"
    fun isPending(): Boolean = state == "pending"

    fun getDuration(): String {
        val start = parseDateTime(startTime) ?: return ""
        val end = parseDateTime(finishTime) ?: return "running..."
        return formatDuration(Duration.between(start, end))
    }

    fun hasLog(): Boolean = log?.id != null
}

data class TimelineRecordLog(
    @SerializedName("id") val id: Int?,
    @SerializedName("type") val type: String?,
    @SerializedName("url") val url: String?
)

// endregion

// region Build Logs

/**
 * Full build log content.
 * API: GET https://dev.azure.com/{org}/{project}/_apis/build/builds/{buildId}/logs/{logId}
 */
data class BuildLog(
    @SerializedName("id") val id: Int?,
    @SerializedName("lineCount") val lineCount: Int?,
    @SerializedName("url") val url: String?,
    @SerializedName("value") val value: List<String>?
)

// endregion

// region Queue / Run Pipeline

/**
 * Request body for queueing a new build.
 * API: POST https://dev.azure.com/{org}/{project}/_apis/build/builds
 */
data class QueueBuildRequest(
    @SerializedName("definition") val definition: QueueBuildDefinitionRef,
    @SerializedName("sourceBranch") val sourceBranch: String? = null,
    @SerializedName("parameters") val parameters: String? = null
)

data class QueueBuildDefinitionRef(
    @SerializedName("id") val id: Int
)

// endregion

// region List Response Wrappers

data class BuildListResponse(
    @SerializedName("value") val value: List<PipelineBuild>,
    @SerializedName("count") val count: Int?
)

data class BuildDefinitionListResponse(
    @SerializedName("value") val value: List<BuildDefinition>,
    @SerializedName("count") val count: Int?
)

// endregion

// region Utility Functions

private fun parseDateTime(dateStr: String?): OffsetDateTime? {
    if (dateStr.isNullOrBlank()) return null
    return try {
        OffsetDateTime.parse(dateStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
    } catch (e: DateTimeParseException) {
        null
    }
}

internal fun formatDuration(duration: Duration): String {
    val totalSeconds = duration.seconds
    return when {
        totalSeconds < 1 -> "< 1 s"
        totalSeconds < 60 -> "$totalSeconds s"
        totalSeconds < 3600 -> {
            val minutes = totalSeconds / 60
            val seconds = totalSeconds % 60
            "$minutes m ${seconds} s"
        }
        else -> {
            val hours = totalSeconds / 3600
            val minutes = (totalSeconds % 3600) / 60
            val seconds = totalSeconds % 60
            "$hours h ${minutes} m ${seconds} s"
        }
    }
}

// endregion
