package paol0b.azuredevops.util

import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/**
 * Shared date formatting utilities.
 */
object DateUtil {

    /**
     * Converts an ISO date string to a human-readable relative date
     * (e.g., "Today", "Yesterday", "3 days ago", "Mar 5, 2025").
     */
    fun relativeDate(dateStr: String?): String {
        if (dateStr.isNullOrBlank()) return ""
        return try {
            val dateTime = OffsetDateTime.parse(dateStr, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
            val itemDate = dateTime.toLocalDate()
            val today = LocalDate.now()
            val daysBetween = ChronoUnit.DAYS.between(itemDate, today)
            when {
                daysBetween == 0L -> "Today"
                daysBetween == 1L -> "Yesterday"
                daysBetween < 30L -> "$daysBetween days ago"
                else -> itemDate.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
            }
        } catch (e: DateTimeParseException) {
            dateStr.take(10)
        }
    }
}
