package paol0b.azuredevops.model

/**
 * Aggregated metrics computed from a collection of Pull Requests.
 */
data class PrMetrics(
    val totalPrs: Int = 0,
    val activePrs: Int = 0,
    val completedPrs: Int = 0,
    val abandonedPrs: Int = 0,
    val draftPrs: Int = 0,

    /** Average hours from PR creation to completion (merge). */
    val avgHoursToMerge: Double = 0.0,

    /** Median hours from PR creation to completion. */
    val medianHoursToMerge: Double = 0.0,

    /** Average number of reviewers per PR. */
    val avgReviewerCount: Double = 0.0,

    /** PRs merged per week (based on the analyzed time window). */
    val mergesPerWeek: Double = 0.0,

    /** PRs created per week over the time window. */
    val weeklyVolume: List<WeeklyBucket> = emptyList(),

    /** Top PR authors ranked by number of PRs created. */
    val authorLeaderboard: List<LeaderboardEntry> = emptyList(),

    /** Top reviewers ranked by number of reviews performed. */
    val reviewerLeaderboard: List<LeaderboardEntry> = emptyList(),

    /** Distribution of reviewer votes across all PRs. */
    val voteDistribution: List<LabeledValue> = emptyList(),

    /** Percentage of PRs that had merge conflicts. */
    val conflictRate: Double = 0.0,

    /** Percentage of PRs that used auto-complete. */
    val autoCompleteRate: Double = 0.0,

    /** Average hours to merge per week (trend). */
    val weeklyMergeTrend: List<WeeklyBucket> = emptyList(),

    /** PR status distribution for the donut chart. */
    val statusDistribution: List<LabeledValue> = emptyList()
)

data class WeeklyBucket(
    /** ISO week label, e.g. "Mar 3" */
    val label: String,
    val value: Double
)

data class LeaderboardEntry(
    val displayName: String,
    val uniqueName: String?,
    val count: Int,
    val imageUrl: String? = null
)

data class LabeledValue(
    val label: String,
    val value: Double
)
