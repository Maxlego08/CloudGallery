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

/** Formatage des dates, durées et tailles. Fonctions pures et testables (zone/locale injectables). */
object Formatters {

    fun localDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
        Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()

    /**
     * En-tête de jour façon Samsung Galerie : « Aujourd'hui », « Hier »,
     * « lun. 6 oct. » pour l'année en cours, « 6 oct. 2024 » sinon.
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

    /** En-tête de mois : « octobre » (année en cours) ou « octobre 2024 ». */
    fun monthHeader(month: YearMonth, today: LocalDate = LocalDate.now(), locale: Locale = Locale.getDefault()): String {
        val pattern = if (month.year == today.year) "LLLL" else "LLLL yyyy"
        return DateTimeFormatter.ofPattern(pattern, locale).format(month).replaceFirstChar { it.titlecase(locale) }
    }

    /** Date et heure complètes pour le panneau Détails. */
    fun fullDateTime(epochMs: Long, locale: Locale = Locale.getDefault(), zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofLocalizedDateTime(FormatStyle.LONG, FormatStyle.SHORT)
            .withLocale(locale)
            .format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** Durée vidéo : « 0:07 », « 12:34 », « 1:02:03 ». */
    fun duration(ms: Long): String {
        val totalSec = (if (ms < 0) 0 else ms) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.ROOT, h, m, s) else "%d:%02d".format(Locale.ROOT, m, s)
    }

    /** Taille lisible en unités décimales comme One UI : « 3,2 Mo ». */
    fun fileSize(bytes: Long, locale: Locale = Locale.getDefault()): String {
        if (bytes < 1000) return "$bytes o"
        val units = listOf("Ko", "Mo", "Go", "To")
        var value = bytes.toDouble()
        var unit = -1
        while (abs(value) >= 1000 && unit < units.lastIndex) {
            value /= 1000
            unit++
        }
        return String.format(locale, if (value >= 100) "%.0f %s" else "%.1f %s", value, units[unit])
    }

    /** Résolution « 4000 x 3000 (12 MP) ». */
    fun resolution(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return ""
        val mp = width.toLong() * height / 1_000_000.0
        return if (mp >= 1) "$width x $height (${"%.0f".format(Locale.ROOT, mp)} MP)" else "$width x $height"
    }

    fun daysBetween(a: LocalDate, b: LocalDate): Long = ChronoUnit.DAYS.between(a, b)
}
