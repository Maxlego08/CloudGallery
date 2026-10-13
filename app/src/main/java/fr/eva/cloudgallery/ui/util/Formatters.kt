package fr.eva.cloudgallery.ui.util

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.abs

/** Formatting of dates, durations and sizes. Pure, testable functions (injectable zone/locale). */
object Formatters {

    fun localDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /**
     * Day header in the Samsung Gallery style: "Today", "Yesterday",
     * "Mon 6 Oct" for the current year, "6 Oct 2024" otherwise.
     */
    fun dayHeader(
        date: LocalDate,
        today: LocalDate = LocalDate.now(),
        locale: Locale = Locale.getDefault(),
        todayLabel: String,
        yesterdayLabel: String,
    ): String = when {
        date == today -> todayLabel
        date == today.minusDays(1) -> yesterdayLabel
        date.year == today.year -> DateTimeFormatter.ofPattern("EEE d MMM", locale).format(date)
        else -> DateTimeFormatter.ofPattern("d MMM yyyy", locale).format(date)
    }

    /** Month header: "October" (current year) or "October 2024". */
    fun monthHeader(month: YearMonth, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String {
        val pattern = if (month.year == today.year) "LLLL" else "LLLL yyyy"
        return DateTimeFormatter.ofPattern(pattern, locale).format(month).replaceFirstChar { it.titlecase(locale) }
    }

    /** Full date and time for the Details panel. */
    fun fullDateTime(epochMs: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
            .withLocale(locale)
            .format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** Video duration: "0:07", "12:34", "1:02:03". */
    fun duration(ms: Long): String {
        val totalSec = (if (ms < 0) 0 else ms) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
    }

    /** Human-readable size in decimal units like One UI: "3.2 MB", or "3,2 Mo" in French. */
    fun fileSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
        val french = locale.language == "fr"
        if (bytes < 1000) return if (french) "$bytes o" else "$bytes B"
        val units = if (french) listOf("Ko", "Mo", "Go", "To") else listOf("kB", "MB", "GB", "TB")
        var value = bytes.toDouble()
        var unit = -1
        while (abs(value) >= 1000 && unit < units.lastIndex) {
            value /= 1000
            unit++
        }
        return String.format(locale, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[unit])
    }

    /** Resolution "4000 x 3000 (12 MP)". */
    fun resolution(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return ""
        val mp = width.toLong() * height / 1_000_000.0
        return if (mp >= 1) "$width x $height (${"%.0f".format(Locale.ROOT, mp)} MP)" else "$width x $height"
    }

    fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)
}
