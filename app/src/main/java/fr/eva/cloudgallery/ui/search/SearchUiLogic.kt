package fr.eva.cloudgallery.ui.search

import fr.eva.cloudgallery.data.search.ftsMatchExpression
import fr.eva.cloudgallery.data.search.nameMatchesSearch
import fr.eva.cloudgallery.domain.model.AlbumSummary

/*
 * Pure logic of the search screen (tested on the JVM).
 */

/** Maximum number of albums suggested while typing. */
internal const val MAX_ALBUM_SUGGESTIONS = 5

/**
 * Albums suggested for the input [input]: non-empty ones where each word of the input starts a
 * word of the name (ignoring accents and case), in the order received. Empty input: none.
 */
internal fun albumSuggestions(albums: List<AlbumSummary>, input: String, max: Int = MAX_ALBUM_SUGGESTIONS): List<AlbumSummary> =
    albums.asSequence()
        .filter { it.mediaCount > 0 && nameMatchesSearch(it.name, input) }
        .take(max)
        .toList()

/** Suggested recent searches: all of them if the input is empty, otherwise the matching ones. */
internal fun recentSuggestions(recent: List<String>, input: String): List<String> =
    if (input.isBlank()) recent else recent.filter { nameMatchesSearch(it, input) }

/** An input only starts a search if it contains at least one word. */
internal fun canSubmitSearch(input: String): Boolean = ftsMatchExpression(input) != null

/** Input kept for the submitted search: leading and trailing spaces removed. */
internal fun submittedQuery(input: String): String = input.trim()
