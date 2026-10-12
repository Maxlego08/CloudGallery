package fr.eva.cloudgallery.ui.saver

import fr.eva.cloudgallery.domain.model.SaverAge
import fr.eva.cloudgallery.domain.model.SpaceSaverCandidate

/*
 * Logique pure de l'écran Economiseur d'espace (testée en JVM).
 */

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * Nombre maximal d'URI par demande de corbeille MediaStore : toutes les URI d'une demande
 * transitent dans une seule transaction Binder (~1 Mo), comme pour la suppression de la grille.
 * Chaque lot reste confirmé par la boîte de dialogue système.
 */
internal const val MAX_TRASH_BATCH = 1_000

/** Copies prises avant le seuil d'ancienneté [age] (date de prise de vue), toutes pour [SaverAge.ALL]. */
internal fun filterByAge(candidates: List<SpaceSaverCandidate>, age: SaverAge, now: Long): List<SpaceSaverCandidate> {
    val days = age.minAgeDays ?: return candidates
    val limit = now - days * DAY_MS
    return candidates.filter { it.dateTaken <= limit }
}

/** Copies cochées : toutes celles du filtre sauf celles que l'utilisateur a décochées. */
internal fun selectedIn(filtered: List<SpaceSaverCandidate>, deselected: Set<Long>): List<SpaceSaverCandidate> =
    filtered.filter { it.sourceId !in deselected }

/** Nombre de copies et espace libéré. */
internal data class SaverSummary(val count: Int, val bytes: Long)

internal fun summaryOf(candidates: List<SpaceSaverCandidate>): SaverSummary =
    SaverSummary(candidates.size, candidates.sumOf { it.sizeBytes })

/** Lots successifs de demandes de corbeille. */
internal fun trashBatches(candidates: List<SpaceSaverCandidate>, max: Int = MAX_TRASH_BATCH): List<List<SpaceSaverCandidate>> =
    candidates.chunked(max)
