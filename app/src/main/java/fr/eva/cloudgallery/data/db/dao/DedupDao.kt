package fr.eva.cloudgallery.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import fr.eva.cloudgallery.data.dedup.HashTarget
import fr.eva.cloudgallery.data.dedup.MergeCandidateRow
import fr.eva.cloudgallery.data.dedup.MergedRow

/*
 * Signature courante d'une source locale : `sizeBytes || ':' || dateModified || ':' || generation`,
 * identique à `localHashSignature` (data/dedup/DedupLogic.kt). Une empreinte locale n'est à jour
 * que si `hashSignature` vaut cette expression.
 */

/**
 * Empreintes des fichiers locaux et fusion local / OneDrive (index uniquement). Les opérations
 * de déplacement couvrent toutes les tables qui référencent `media.id` : sources, favoris,
 * appartenances aux albums, couverture d'album, fichiers en cache, téléchargements, position de
 * lecture et index de recherche.
 */
@Dao
interface DedupDao {

    // --- Empreintes locales -------------------------------------------------------------------

    /** Fichiers locaux visibles dont l'empreinte manque ou est périmée. */
    @Query(
        """
        SELECT id AS sourceId, mediaId, uri, sizeBytes, dateModified, generation FROM media_source
        WHERE kind = 'LOCAL' AND missingSince IS NULL AND uri IS NOT NULL
          AND (hashSignature IS NULL OR hashSignature != (sizeBytes || ':' || dateModified || ':' || generation))
        """,
    )
    suspend fun staleLocalHashes(): List<HashTarget>

    @Query(
        """
        SELECT EXISTS(SELECT 1 FROM media_source
        WHERE kind = 'LOCAL' AND missingSince IS NULL AND uri IS NOT NULL
          AND (hashSignature IS NULL OR hashSignature != (sizeBytes || ':' || dateModified || ':' || generation)))
        """,
    )
    suspend fun hasStaleLocalHashes(): Boolean

    /** Tailles des éléments OneDrive visibles (priorité du calcul des empreintes). */
    @Query("SELECT DISTINCT sizeBytes FROM media_source WHERE kind = 'ONEDRIVE' AND missingSince IS NULL")
    suspend fun visibleCloudSizes(): List<Long>

    /**
     * Enregistre l'empreinte d'un fichier local, seulement si la source est toujours dans l'état
     * lu avant le calcul ([signature]) : un fichier modifié entre-temps sera recalculé.
     */
    @Query(
        """
        UPDATE media_source SET quickXorHash = :hash, hashSignature = :signature
        WHERE id = :sourceId AND kind = 'LOCAL' AND (sizeBytes || ':' || dateModified || ':' || generation) = :signature
        """,
    )
    suspend fun setLocalHash(sourceId: Long, hash: String, signature: String): Int

    @Query("UPDATE media SET contentHash = :contentHash WHERE id = :mediaId")
    suspend fun setContentHash(mediaId: Long, contentHash: String?)

    // --- Fusion -------------------------------------------------------------------------------

    /** Parmi [mediaIds], médias qui ont un fichier local (même masqué) : métadonnées locales. */
    @Query("SELECT DISTINCT mediaId FROM media_source WHERE kind = 'LOCAL' AND mediaId IN (:mediaIds)")
    suspend fun withLocalSource(mediaIds: List<Long>): List<Long>

    /**
     * Couples fichier local / élément OneDrive visibles, de même empreinte et même taille :
     * empreinte locale à jour, média local sans source OneDrive (même masquée), média OneDrive
     * sans fichier local, sans téléchargement en cours (copie hors ligne en train de s'écrire).
     */
    @Query(
        """
        SELECT l.id AS localSourceId, l.mediaId AS localMediaId, o.id AS cloudSourceId, o.mediaId AS cloudMediaId,
            l.quickXorHash AS quickXorHash, l.sizeBytes AS sizeBytes
        FROM media_source l
        JOIN media_source o ON o.quickXorHash = l.quickXorHash AND o.sizeBytes = l.sizeBytes
        WHERE l.kind = 'LOCAL' AND l.missingSince IS NULL AND l.quickXorHash IS NOT NULL
          AND l.hashSignature = (l.sizeBytes || ':' || l.dateModified || ':' || l.generation)
          AND o.kind = 'ONEDRIVE' AND o.missingSince IS NULL
          AND o.mediaId != l.mediaId
          AND NOT EXISTS(SELECT 1 FROM media_source x WHERE x.mediaId = l.mediaId AND x.kind = 'ONEDRIVE')
          AND NOT EXISTS(SELECT 1 FROM media_source y WHERE y.mediaId = o.mediaId AND y.kind = 'LOCAL')
          AND NOT EXISTS(SELECT 1 FROM download_task t WHERE t.mediaId = o.mediaId AND t.state = 'RUNNING')
        """,
    )
    suspend fun mergeCandidates(): List<MergeCandidateRow>

    /** Médias fusionnés (une source locale et une source OneDrive), pour vérifier leurs empreintes. */
    @Query(
        """
        SELECT l.mediaId AS mediaId, l.sizeBytes AS localSizeBytes, l.quickXorHash AS localQuickXorHash,
            (l.quickXorHash IS NOT NULL AND l.hashSignature = (l.sizeBytes || ':' || l.dateModified || ':' || l.generation)) AS localHashCurrent,
            o.id AS cloudSourceId, o.externalId AS cloudItemId, o.sizeBytes AS cloudSizeBytes, o.quickXorHash AS cloudQuickXorHash
        FROM media_source l JOIN media_source o ON o.mediaId = l.mediaId AND o.kind = 'ONEDRIVE'
        WHERE l.kind = 'LOCAL'
        """,
    )
    suspend fun mergedMedia(): List<MergedRow>

    /** Revérifié dans la transaction de fusion : le couple est toujours valable. */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM media_source l JOIN media_source o ON o.id = :cloudSourceId
            WHERE l.mediaId = :localMediaId AND l.kind = 'LOCAL' AND l.missingSince IS NULL
              AND l.quickXorHash IS NOT NULL AND l.quickXorHash = o.quickXorHash AND l.sizeBytes = o.sizeBytes
              AND l.hashSignature = (l.sizeBytes || ':' || l.dateModified || ':' || l.generation)
              AND o.kind = 'ONEDRIVE' AND o.missingSince IS NULL AND o.mediaId = :cloudMediaId
              AND NOT EXISTS(SELECT 1 FROM media_source x WHERE x.mediaId = :localMediaId AND x.kind = 'ONEDRIVE')
              AND NOT EXISTS(SELECT 1 FROM media_source y WHERE y.mediaId = :cloudMediaId AND y.kind = 'LOCAL')
              AND NOT EXISTS(SELECT 1 FROM download_task t WHERE t.mediaId = :cloudMediaId AND t.state = 'RUNNING')
        )
        """,
    )
    suspend fun mergeStillValid(localMediaId: Long, cloudMediaId: Long, cloudSourceId: Long): Boolean

    /** Sources OneDrive et copies hors ligne d'un média, rattachées à un autre. */
    @Query("UPDATE media_source SET mediaId = :to WHERE mediaId = :from AND kind IN ('ONEDRIVE', 'OFFLINE_COPY')")
    suspend fun moveCloudSources(from: Long, to: Long)

    @Query("INSERT OR IGNORE INTO favorite (mediaId, addedAt) SELECT :to, addedAt FROM favorite WHERE mediaId = :from")
    suspend fun copyFavorite(from: Long, to: Long)

    @Query("INSERT OR IGNORE INTO album_media (albumId, mediaId, addedAt) SELECT albumId, :to, addedAt FROM album_media WHERE mediaId = :from")
    suspend fun copyAlbumMemberships(from: Long, to: Long)

    @Query("UPDATE album SET coverMediaId = :to WHERE coverMediaId = :from")
    suspend fun moveAlbumCovers(from: Long, to: Long)

    /** Fichiers en cache (original, copie hors ligne) ; une ligne du même type déjà présente l'emporte. */
    @Query("UPDATE OR IGNORE cached_file SET mediaId = :to WHERE mediaId = :from")
    suspend fun moveCachedFiles(from: Long, to: Long)

    @Query("UPDATE download_task SET mediaId = :to WHERE mediaId = :from")
    suspend fun moveDownloadTasks(from: Long, to: Long)

    @Query(
        """
        INSERT OR IGNORE INTO playback_position (mediaId, positionMs, durationMs, updatedAt)
        SELECT :to, positionMs, durationMs, updatedAt FROM playback_position WHERE mediaId = :from
        """,
    )
    suspend fun copyPlaybackPosition(from: Long, to: Long)

    /** Entrée de recherche d'un média supprimé (table virtuelle : pas de cascade). */
    @Query("DELETE FROM media_fts WHERE rowid = :mediaId")
    suspend fun deleteSearchEntry(mediaId: Long)

    @Query("DELETE FROM media WHERE id = :mediaId")
    suspend fun deleteMedia(mediaId: Long)

    // --- Défusion -----------------------------------------------------------------------------

    /** Source OneDrive [cloudSourceId] et copie hors ligne du même élément, vers le média [to]. */
    @Query(
        """
        UPDATE media_source SET mediaId = :to
        WHERE mediaId = :from AND (id = :cloudSourceId OR (kind = 'OFFLINE_COPY' AND externalId = :cloudItemId))
        """,
    )
    suspend fun detachCloudSource(from: Long, to: Long, cloudSourceId: Long, cloudItemId: String)

    /** Fichiers tirés de OneDrive (original temporaire, copie hors ligne), vers le média [to]. */
    @Query("UPDATE cached_file SET mediaId = :to WHERE mediaId = :from AND kind IN ('ORIGINAL', 'OFFLINE')")
    suspend fun detachCloudFiles(from: Long, to: Long)

    @Query("UPDATE download_task SET mediaId = :to WHERE mediaId = :from AND cloudItemId = :cloudItemId")
    suspend fun detachDownloadTasks(from: Long, to: Long, cloudItemId: String)
}
