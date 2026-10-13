package fr.eva.cloudgallery.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import fr.eva.cloudgallery.data.dedup.HashTarget
import fr.eva.cloudgallery.data.dedup.MergeCandidateRow
import fr.eva.cloudgallery.data.dedup.MergedRow

/*
 * Current signature of a local source: `sizeBytes || ':' || dateModified || ':' || generation`,
 * identical to `localHashSignature` (data/dedup/DedupLogic.kt). A local hash is only up to date
 * when `hashSignature` equals this expression.
 */

/**
 * Local file hashes and local / OneDrive merging (index only). The move operations cover every
 * table that references `media.id`: sources, favorites, album memberships, album cover, cached
 * files, downloads, playback position and search index.
 */
@Dao
interface DedupDao {

    // --- Local hashes ---------------------------------------------------------------------------

    /** Visible local files whose hash is missing or stale. */
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

    /** Sizes of visible OneDrive items (prioritizes hash computation). */
    @Query("SELECT DISTINCT sizeBytes FROM media_source WHERE kind = 'ONEDRIVE' AND missingSince IS NULL")
    suspend fun visibleCloudSizes(): List<Long>

    /**
     * Stores the hash of a local file, only if the source is still in the state read before the
     * computation ([signature]): a file modified in the meantime will be recomputed.
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

    // --- Merge --------------------------------------------------------------------------------

    /** Among [mediaIds], media that have a local file (even hidden): local metadata. */
    @Query("SELECT DISTINCT mediaId FROM media_source WHERE kind = 'LOCAL' AND mediaId IN (:mediaIds)")
    suspend fun withLocalSource(mediaIds: List<Long>): List<Long>

    /**
     * Visible local file / OneDrive item pairs with the same hash and the same size: local hash
     * up to date, local media without a OneDrive source (even hidden), OneDrive media without a
     * local file and without a running download (offline copy being written).
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

    /** Merged media (one local source and one OneDrive source), to verify their hashes. */
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

    /** Rechecked inside the merge transaction: the pair is still valid. */
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

    /** OneDrive sources and offline copies of a media item, reattached to another one. */
    @Query("UPDATE media_source SET mediaId = :to WHERE mediaId = :from AND kind IN ('ONEDRIVE', 'OFFLINE_COPY')")
    suspend fun moveCloudSources(from: Long, to: Long)

    @Query("INSERT OR IGNORE INTO favorite (mediaId, addedAt) SELECT :to, addedAt FROM favorite WHERE mediaId = :from")
    suspend fun copyFavorite(from: Long, to: Long)

    @Query("INSERT OR IGNORE INTO album_media (albumId, mediaId, addedAt) SELECT albumId, :to, addedAt FROM album_media WHERE mediaId = :from")
    suspend fun copyAlbumMemberships(from: Long, to: Long)

    @Query("UPDATE album SET coverMediaId = :to WHERE coverMediaId = :from")
    suspend fun moveAlbumCovers(from: Long, to: Long)

    /** Cached files (original, offline copy); an existing row of the same kind wins. */
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

    /** Search entry of a deleted media item (virtual table: no cascade). */
    @Query("DELETE FROM media_fts WHERE rowid = :mediaId")
    suspend fun deleteSearchEntry(mediaId: Long)

    @Query("DELETE FROM media WHERE id = :mediaId")
    suspend fun deleteMedia(mediaId: Long)

    // --- Unmerge ------------------------------------------------------------------------------

    /** OneDrive source [cloudSourceId] and offline copy of the same item, moved to media [to]. */
    @Query(
        """
        UPDATE media_source SET mediaId = :to
        WHERE mediaId = :from AND (id = :cloudSourceId OR (kind = 'OFFLINE_COPY' AND externalId = :cloudItemId))
        """,
    )
    suspend fun detachCloudSource(from: Long, to: Long, cloudSourceId: Long, cloudItemId: String)

    /** Files pulled from OneDrive (temporary original, offline copy), moved to media [to]. */
    @Query("UPDATE cached_file SET mediaId = :to WHERE mediaId = :from AND kind IN ('ORIGINAL', 'OFFLINE')")
    suspend fun detachCloudFiles(from: Long, to: Long)

    @Query("UPDATE download_task SET mediaId = :to WHERE mediaId = :from AND cloudItemId = :cloudItemId")
    suspend fun detachDownloadTasks(from: Long, to: Long, cloudItemId: String)
}
