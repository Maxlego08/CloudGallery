package fr.eva.cloudgallery.ui.grid

import androidx.compose.runtime.Immutable
import fr.eva.cloudgallery.domain.model.MediaSummary
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Item of a paged grid: period header (full width) or media. */
@Immutable
sealed interface GridItem {
    /** Stable key for LazyVerticalGrid (Long for a media, String for a header). */
    val key: Any

    /**
     * Day or month header. [epochDay] = the day itself (DAY) or the first day of the month (MONTH).
     * The label is resolved in the UI (Formatters + resources).
     */
    data class Header(val grouping: GridGrouping, val epochDay: Long) : GridItem {
        override val key: Any
            get() = (if (grouping == GridGrouping.DAY) "d:" else "m:") + epochDay

        val date: LocalDate get() = LocalDate.ofEpochDay(epochDay)
        val yearMonth: YearMonth get() = YearMonth.from(date)
    }

    data class Media(val media: MediaSummary) : GridItem {
        override val key: Any get() = media.id
    }
}

// Pure grouping functions (unit-tested).

/** First day of the period containing [epochMs], expressed as an epoch day. */
fun periodStartEpochDay(epochMs: Long, grouping: GridGrouping, zone: ZoneId): Long {
    // LocalDate.ofInstant avoids allocating a ZonedDateTime (called for every pair of items).
    val date = LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), zone)
    return when (grouping) {
        GridGrouping.DAY -> date.toEpochDay()
        GridGrouping.MONTH -> date.withDayOfMonth(1).toEpochDay()
    }
}

/**
 * Header to insert between [before] and [after] (descending chronological order), or null.
 * A header precedes the first media of each period; nothing after the last media.
 */
fun headerBetween(
    before: MediaSummary?,
    after: MediaSummary?,
    grouping: GridGrouping,
    zone: ZoneId,
): GridItem.Header? {
    if (after == null) return null
    val afterPeriod = periodStartEpochDay(after.dateTaken, grouping, zone)
    if (before != null && periodStartEpochDay(before.dateTaken, grouping, zone) == afterPeriod) return null
    return GridItem.Header(grouping, afterPeriod)
}

/**
 * Bounds [start, end) in ms of a header's period (day or month starting at [epochDay]), in the
 * time zone used for grouping.
 */
fun periodBoundsMs(epochDay: Long, grouping: GridGrouping, zone: ZoneId): Pair<Long, Long> {
    val start = LocalDate.ofEpochDay(epochDay)
    val end = when (grouping) {
        GridGrouping.DAY -> start.plusDays(1)
        GridGrouping.MONTH -> start.plusMonths(1)
    }
    return start.atStartOfDay(zone).toInstant().toEpochMilli() to end.atStartOfDay(zone).toInstant().toEpochMilli()
}
