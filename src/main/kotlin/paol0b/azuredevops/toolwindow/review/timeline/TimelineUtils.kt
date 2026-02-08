package paol0b.azuredevops.toolwindow.review.timeline

import java.text.SimpleDateFormat
import java.util.*

/**
 * Shared utility functions for the timeline components.
 */
object TimelineUtils {

    /**
     * Format a raw ISO-8601 timestamp into a human-readable "time ago" string.
     */
    fun formatTimeAgo(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            val formats = listOf(
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US),
                SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
            )
            var date: Date? = null
            for (fmt in formats) {
                fmt.timeZone = TimeZone.getTimeZone("UTC")
                try { date = fmt.parse(raw.take(30)); break } catch (_: Exception) {}
            }
            if (date == null) return raw.take(16).replace('T', ' ')

            val now = Date()
            val diffMs = now.time - date.time
            val diffMin = diffMs / (1000 * 60)
            val diffHrs = diffMs / (1000 * 60 * 60)
            val diffDays = diffMs / (1000 * 60 * 60 * 24)

            when {
                diffMin < 1 -> "just now"
                diffMin < 60 -> "${diffMin}m ago"
                diffHrs < 24 -> "${diffHrs}h ago"
                diffDays < 7 -> "${diffDays}d ago"
                else -> SimpleDateFormat("MMM d, yyyy", Locale.US).format(date)
            }
        } catch (_: Exception) {
            raw.take(16).replace('T', ' ')
        }
    }

    /**
     * Format a raw timestamp into full date-time.
     */
    fun formatFullDateTime(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return try {
            val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
            }
            val date = parser.parse(raw.take(19))
            SimpleDateFormat("MMM d, yyyy HH:mm", Locale.US).format(date)
        } catch (_: Exception) {
            raw.take(16).replace('T', ' ')
        }
    }

    /**
     * Escape HTML special characters.
     */
    fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\n", "<br>")
    }

    /**
     * Extract the vote value from system content text like "voted (-5)" or "voted (10)".
     * Returns null if no vote value is found.
     */
    fun extractVoteValueFromContent(content: String): Int? {
        val numericRegex = Regex("""voted[^-\d]*(-?\d+)""", RegexOption.IGNORE_CASE)
        val numericMatch = numericRegex.find(content)
        val numericValue = numericMatch?.groupValues?.get(1)?.toIntOrNull()
        if (numericValue != null) return numericValue

        val lower = content.lowercase()
        return when {
            lower.contains("approved with suggestions") || lower.contains("approve with suggestions") -> 5
            lower.contains("waiting for author") || lower.contains("wait for author") -> -5
            lower.contains("rejected") || lower.contains("reject") -> -10
            lower.contains("approved") || lower.contains("approve") -> 10
            else -> null
        }
    }
}
