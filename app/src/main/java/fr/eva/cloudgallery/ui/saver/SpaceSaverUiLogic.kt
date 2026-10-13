package fr.eva.cloudgallery.ui.saver

import fr.eva.cloudgallery.domain.model.SaverAge
import fr.eva.cloudgallery.domain.model.SpaceSaverCandidate

/*
 * Pure logic of the Space saver screen (tested on the JVM).
 */

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * Maximum number of URIs per MediaStore trash request: all the URIs of a request go through
 * a single Binder transaction (~1 MB), as for deletion from the grid.
 * Each batch is still confirmed by the system dialog.
 */
internal const val MAX_TRASH_BATCH = 1_000

/** Copies taken before the age threshold [age] (capture date), all of them for [SaverAge.ALL]. */
internal fun filterByAge(candidates: List<SpaceSaverCandidate>, age: SaverAge, now: Long): List<SpaceSaverCandidate> {
    val days = age.minAgeDays ?: return candidates
    val limit = now - days * DAY_MS
    return candidates.filter { it.dateTaken <= limit }
}

/** Checked copies: all those in the filter except the ones the user unchecked. */
internal fun selectedIn(filtered: List<SpaceSaverCandidate>, deselected: Set<Long>): List<SpaceSaverCandidate> =
    filtered.filter { it.sourceId !in deselected }

/** Number of copies and space freed. */
internal data class SaverSummary(val count: Int, val bytes: Long)

internal fun summaryOf(candidates: List<SpaceSaverCandidate>): SaverSummary =
    SaverSummary(candidates.size, candidates.sumOf { it.sizeBytes })

/** Successive batches of trash requests. */
internal fun trashBatches(candidates: List<SpaceSaverCandidate>, max: Int = MAX_TRASH_BATCH): List<List<SpaceSaverCandidate>> =
    candidates.chunked(max)
