package fr.eva.cloudgallery.ui.grid

import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/*
 * Logique pure du défilement rapide (testée en JVM). La poignée couvre toute la liste du contexte,
 * pas seulement les pages chargées : sa position est le rang du premier élément visible parmi les
 * dates de tous les médias, triées comme la grille (dateTaken DESC).
 */

/** En dessous de ce nombre de médias, pas de poignée (la grille se parcourt sans elle). */
internal const val FAST_SCROLL_MIN_ITEMS = 60

/**
 * Dates de prise de vue de tous les médias du contexte, dans l'ordre de la grille (décroissant).
 * Les rangs sont ceux de la pagination : rang `r` = `r`-ième média de la liste triée.
 */
class FastScrollIndex(private val dates: LongArray) {

    val size: Int get() = dates.size

    /** Nombre de médias dont la date est ≥ [ms] (= rang du premier média plus ancien que [ms]). */
    fun countAtOrAfter(ms: Long): Int {
        // Première position dont la date est < ms (tableau décroissant).
        var lo = 0
        var hi = dates.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (dates[mid] >= ms) lo = mid + 1 else hi = mid
        }
        return lo
    }

    /** Rang du premier média de date [dateTaken] (les médias plus récents le précèdent). */
    fun rankOfDate(dateTaken: Long): Int =
        if (dateTaken == Long.MAX_VALUE) countAtOrAfter(dateTaken) else countAtOrAfter(dateTaken + 1)

    /** Rang du premier média de la période commençant au jour [epochDay]. */
    fun periodStartRank(epochDay: Long, grouping: GridGrouping, zone: ZoneId): Int =
        countAtOrAfter(periodBoundsMs(epochDay, grouping, zone).second)

    /** Rang visé par la poignée placée à [fraction] (0 = haut, 1 = bas). */
    fun rankAt(fraction: Float): Int {
        if (dates.isEmpty()) return 0
        return (fraction.coerceIn(0f, 1f) * (dates.size - 1)).roundToInt()
    }

    /** Période (premier jour, en jour epoch) du média de rang [rank]. */
    fun periodAt(rank: Int, grouping: GridGrouping, zone: ZoneId): Long? {
        if (dates.isEmpty()) return null
        return periodStartEpochDay(dates[rank.coerceIn(0, dates.size - 1)], grouping, zone)
    }

    /** Position de la poignée pour le rang [rank] (0..1). */
    fun fractionOf(rank: Int): Float =
        if (dates.size <= 1) 0f else (rank.coerceIn(0, dates.size - 1).toFloat() / (dates.size - 1))

    companion object {
        val EMPTY = FastScrollIndex(LongArray(0))
    }
}

/** Premier élément visible de la grille, ramené à une date (en-tête : début de sa période). */
internal fun FastScrollIndex.rankOf(item: GridItem, zone: ZoneId): Int = when (item) {
    is GridItem.Media -> rankOfDate(item.media.dateTaken)
    is GridItem.Header -> periodStartRank(item.epochDay, item.grouping, zone)
}

/**
 * Position de la poignée hors glisser : rang du premier élément visible, ou extrémités quand la
 * grille est en haut (grand titre visible) ou tout en bas.
 */
internal fun FastScrollIndex.thumbFraction(firstVisibleRank: Int?, atTop: Boolean, atBottom: Boolean): Float = when {
    atTop -> 0f
    atBottom -> 1f
    firstVisibleRank == null -> 0f
    else -> fractionOf(firstVisibleRank)
}

/** Clé de l'en-tête de la période [epochDay] (même format que [GridItem.Header.key]). */
internal fun headerKeyOf(grouping: GridGrouping, epochDay: Long): String = GridItem.Header(grouping, epochDay).key as String

/**
 * Fraction de la piste (0..1) pour un doigt à [pointerY], la poignée de hauteur [thumbHeight]
 * étant centrée sur le doigt dans une piste de [trackTop] à [trackBottom].
 */
internal fun trackFraction(pointerY: Float, trackTop: Float, trackBottom: Float, thumbHeight: Float): Float {
    val range = trackBottom - trackTop - thumbHeight
    if (range <= 0f) return 0f
    return ((pointerY - trackTop - thumbHeight / 2f) / range).coerceIn(0f, 1f)
}

/** Libellé de la bulle : « 12 janv. 2025 » (jours) ou « Janvier 2025 » (mois). */
internal fun fastScrollLabel(epochDay: Long, grouping: GridGrouping, locale: Locale): String {
    val date = LocalDate.ofEpochDay(epochDay)
    val pattern = when (grouping) {
        GridGrouping.DAY -> "d MMM yyyy"
        GridGrouping.MONTH -> "LLLL yyyy"
    }
    return DateTimeFormatter.ofPattern(pattern, locale).format(date).replaceFirstChar { it.titlecase(locale) }
}

/** Mois (année × 12 + mois) d'une période : un retour haptique à chaque changement. */
internal fun monthOrdinal(epochDay: Long): Int {
    val date = LocalDate.ofEpochDay(epochDay)
    return date.year * 12 + date.monthValue - 1
}
