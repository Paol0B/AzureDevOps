package paol0b.azuredevops.services

import paol0b.azuredevops.model.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * Computes aggregated PR metrics from a list of pull requests.
 * Stateless utility — call [compute] with the raw PR list.
 */
object PrMetricsService {

    private val isoFormatter = DateTimeFormatter.ofPattern("MMM d")

    fun compute(pullRequests: List<PullRequest>): PrMetrics {
        if (pullRequests.isEmpty()) return PrMetrics()

        val active = pullRequests.filter { it.isActive() }
        val completed = pullRequests.filter { it.isMerged() }
        val abandoned = pullRequests.filter { it.isAbandoned() }
        val drafts = pullRequests.filter { it.isDraft == true }

        // --- time-to-merge ---
        val mergeHours = completed.mapNotNull { pr ->
            val created = parseInstant(pr.creationDate) ?: return@mapNotNull null
            val closed = parseInstant(pr.closedDate) ?: return@mapNotNull null
            ChronoUnit.MINUTES.between(created, closed) / 60.0
        }.sorted()

        val avgMergeHours = mergeHours.averageOrZero()
        val medianMergeHours = mergeHours.medianOrZero()

        // --- reviewer stats ---
        val avgReviewers = pullRequests.map { (it.reviewers?.size ?: 0).toDouble() }.averageOrZero()

        // --- weekly volume ---
        val weeklyVolume = buildWeeklyBuckets(pullRequests) { parseInstant(it.creationDate) }

        // --- weekly merge trend (avg hours to merge per week) ---
        val weeklyMergeTrend = buildWeeklyMergeTrend(completed)

        // --- merges per week ---
        val timeWindowWeeks = timeWindowWeeks(pullRequests)
        val mergesPerWeek = if (timeWindowWeeks > 0) completed.size / timeWindowWeeks else 0.0

        // --- author leaderboard ---
        val authorLeaderboard = pullRequests
            .mapNotNull { it.createdBy }
            .groupBy { it.id ?: it.displayName ?: "Unknown" }
            .map { (_, users) ->
                val representative = users.first()
                LeaderboardEntry(
                    displayName = representative.displayName ?: "Unknown",
                    uniqueName = representative.uniqueName,
                    count = users.size,
                    imageUrl = representative.imageUrl
                )
            }
            .sortedByDescending { it.count }
            .take(10)

        // --- reviewer leaderboard ---
        val reviewerLeaderboard = pullRequests
            .flatMap { it.reviewers ?: emptyList() }
            .filter { (it.vote ?: 0) != 0 }
            .groupBy { it.id ?: it.displayName ?: "Unknown" }
            .map { (_, reviewers) ->
                val representative = reviewers.first()
                LeaderboardEntry(
                    displayName = representative.displayName ?: "Unknown",
                    uniqueName = representative.uniqueName,
                    count = reviewers.size,
                    imageUrl = representative.imageUrl
                )
            }
            .sortedByDescending { it.count }
            .take(10)

        // --- vote distribution ---
        val allVotes = pullRequests.flatMap { it.reviewers ?: emptyList() }.mapNotNull { it.vote }
        val voteDistribution = listOf(
            LabeledValue("Approved", allVotes.count { it == 10 }.toDouble()),
            LabeledValue("Approved w/ suggestions", allVotes.count { it == 5 }.toDouble()),
            LabeledValue("No vote", allVotes.count { it == 0 }.toDouble()),
            LabeledValue("Waiting for author", allVotes.count { it == -5 }.toDouble()),
            LabeledValue("Rejected", allVotes.count { it == -10 }.toDouble())
        ).filter { it.value > 0 }

        // --- status distribution ---
        val statusDistribution = listOf(
            LabeledValue("Active", active.size.toDouble()),
            LabeledValue("Completed", completed.size.toDouble()),
            LabeledValue("Abandoned", abandoned.size.toDouble())
        ).filter { it.value > 0 }

        // --- conflict & auto-complete rates ---
        val conflictRate = if (pullRequests.isNotEmpty())
            pullRequests.count { it.hasConflicts() }.toDouble() / pullRequests.size * 100 else 0.0
        val autoCompleteRate = if (pullRequests.isNotEmpty())
            pullRequests.count { it.hasAutoComplete() }.toDouble() / pullRequests.size * 100 else 0.0

        return PrMetrics(
            totalPrs = pullRequests.size,
            activePrs = active.size,
            completedPrs = completed.size,
            abandonedPrs = abandoned.size,
            draftPrs = drafts.size,
            avgHoursToMerge = avgMergeHours,
            medianHoursToMerge = medianMergeHours,
            avgReviewerCount = avgReviewers,
            mergesPerWeek = mergesPerWeek,
            weeklyVolume = weeklyVolume,
            authorLeaderboard = authorLeaderboard,
            reviewerLeaderboard = reviewerLeaderboard,
            voteDistribution = voteDistribution,
            conflictRate = conflictRate,
            autoCompleteRate = autoCompleteRate,
            weeklyMergeTrend = weeklyMergeTrend,
            statusDistribution = statusDistribution
        )
    }

    // ---- helpers ----

    private fun parseInstant(dateStr: String?): Instant? {
        if (dateStr.isNullOrBlank()) return null
        return try {
            Instant.parse(dateStr)
        } catch (_: Exception) {
            try {
                // Try ISO-8601 without trailing Z
                Instant.parse(dateStr.trimEnd('Z') + "Z")
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun buildWeeklyBuckets(
        pullRequests: List<PullRequest>,
        dateExtractor: (PullRequest) -> Instant?
    ): List<WeeklyBucket> {
        val zone = ZoneId.systemDefault()
        val dated = pullRequests.mapNotNull { pr ->
            dateExtractor(pr)?.let { it to pr }
        }
        if (dated.isEmpty()) return emptyList()

        val earliest = dated.minOf { it.first }.atZone(zone).toLocalDate()
        val latest = dated.maxOf { it.first }.atZone(zone).toLocalDate()

        // Create weekly buckets from earliest to latest
        val buckets = mutableListOf<Pair<LocalDate, Int>>()
        var weekStart = earliest.with(java.time.DayOfWeek.MONDAY)
        while (!weekStart.isAfter(latest)) {
            buckets.add(weekStart to 0)
            weekStart = weekStart.plusWeeks(1)
        }

        // Fill counts
        val bucketMap = buckets.toMap().toMutableMap()
        dated.forEach { (instant, _) ->
            val date = instant.atZone(zone).toLocalDate()
            val bucket = date.with(java.time.DayOfWeek.MONDAY)
            bucketMap[bucket] = (bucketMap[bucket] ?: 0) + 1
        }

        return bucketMap.toSortedMap().map { (date, count) ->
            WeeklyBucket(date.format(isoFormatter), count.toDouble())
        }
    }

    private fun buildWeeklyMergeTrend(completed: List<PullRequest>): List<WeeklyBucket> {
        val zone = ZoneId.systemDefault()
        val withDuration = completed.mapNotNull { pr ->
            val created = parseInstant(pr.creationDate) ?: return@mapNotNull null
            val closed = parseInstant(pr.closedDate) ?: return@mapNotNull null
            val hours = ChronoUnit.MINUTES.between(created, closed) / 60.0
            closed to hours
        }
        if (withDuration.isEmpty()) return emptyList()

        // Group by week
        val byWeek = withDuration.groupBy { (closed, _) ->
            closed.atZone(zone).toLocalDate().with(java.time.DayOfWeek.MONDAY)
        }

        return byWeek.toSortedMap().map { (weekStart, entries) ->
            val avgHours = entries.map { it.second }.average()
            WeeklyBucket(weekStart.format(isoFormatter), avgHours)
        }
    }

    private fun timeWindowWeeks(pullRequests: List<PullRequest>): Double {
        val dates = pullRequests.mapNotNull { parseInstant(it.creationDate) }
        if (dates.size < 2) return 1.0
        val days = ChronoUnit.DAYS.between(dates.min(), dates.max())
        return (days / 7.0).coerceAtLeast(1.0)
    }

    private fun List<Double>.averageOrZero(): Double =
        if (isEmpty()) 0.0 else average()

    private fun List<Double>.medianOrZero(): Double {
        if (isEmpty()) return 0.0
        val sorted = sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }
}
