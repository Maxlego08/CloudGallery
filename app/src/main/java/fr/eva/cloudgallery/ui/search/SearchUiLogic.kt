package fr.eva.cloudgallery.ui.search

import fr.eva.cloudgallery.data.search.ftsMatchExpression
import fr.eva.cloudgallery.data.search.nameMatchesSearch
import fr.eva.cloudgallery.domain.model.AlbumSummary

/*
 * Logique pure de l'écran de recherche (testée en JVM).
 */

/** Albums proposés au plus pendant la saisie. */
internal const val MAX_ALBUM_SUGGESTIONS = 5

/**
 * Albums proposés pour la saisie [input] : non vides, dont chaque mot de la saisie commence un
 * mot du nom (accents et casse ignorés), dans l'ordre reçu. Saisie vide : aucun.
 */
internal fun albumSuggestions(albums: List<AlbumSummary>, input: String, max: Int = MAX_ALBUM_SUGGESTIONS): List<AlbumSummary> =
    albums.asSequence()
        .filter { it.mediaCount > 0 && nameMatchesSearch(it.name, input) }
        .take(max)
        .toList()

/** Recherches récentes proposées : toutes si la saisie est vide, sinon celles qui y correspondent. */
internal fun recentSuggestions(recent: List<String>, input: String): List<String> =
    if (input.isBlank()) recent else recent.filter { nameMatchesSearch(it, input) }

/** Une saisie ne lance une recherche que si elle contient au moins un mot. */
internal fun canSubmitSearch(input: String): Boolean = ftsMatchExpression(input) != null

/** Saisie gardée pour la recherche lancée : espaces de début et de fin retirés. */
internal fun submittedQuery(input: String): String = input.trim()
