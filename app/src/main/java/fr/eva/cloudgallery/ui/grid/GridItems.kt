package fr.eva.cloudgallery.ui.grid

import androidx.compose.runtime.Immutable
import fr.eva.cloudgallery.domain.model.MediaSummary
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Elément d'une grille paginée : en-tête de période (pleine largeur) ou média. */
@Immutable
sealed interface GridItem {
    /** Clé stable pour LazyVerticalGrid (Long pour un média, String pour un en-tête). */
    val key: Any

    /**
     * En-tête de jour ou de mois. [epochDay] = jour concerné (DAY) ou premier jour du mois (MONTH).
     * Le libellé est résolu dans l'UI (Formatters + ressources).
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

// Fonctions pures de regroupement (testées unitairement).

/** Premier jour de la période contenant [epochMs], exprimé en jour epoch. */
fun periodStartEpochDay(epochMs: Long, grouping: GridGrouping, zone: ZoneId): Long {
    // LocalDate.ofInstant évite l'allocation d'un ZonedDateTime (appelé pour chaque paire d'éléments).
    val date = LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), zone)
    return when (grouping) {
        GridGrouping.DAY -> date.toEpochDay()
        GridGrouping.MONTH -> date.withDayOfMonth(1).toEpochDay()
    }
}

/**
 * En-tête à insérer entre [before] et [after] (ordre chronologique décroissant), ou null.
 * Un en-tête précède le premier média de chaque période ; rien après le dernier média.
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
 * Bornes [début, fin[ en ms de la période d'un en-tête (jour ou mois commençant à [epochDay]),
 * dans le fuseau utilisé pour le regroupement.
 */
fun periodBoundsMs(epochDay: Long, grouping: GridGrouping, zone: ZoneId): Pair<Long, Long> {
    val start = LocalDate.ofEpochDay(epochDay)
    val end = when (grouping) {
        GridGrouping.DAY -> start.plusDays(1)
        GridGrouping.MONTH -> start.plusMonths(1)
    }
    return start.atStartOfDay(zone).toInstant().toEpochMilli() to end.atStartOfDay(zone).toInstant().toEpochMilli()
}
