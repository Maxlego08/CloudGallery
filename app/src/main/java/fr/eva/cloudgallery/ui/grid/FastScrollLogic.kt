package fr.eva.cloudgallery.ui.grid

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Pure fast-scroll logic (JVM-tested). The thumb spans the whole list of the context, not just
 * the loaded pages: its position is the rank of the first visible item among the dates of all
 * media, sorted like the grid (dateTaken DESC).
 */

/** Below this number of media, no thumb (the grid is browsed without it). */
internal const val FAST_SCROLL_MIN_ITEMS = 60

/**
 * Capture dates of all media in the context, in grid order (descending).
 * Ranks are those of the paging: rank `r` = `r`-th media of the sorted list.
 */
class FastScrollIndex(private val dates: LongArray) {

    val size: Int get() = dates.size

    /** Number of media whose date is ≥ [ms] (= rank of the first media older than [ms]). */
    fun countAtOrAfter(ms: Long): Int {
        // First position whose date is < ms (descending array).
        var lo = 0
        var hi = dates.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (dates[mid] >= ms) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Rank of the first media dated [dateTaken] (more recent media come before it). */
    fun rankOfDate(dateTaken: Long): Int =
        if (dateTaken == Long.MAX_VALUE) countAtOrAfter(dateTaken) else countAtOrAfter(dateTaken + 1)

    /** Rank of the first media of the period starting on day [epochDay]. */
    fun periodStartRank(epochDay: Long, grouping: GridGrouping, zone: ZoneId): Int =
        countAtOrAfter(periodBoundsMs(epochDay, grouping, zone).second)

    /** Rank targeted by the thumb placed at [fraction] (0 = top, 1 = bottom). */
    fun rankAt(fraction: Float): Int {
        if (dates.isEmpty()) return 0
        return (fraction.coerceIn(0f, 1f) * (dates.size - 1)).roundToInt()
    }

    /** Period (first day, as an epoch day) of the media at rank [rank]. */
    fun periodAt(rank: Int, grouping: GridGrouping, zone: ZoneId): Long? {
        if (dates.isEmpty()) return null
        return periodStartEpochDay(dates[rank.coerceIn(0, dates.size - 1)], grouping, zone)
    }

    /** Thumb position for rank [rank] (0..1). */
    fun fractionOf(rank: Int): Float =
        if (dates.size <= 1) 0f else (rank.coerceIn(0, dates.size - 1).toFloat() / (dates.size - 1))

    companion object {
        val EMPTY = FastScrollIndex(LongArray(0))
    }
}

/** First visible grid item, mapped to a date (header: start of its period). */
internal fun FastScrollIndex.rankOf(item: GridItem, zone: ZoneId): Int = when (item) {
    is GridItem.Media -> rankOfDate(item.media.dateTaken)
    is GridItem.Header -> periodStartRank(item.epochDay, item.grouping, zone)
}

/**
 * Thumb position outside of a drag: rank of the first visible item, or the ends when the grid is
 * at the top (large title visible) or at the very bottom.
 */
internal fun FastScrollIndex.thumbFraction(firstVisibleRank: Int?, atTop: Boolean, atBottom: Boolean): Float = when {
    atTop -> 0f
    atBottom -> 1f
    firstVisibleRank == null -> 0f
    else -> fractionOf(firstVisibleRank)
}

/** Key of the header for period [epochDay] (same format as [GridItem.Header.key]). */
internal fun headerKeyOf(grouping: GridGrouping, epochDay: Long): String = GridItem.Header(grouping, epochDay).key as String

/**
 * Track fraction (0..1) for a finger at [pointerY], the thumb of height [thumbHeight] being
 * centered on the finger in a track running from [trackTop] to [trackBottom].
 */
internal fun trackFraction(pointerY: Float, trackTop: Float, trackBottom: Float, thumbHeight: Float): Float {
    val range = trackBottom - trackTop - thumbHeight
    if (range <= 0f) return 0f
    return ((pointerY - trackTop - thumbHeight / 2f) / range).coerceIn(0f, 1f)
}

/** Bubble label: "12 Jan 2025" (days) or "January 2025" (months), in [locale]. */
internal fun fastScrollLabel(epochDay: Long, grouping: GridGrouping, locale: Locale): String {
    val date = LocalDate.ofEpochDay(epochDay)
    val pattern = when (grouping) {
        GridGrouping.DAY -> "d MMM yyyy"
        GridGrouping.MONTH -> "LLLL yyyy"
    }
    return DateTimeFormatter.ofPattern(pattern, locale).format(date).replaceFirstChar { it.titlecase(locale) }
}

/** Month (year × 12 + month) of a period: one haptic tick on each change. */
internal fun monthOrdinal(epochDay: Long): Int {
    val date = LocalDate.ofEpochDay(epochDay)
    return date.year * 12 + date.monthValue - 1
}
