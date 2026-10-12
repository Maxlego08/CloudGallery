package fr.eva.cloudgallery.data.search

import java.text.Normalizer
import java.util.Locale

/*
 * Logique pure de la recherche plein texte (testée en JVM).
 */

/** Mots pris en compte au plus dans une recherche (au-delà : ignorés). */
internal const val MAX_SEARCH_TOKENS = 8

/** Longueur maximale d'un mot de recherche (un nom de fichier entier tient largement). */
internal const val MAX_SEARCH_TOKEN_LENGTH = 64

private val WORD = Regex("[\\p{L}\\p{N}]+")

/**
 * Libellés des mois (index 0 = janvier), en français puis en anglais, indexés dans `media_fts.dates`
 * pour qu'« août 2026 » comme « august 2026 » trouvent les médias du mois.
 */
internal val MONTH_LABELS = listOf(
    "janvier january", "février february", "mars march", "avril april", "mai may", "juin june",
    "juillet july", "août august", "septembre september", "octobre october", "novembre november",
    "décembre december",
)

/** Libellés des jours de la semaine, dans l'ordre de `strftime('%w')` (0 = dimanche). */
internal val WEEKDAY_LABELS = listOf(
    "dimanche sunday", "lundi monday", "mardi tuesday", "mercredi wednesday", "jeudi thursday",
    "vendredi friday", "samedi saturday",
)

/** Libellés du type de média (« vidéo » trouve les vidéos), français puis anglais. */
internal const val VIDEO_LABEL = "vidéo video"
internal const val PHOTO_LABEL = "photo image"

/**
 * Mots d'une saisie : suites de lettres et de chiffres (tout le reste sépare), en minuscules,
 * comme les découpe le tokeniseur unicode61 de l'index.
 */
internal fun searchTokens(input: String): List<String> =
    WORD.findAll(input.lowercase(Locale.ROOT))
        .map { it.value.take(MAX_SEARCH_TOKEN_LENGTH) }
        .take(MAX_SEARCH_TOKENS)
        .toList()

/**
 * Expression FTS4 sûre pour une saisie de l'utilisateur, ou null s'il n'y a aucun mot.
 *
 * Chaque mot devient un préfixe (`mot*` : recherche au fil de la frappe, « 2026 » trouve aussi
 * « IMG_20260812 ») et les mots sont combinés en ET implicite. Aucun caractère de la syntaxe FTS
 * ne survit (guillemets, `*`, `-`, `:`, parenthèses, `^`…) et les mots sont en minuscules : les
 * opérateurs `AND`, `OR`, `NOT`, `NEAR`, sensibles à la casse, ne peuvent pas être formés. Une
 * saisie ne peut donc jamais provoquer d'erreur de syntaxe FTS.
 */
internal fun ftsMatchExpression(input: String): String? {
    val tokens = searchTokens(input)
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" ") { "$it*" }
}

/** Texte sans accents, en minuscules (comparaison des noms d'albums, comme unicode61). */
internal fun foldForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)

/**
 * Nom (d'album) qui correspond à une saisie : chaque mot de la saisie est le début d'un mot du
 * nom (accents et casse ignorés), comme la recherche plein texte des médias. Saisie vide : non.
 */
internal fun nameMatchesSearch(name: String, input: String): Boolean {
    val wanted = searchTokens(foldForSearch(input))
    if (wanted.isEmpty()) return false
    val words = searchTokens(foldForSearch(name))
    return wanted.all { w -> words.any { it.startsWith(w) } }
}

/** Recherches récentes gardées au plus. */
internal const val MAX_RECENT_SEARCHES = 10

/**
 * Historique après la recherche [query] : en tête, sans doublon (casse et accents ignorés,
 * espaces superflus retirés), borné à [max]. Une saisie sans mot ne change rien.
 */
internal fun withRecentSearch(recent: List<String>, query: String, max: Int = MAX_RECENT_SEARCHES): List<String> {
    val clean = query.trim().replace(Regex("\\s+"), " ")
    if (searchTokens(clean).isEmpty()) return recent
    val key = foldForSearch(clean)
    return (listOf(clean) + recent.filter { foldForSearch(it) != key }).take(max)
}
