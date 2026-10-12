package fr.eva.cloudgallery.data.storage

import fr.eva.cloudgallery.data.db.dao.SpaceSaverRow
import fr.eva.cloudgallery.data.dedup.localHashSignature
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SpaceSaverCandidate
import fr.eva.cloudgallery.domain.model.SpaceSaverList

/*
 * Logique pure de l'économiseur d'espace (testée en JVM).
 */

/**
 * Signature d'un fichier local au calcul de son empreinte, telle que comparée en SQL par
 * `SPACE_SAVER_SELECT` : `"<sizeBytes>:<dateModified>:<generation>"`. C'est celle qu'écrit le
 * calcul des empreintes ([localHashSignature]) : une seule définition du format.
 */
internal fun spaceSaverSignature(sizeBytes: Long, dateModified: Long, generation: Long): String =
    localHashSignature(sizeBytes, dateModified, generation)

/**
 * Copies proposées : une copie dont le média porte un favori ou un album de l'utilisateur n'est
 * proposée que si ce média contient aussi l'élément OneDrive (regroupés : favori et album
 * restent sur l'élément OneDrive) ; sinon elle est écartée et comptée.
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
 * Copies revues encore supprimables juste avant la demande : toujours proposées ([fresh], relu
 * en base) et inchangées depuis la revue (même URI, taille et date de modification). Une copie
 * modifiée, déplacée ou dont l'élément OneDrive a disparu entre-temps est écartée.
 */
internal fun stillCandidates(reviewed: List<SpaceSaverCandidate>, fresh: List<SpaceSaverCandidate>): List<SpaceSaverCandidate> {
    val current = fresh.associateBy { it.sourceId }
    return reviewed.filter { r ->
        val f = current[r.sourceId] ?: return@filter false
        f.uri == r.uri && f.sizeBytes == r.sizeBytes && f.dateModified == r.dateModified
    }
}
