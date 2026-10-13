package fr.eva.cloudgallery.data.search

import java.text.Normalizer
import java.util.Locale

/*
 * Pure logic of the full-text search (tested on the JVM).
 */

/** Maximum number of words taken into account in a search (beyond that: ignored). */
internal const val MAX_SEARCH_TOKENS = 8

/** Maximum length of a search word (a whole file name fits easily). */
internal const val MAX_SEARCH_TOKEN_LENGTH = 64

private val WORD = Regex("[\\p{L}\\p{N}]+")

/**
 * Month labels (index 0 = January), in French then English, indexed in `media_fts.dates` so
 * that both "août 2026" and "august 2026" find the media of that month.
 */
internal val MONTH_LABELS = listOf(
    "janvier january", "février february", "mars march", "avril april", "mai may", "juin june",
    "juillet july", "août august", "septembre september", "octobre october", "novembre november",
    "décembre december",
)

/** Weekday labels, in `strftime('%w')` order (0 = Sunday). */
internal val WEEKDAY_LABELS = listOf(
    "dimanche sunday", "lundi monday", "mardi tuesday", "mercredi wednesday", "jeudi thursday",
    "vendredi friday", "samedi saturday",
)

/** Media type labels ("vidéo" finds videos), French then English. */
internal const val VIDEO_LABEL = "vidéo video"
internal const val PHOTO_LABEL = "photo image"

/**
 * Words of an input: runs of letters and digits (everything else separates), lowercased, the
 * way the index's unicode61 tokenizer splits them.
 */
internal fun searchTokens(input: String): List<String> =
    WORD.findAll(input.lowercase(Locale.ROOT))
        .map { it.value.take(MAX_SEARCH_TOKEN_LENGTH) }
        .take(MAX_SEARCH_TOKENS)
        .toList()

/**
 * Safe FTS4 expression for a user input, or null if there is no word.
 *
 * Each word becomes a prefix (`word*`: search-as-you-type, "2026" also finds "IMG_20260812")
 * and the words are combined with an implicit AND. No FTS syntax character survives (quotes,
 * `*`, `-`, `:`, parentheses, `^`…) and the words are lowercase: the case-sensitive operators
 * `AND`, `OR`, `NOT`, `NEAR` cannot be formed. An input can therefore never cause an FTS syntax
 * error.
 */
internal fun ftsMatchExpression(input: String): String? {
    val tokens = searchTokens(input)
    if (tokens.isEmpty()) return null
    return tokens.joinToString(" ") { "$it*" }
}

/** Text without accents, lowercased (album name comparison, like unicode61). */
internal fun foldForSearch(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
        .lowercase(Locale.ROOT)

/**
 * (Album) name matching an input: each word of the input is the start of a word of the name
 * (accents and case ignored), like the media full-text search. Empty input: no match.
 */
internal fun nameMatchesSearch(name: String, input: String): Boolean {
    val wanted = searchTokens(foldForSearch(input))
    if (wanted.isEmpty()) return false
    val words = searchTokens(foldForSearch(name))
    return wanted.all { w -> words.any { it.startsWith(w) } }
}

/** Maximum number of recent searches kept. */
internal const val MAX_RECENT_SEARCHES = 10

/**
 * History after the search [query]: at the top, without duplicates (case and accents ignored,
 * extra whitespace removed), capped at [max]. An input without any word changes nothing.
 */
internal fun withRecentSearch(recent: List<String>, query: String, max: Int = MAX_RECENT_SEARCHES): List<String> {
    val clean = query.trim().replace(Regex("\\s+"), " ")
    if (searchTokens(clean).isEmpty()) return recent
    val key = foldForSearch(clean)
    return (listOf(clean) + recent.filter { foldForSearch(it) != key }).take(max)
}
