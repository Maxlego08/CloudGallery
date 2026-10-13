package fr.eva.cloudgallery.data.storage

import fr.eva.cloudgallery.data.db.dao.SpaceSaverRow
import fr.eva.cloudgallery.data.dedup.localHashSignature
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SpaceSaverCandidate
import fr.eva.cloudgallery.domain.model.SpaceSaverList

/*
 * Pure logic of the space saver (tested on the JVM).
 */

/**
 * Signature of a local file at the time its hash was computed, as compared in SQL by
 * `SPACE_SAVER_SELECT`: `"<sizeBytes>:<dateModified>:<generation>"`. It is the one written by
 * the hashing ([localHashSignature]): a single definition of the format.
 */
internal fun spaceSaverSignature(sizeBytes: Long, dateModified: Long, generation: Long): String =
    localHashSignature(sizeBytes, dateModified, generation)

/**
 * Offered copies: a copy whose media carries a favorite or a user album is only offered if
 * that media also contains the OneDrive item (merged: favorite and album stay on the OneDrive
 * item); otherwise it is excluded and counted.
 */
internal fun spaceSaverListOf(rows: List<SpaceSaverRow>): SpaceSaverList {
    var excluded = 0
    val candidates = ArrayList<SpaceSaverCandidate>(rows.size)
    for (row in rows) {
        if (row.hasUserData && !row.mergedWithCloud) {
            excluded++
            continue
        }
        candidates += SpaceSaverCandidate(
            sourceId = row.sourceId,
            mediaId = row.mediaId,
            uri = row.uri,
            sizeBytes = row.sizeBytes,
            dateModified = row.dateModified,
            isVideo = row.mediaType == MediaType.VIDEO,
            displayName = row.displayName,
            dateTaken = row.dateTaken,
        )
    }
    return SpaceSaverList(candidates, excluded)
}

/**
 * Reviewed copies that can still be deleted right before the request: still offered ([fresh],
 * re-read from the database) and unchanged since the review (same URI, size and modification
 * date). A copy that was modified, moved or whose OneDrive item has disappeared in the meantime
 * is excluded.
 */
internal fun stillCandidates(reviewed: List<SpaceSaverCandidate>, fresh: List<SpaceSaverCandidate>): List<SpaceSaverCandidate> {
    val current = fresh.associateBy { it.sourceId }
    return reviewed.filter { r ->
        val f = current[r.sourceId] ?: return@filter false
        f.uri == r.uri && f.sizeBytes == r.sizeBytes && f.dateModified == r.dateModified
    }
}
