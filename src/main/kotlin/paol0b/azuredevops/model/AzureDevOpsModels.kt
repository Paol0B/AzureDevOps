package paol0b.azuredevops.model

import com.google.gson.annotations.SerializedName

/**
 * Rappresenta la configurazione dell'account Azure DevOps
 */
data class AzureDevOpsConfig(
    val organization: String = "",
    val project: String = "",
    val repository: String = "",
    val personalAccessToken: String = ""
) {
    fun isValid(): Boolean {
        return organization.isNotBlank() &&
                project.isNotBlank() &&
                repository.isNotBlank() &&
                personalAccessToken.isNotBlank()
    }
}

/**
 * Request per creare una Pull Request
 */
data class CreatePullRequestRequest(
    val sourceRefName: String,
    val targetRefName: String,
    val title: String,
    val description: String = ""
)

/**
 * Response dalla creazione di una Pull Request
 */
data class PullRequestResponse(
    @SerializedName("pullRequestId")
    val pullRequestId: Int,
    val title: String,
    val description: String?,
    val sourceRefName: String,
    val targetRefName: String,
    val status: String,
    @SerializedName("createdBy")
    val createdBy: CreatedBy?,
    @SerializedName("creationDate")
    val creationDate: String?,
    @SerializedName("url")
    val url: String?
)

/**
 * Pull Request completa con tutti i dettagli
 */
data class PullRequest(
    @SerializedName("pullRequestId")
    val pullRequestId: Int,
    val title: String,
    val description: String?,
    val sourceRefName: String,
    val targetRefName: String,
    val status: PullRequestStatus,
    @SerializedName("createdBy")
    val createdBy: User?,
    @SerializedName("creationDate")
    val creationDate: String?,
    @SerializedName("closedDate")
    val closedDate: String?,
    @SerializedName("mergeStatus")
    val mergeStatus: String?,
    @SerializedName("isDraft")
    val isDraft: Boolean?,
    @SerializedName("reviewers")
    val reviewers: List<Reviewer>?,
    @SerializedName("labels")
    val labels: List<Label>?,
    @SerializedName("url")
    val url: String?,
    @SerializedName("repository")
    val repository: Repository?,
    @SerializedName("lastMergeSourceCommit")
    val lastMergeSourceCommit: CommitRef?,
    @SerializedName("lastMergeTargetCommit")
    val lastMergeTargetCommit: CommitRef?
) {
    fun getWebUrl(): String {
        return url ?: ""
    }
    
    fun getSourceBranchName(): String = sourceRefName.removePrefix("refs/heads/")
    fun getTargetBranchName(): String = targetRefName.removePrefix("refs/heads/")
    
    fun isActive(): Boolean = status == PullRequestStatus.Active
    fun isMerged(): Boolean = status == PullRequestStatus.Completed
    fun isAbandoned(): Boolean = status == PullRequestStatus.Abandoned
}

/**
 * Stato della Pull Request
 */
enum class PullRequestStatus {
    @SerializedName("notSet")
    NotSet,
    @SerializedName("active")
    Active,
    @SerializedName("abandoned")
    Abandoned,
    @SerializedName("completed")
    Completed;
    
    fun getDisplayName(): String = when(this) {
        NotSet -> "Not Set"
        Active -> "Active"
        Abandoned -> "Abandoned"
        Completed -> "Completed"
    }
}

/**
 * Reviewer di una PR
 */
data class Reviewer(
    val id: String?,
    val displayName: String?,
    val uniqueName: String?,
    @SerializedName("imageUrl")
    val imageUrl: String?,
    val vote: Int?,
    @SerializedName("isRequired")
    val isRequired: Boolean?
) {
    fun getVoteStatus(): ReviewerVote = when(vote) {
        10 -> ReviewerVote.Approved
        5 -> ReviewerVote.ApprovedWithSuggestions
        0 -> ReviewerVote.NoVote
        -5 -> ReviewerVote.WaitingForAuthor
        -10 -> ReviewerVote.Rejected
        else -> ReviewerVote.NoVote
    }
}

enum class ReviewerVote {
    Approved,
    ApprovedWithSuggestions,
    NoVote,
    WaitingForAuthor,
    Rejected;
    
    fun getDisplayName(): String = when(this) {
        Approved -> "✓ Approved"
        ApprovedWithSuggestions -> "✓ Approved with suggestions"
        NoVote -> "○ No vote"
        WaitingForAuthor -> "⚠ Waiting for author"
        Rejected -> "✗ Rejected"
    }
}

/**
 * Label/Tag di una PR
 */
data class Label(
    val id: String?,
    val name: String?,
    val active: Boolean?
)

/**
 * Repository info
 */
data class Repository(
    val id: String?,
    val name: String?,
    @SerializedName("project")
    val project: Project?
)

/**
 * Project info
 */
data class Project(
    val id: String?,
    val name: String?
)

/**
 * User/CreatedBy info
 */
data class User(
    val id: String?,
    val displayName: String?,
    val uniqueName: String?,
    @SerializedName("imageUrl")
    val imageUrl: String?
)

data class CreatedBy(
    val displayName: String?,
    val uniqueName: String?
)

/**
 * Response lista PR
 */
data class PullRequestListResponse(
    val value: List<PullRequest>,
    val count: Int?
)

/**
 * Rappresenta un branch Git
 */
data class GitBranch(
    val name: String,
    val displayName: String
) {
    companion object {
        fun fromRefName(refName: String): GitBranch {
            val displayName = refName.removePrefix("refs/heads/")
            return GitBranch(refName, displayName)
        }
    }
}

/**
 * Risposta errore da Azure DevOps API
 */
data class AzureDevOpsError(
    val message: String?,
    @SerializedName("typeKey")
    val typeKey: String?,
    @SerializedName("errorCode")
    val errorCode: Int?
)

data class AzureDevOpsErrorResponse(
    @SerializedName("\$id")
    val id: String?,
    val innerException: String?,
    val message: String?,
    @SerializedName("typeName")
    val typeName: String?,
    @SerializedName("typeKey")
    val typeKey: String?,
    @SerializedName("errorCode")
    val errorCode: Int?,
    @SerializedName("eventId")
    val eventId: Int?
)

/**
 * Thread di commenti in una PR
 */
data class CommentThread(
    val id: Int?,
    @SerializedName("pullRequestThreadContext")
    val pullRequestThreadContext: ThreadContext?,
    val comments: List<Comment>?,
    val status: ThreadStatus?,
    @SerializedName("threadContext")
    val threadContext: ThreadContext?,
    @SerializedName("isDeleted")
    val isDeleted: Boolean?
) {
    /**
     * Ottiene il path del file, cercando in pullRequestThreadContext prima e poi in threadContext
     */
    fun getFilePath(): String? = pullRequestThreadContext?.filePath ?: threadContext?.filePath
    
    /**
     * Ottiene la riga di inizio, cercando in pullRequestThreadContext prima e poi in threadContext
     */
    fun getRightFileStart(): Int? = pullRequestThreadContext?.rightFileStart?.line ?: threadContext?.rightFileStart?.line
    
    /**
     * Ottiene la riga di fine, cercando in pullRequestThreadContext prima e poi in threadContext
     */
    fun getRightFileEnd(): Int? = pullRequestThreadContext?.rightFileEnd?.line ?: threadContext?.rightFileEnd?.line
    
    fun isActive(): Boolean = status == ThreadStatus.Active
    fun isResolved(): Boolean = status == ThreadStatus.Fixed || status == ThreadStatus.Closed
}

/**
 * Contesto del thread (posizione nel file)
 */
data class ThreadContext(
    @SerializedName("filePath")
    val filePath: String?,
    @SerializedName("rightFileStart")
    val rightFileStart: LineInfo?,
    @SerializedName("rightFileEnd")
    val rightFileEnd: LineInfo?,
    @SerializedName("leftFileStart")
    val leftFileStart: LineInfo?,
    @SerializedName("leftFileEnd")
    val leftFileEnd: LineInfo?
)

data class LineInfo(
    val line: Int?,
    val offset: Int?
)

/**
 * Stato del thread
 */
enum class ThreadStatus {
    @SerializedName("unknown")
    Unknown,
    @SerializedName("active")
    Active,
    @SerializedName("fixed")
    Fixed,
    @SerializedName("wontFix")
    WontFix,
    @SerializedName("closed")
    Closed,
    @SerializedName("byDesign")
    ByDesign,
    @SerializedName("pending")
    Pending;
    
    fun getDisplayName(): String = when(this) {
        Unknown -> "Unknown"
        Active -> "Active"
        Fixed -> "Fixed"
        WontFix -> "Won't Fix"
        Closed -> "Closed"
        ByDesign -> "By Design"
        Pending -> "Pending"
    }
    
    /**
     * Converte lo status nel formato richiesto dall'API Azure DevOps
     */
    fun toApiValue(): String = when(this) {
        Unknown -> "unknown"
        Active -> "active"
        Fixed -> "fixed"
        WontFix -> "wontFix"
        Closed -> "closed"
        ByDesign -> "byDesign"
        Pending -> "pending"
    }
}

/**
 * Singolo commento
 */
data class Comment(
    val id: Int?,
    val content: String?,
    val author: User?,
    @SerializedName("publishedDate")
    val publishedDate: String?,
    @SerializedName("lastUpdatedDate")
    val lastUpdatedDate: String?,
    @SerializedName("lastContentUpdatedDate")
    val lastContentUpdatedDate: String?,
    @SerializedName("commentType")
    val commentType: String?,
    @SerializedName("isDeleted")
    val isDeleted: Boolean?
)

/**
 * Request per creare un commento
 */
data class CreateCommentRequest(
    val content: String,
    @SerializedName("parentCommentId")
    val parentCommentId: Int? = null,
    @SerializedName("commentType")
    val commentType: String = "text"
)

/**
 * Request per aggiornare lo stato di un thread
 * Azure DevOps API 7.2 richiede solo il campo status con il valore in minuscolo
 */
data class UpdateThreadStatusRequest(
    @SerializedName("status")
    val status: String
) {
    constructor(status: ThreadStatus) : this(status.toApiValue())
}

/**
 * Response lista thread
 */
data class CommentThreadListResponse(
    val value: List<CommentThread>,
    val count: Int?
)

/**
 * Riferimento a un commit
 */
data class CommitRef(
    @SerializedName("commitId")
    val commitId: String?,
    @SerializedName("url")
    val url: String?
)
