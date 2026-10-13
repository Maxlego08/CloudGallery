package fr.eva.cloudgallery.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind
import kotlinx.coroutines.flow.Flow

/** Lightweight (externalId, generation) pair used to compare MediaStore with the database. */
data class SourceStamp(
    val id: Long,
    val mediaId: Long,
    val externalId: String,
    val generation: Long,
    val volumeName: String?,
    val missingSince: Long?,
)

/** Fingerprint of a source to re-identify after a MediaStore version change. */
data class StaleSourceRow(
    val id: Long,
    val mediaId: Long,
    val relativePath: String?,
    val sizeBytes: Long,
    val displayName: String,
)

@Dao
interface MediaSourceDao {

    @Query("SELECT * FROM media_source WHERE mediaId = :mediaId")
    suspend fun getForMedia(mediaId: Long): List<MediaSourceEntity>

    @Query("SELECT * FROM media_source WHERE mediaId = :mediaId")
    fun observeForMedia(mediaId: Long): Flow<List<MediaSourceEntity>>

    @Query("SELECT * FROM media_source WHERE kind = :kind AND externalId = :externalId")
    suspend fun find(kind: SourceKind, externalId: String): MediaSourceEntity?

    @Query("SELECT * FROM media_source WHERE kind = :kind AND externalId IN (:externalIds)")
    suspend fun findAll(kind: SourceKind, externalIds: List<String>): List<MediaSourceEntity>

    @Query("SELECT id, mediaId, externalId, generation, volumeName, missingSince FROM media_source WHERE kind = :kind")
    suspend fun stamps(kind: SourceKind): List<SourceStamp>

    @Query("SELECT * FROM media_source WHERE id = :id")
    suspend fun getById(id: Long): MediaSourceEntity?

    /** Hides sources (without overwriting an already set timestamp). */
    @Query("UPDATE media_source SET missingSince = :now WHERE id IN (:ids) AND missingSince IS NULL")
    suspend fun markMissing(ids: List<Long>, now: Long)

    /**
     * MediaStore version changed: _IDs are no longer reliable. LOCAL external ids are prefixed
     * with "stale:" and then re-matched by fingerprint during the full rescan.
     */
    @Query(
        """
        UPDATE media_source SET externalId = 'stale:' || externalId
        WHERE kind = 'LOCAL' AND NOT (externalId >= 'stale:' AND externalId < 'stale;')
        """,
    )
    suspend fun markLocalStale()

    @Query(
        """
        SELECT s.id, s.mediaId, s.relativePath, s.sizeBytes, m.displayName
        FROM media_source s JOIN media m ON m.id = s.mediaId
        WHERE s.kind = 'LOCAL' AND s.externalId >= 'stale:' AND s.externalId < 'stale;'
        """,
    )
    suspend fun staleLocal(): List<StaleSourceRow>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(source: MediaSourceEntity): Long

    @Update
    suspend fun update(source: MediaSourceEntity)

    @Query("DELETE FROM media_source WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT COUNT(*) FROM media_source WHERE kind = :kind AND missingSince IS NULL")
    suspend fun count(kind: SourceKind): Int

    /** True if at least one source of this kind exists, hidden or not (unlike [count]). */
    @Query("SELECT EXISTS(SELECT 1 FROM media_source WHERE kind = :kind)")
    suspend fun existsAny(kind: SourceKind): Boolean

    /** Media with at least one source of this kind (hidden or not). */
    @Query("SELECT DISTINCT mediaId FROM media_source WHERE kind = :kind")
    suspend fun mediaIdsByKind(kind: SourceKind): List<Long>

    @Query("DELETE FROM media_source WHERE kind = :kind")
    suspend fun deleteByKind(kind: SourceKind)

    /** Distinct folders of a source kind (to create/update folder albums). */
    @Query(
        """
        SELECT bucketKey, bucketName, relativePath FROM media_source
        WHERE kind = :kind AND bucketKey IS NOT NULL
        GROUP BY bucketKey
        """,
    )
    suspend fun buckets(kind: SourceKind): List<BucketRow>

    /** Like [buckets], ignoring hidden sources (OneDrive albums). */
    @Query(
        """
        SELECT bucketKey, bucketName, relativePath FROM media_source
        WHERE kind = :kind AND bucketKey IS NOT NULL AND missingSince IS NULL
        GROUP BY bucketKey
        """,
    )
    suspend fun visibleBuckets(kind: SourceKind): List<BucketRow>

    /** Readable name and path of a OneDrive folder, copied onto its sources (no needless rewrite). */
    @Query(
        """
        UPDATE media_source SET bucketName = :name, relativePath = :path
        WHERE kind = 'ONEDRIVE' AND bucketKey = :key AND (bucketName IS NOT :name OR relativePath IS NOT :path)
        """,
    )
    suspend fun updateCloudBucketInfo(key: String, name: String, path: String?)

    /** Media with a visible OneDrive source in these folders (at most 500 keys per call). */
    @Query(
        """
        SELECT DISTINCT mediaId FROM media_source
        WHERE kind = 'ONEDRIVE' AND bucketKey IN (:keys) AND missingSince IS NULL
        """,
    )
    suspend fun visibleCloudMediaIdsInBuckets(keys: List<String>): List<Long>

    /** Hides the OneDrive sources of these folders (folder deleted on OneDrive). */
    @Query(
        """
        UPDATE media_source SET missingSince = :now
        WHERE kind = 'ONEDRIVE' AND bucketKey IN (:keys) AND missingSince IS NULL
        """,
    )
    suspend fun markMissingInBuckets(keys: List<String>, now: Long)

    /**
     * Folders (`cloudBucketKey` keys) that contain hidden OneDrive sources: the deletion markers
     * of their folders and of their ancestors are kept.
     */
    @Query(
        """
        SELECT DISTINCT bucketKey FROM media_source
        WHERE kind = 'ONEDRIVE' AND missingSince IS NOT NULL AND bucketKey IS NOT NULL
        """,
    )
    suspend fun hiddenCloudBuckets(): List<String>

    /**
     * Possible restored folder without a marker: true if a hidden OneDrive source belongs to one
     * of the folders [keys] (newly received folders), or to a folder missing from `cloud_folder`
     * (neither present nor marked). Since v5, a deleted folder keeps a marker as long as its
     * subtree has hidden items: this case now only concerns folders deleted before v5 (their
     * rows were removed), until their items are purged.
     * Keys follow `cloudBucketKey` ("onedrive:<folder id>").
     */
    @Query(
        """
        SELECT EXISTS(
            SELECT 1 FROM media_source
            WHERE kind = 'ONEDRIVE' AND missingSince IS NOT NULL AND bucketKey IS NOT NULL
              AND (bucketKey IN (:keys) OR bucketKey NOT IN (SELECT 'onedrive:' || itemId FROM cloud_folder))
        )
        """,
    )
    suspend fun existsHiddenCloudInBucketsOrOrphaned(keys: List<String>): Boolean

    /** True if this folder contains at least one visible OneDrive source. */
    @Query("SELECT EXISTS(SELECT 1 FROM media_source WHERE kind = 'ONEDRIVE' AND bucketKey = :key AND missingSince IS NULL)")
    suspend fun existsVisibleCloudInBucket(key: String): Boolean

    // --- Offline copies (OFFLINE_COPY) -------------------------------------------------------

    /**
     * An offline copy follows the visibility of its media's OneDrive item: hidden along with it
     * (item or folder deleted on OneDrive), visible again if it comes back. Without a OneDrive
     * source (copy kept after its deletion from OneDrive), it is visible.
     * Called in every sync transaction, right before the flags are recomputed.
     */
    @Query(
        """
        UPDATE media_source SET missingSince = (
            SELECT o.missingSince FROM media_source o
            WHERE o.mediaId = media_source.mediaId AND o.kind = 'ONEDRIVE' ORDER BY o.id LIMIT 1
        )
        WHERE kind = 'OFFLINE_COPY' AND mediaId IN (:mediaIds)
        """,
    )
    suspend fun syncOfflineCopyVisibility(mediaIds: List<Long>)

    @Query("SELECT * FROM media_source WHERE kind = :kind AND mediaId IN (:mediaIds)")
    suspend fun findForMedia(kind: SourceKind, mediaIds: List<Long>): List<MediaSourceEntity>

    @Query("DELETE FROM media_source WHERE kind = :kind AND mediaId IN (:mediaIds)")
    suspend fun deleteForMedia(kind: SourceKind, mediaIds: List<Long>)

    /** Number of sources of this kind, hidden or not. */
    @Query("SELECT COUNT(*) FROM media_source WHERE kind = :kind")
    suspend fun countAll(kind: SourceKind): Int

    /**
     * Offline copies of items deleted from OneDrive: media without a visible OneDrive source
     * (item hidden during the grace period, awaiting a decision, or copy already kept alone).
     * These are the last known copies of these items.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT s.mediaId) FROM media_source s
        WHERE s.kind = 'OFFLINE_COPY'
          AND NOT EXISTS(SELECT 1 FROM media_source o WHERE o.mediaId = s.mediaId AND o.kind = 'ONEDRIVE' AND o.missingSince IS NULL)
          AND NOT EXISTS(SELECT 1 FROM media_source l WHERE l.mediaId = s.mediaId AND l.kind = 'LOCAL' AND l.missingSince IS NULL)
        """,
    )
    suspend fun countOfflineLastCopies(): Int

    /**
     * Like [countOfflineLastCopies], among [mediaIds]: offline copies, visible or hidden, with
     * neither a visible OneDrive source nor a visible local file. Same rule as "Free up", which
     * also deletes hidden copies.
     */
    @Query(
        """
        SELECT COUNT(DISTINCT s.mediaId) FROM media_source s
        WHERE s.kind = 'OFFLINE_COPY' AND s.mediaId IN (:mediaIds)
          AND NOT EXISTS(SELECT 1 FROM media_source o WHERE o.mediaId = s.mediaId AND o.kind = 'ONEDRIVE' AND o.missingSince IS NULL)
          AND NOT EXISTS(SELECT 1 FROM media_source l WHERE l.mediaId = s.mediaId AND l.kind = 'LOCAL' AND l.missingSince IS NULL)
        """,
    )
    suspend fun countOfflineLastCopiesAmong(mediaIds: List<Long>): Int

    /**
     * Media awaiting a decision (decision D1): offline copy of an item hidden on OneDrive since
     * at least [expiredBefore] (grace period elapsed).
     */
    @Query(
        """
        SELECT DISTINCT o.mediaId FROM media_source o
        WHERE o.kind = 'ONEDRIVE' AND o.missingSince IS NOT NULL AND o.missingSince <= :expiredBefore
          AND EXISTS(SELECT 1 FROM media_source c WHERE c.mediaId = o.mediaId AND c.kind = 'OFFLINE_COPY')
        """,
    )
    suspend fun awaitingDecisionMediaIds(expiredBefore: Long): List<Long>

    /** Like [awaitingDecisionMediaIds], observable (prompt on the Account screen and the Menu). */
    @Query(
        """
        SELECT DISTINCT o.mediaId FROM media_source o
        WHERE o.kind = 'ONEDRIVE' AND o.missingSince IS NOT NULL AND o.missingSince <= :expiredBefore
          AND EXISTS(SELECT 1 FROM media_source c WHERE c.mediaId = o.mediaId AND c.kind = 'OFFLINE_COPY')
        ORDER BY o.mediaId
        """,
    )
    fun observeAwaitingDecisionMediaIds(expiredBefore: Long): Flow<List<Long>>

    /**
     * Among [mediaIds] (list confirmed by the user), those still awaiting a decision: to be
     * re-read inside the transaction that applies the decision (an item that came back on
     * OneDrive in the meantime is no longer concerned).
     */
    @Query(
        """
        SELECT DISTINCT o.mediaId FROM media_source o
        WHERE o.kind = 'ONEDRIVE' AND o.mediaId IN (:mediaIds)
          AND o.missingSince IS NOT NULL AND o.missingSince <= :expiredBefore
          AND EXISTS(SELECT 1 FROM media_source c WHERE c.mediaId = o.mediaId AND c.kind = 'OFFLINE_COPY')
        """,
    )
    suspend fun awaitingDecisionAmong(mediaIds: List<Long>, expiredBefore: Long): List<Long>

    /** Deletes hidden sources of this kind for [mediaIds] (never a visible source). */
    @Query("DELETE FROM media_source WHERE kind = :kind AND mediaId IN (:mediaIds) AND missingSince IS NOT NULL")
    suspend fun deleteHiddenForMedia(kind: SourceKind, mediaIds: List<Long>)

    /**
     * Visible OneDrive item of each media in [mediaIds] without an offline copy ("Keep offline"
     * candidates), with what is needed to download it.
     */
    @Query(
        """
        SELECT s.mediaId, s.externalId AS itemId, s.cTag, s.sizeBytes FROM media_source s
        WHERE s.kind = 'ONEDRIVE' AND s.missingSince IS NULL AND s.mediaId IN (:mediaIds)
          AND NOT EXISTS(SELECT 1 FROM media_source c WHERE c.mediaId = s.mediaId AND c.kind = 'OFFLINE_COPY')
        """,
    )
    suspend fun keepOfflineCandidates(mediaIds: List<Long>): List<OfflineCandidateRow>

    /**
     * Visible OneDrive items of [mediaIds] that already have an offline copy but whose update
     * (new version) is paused or failed: "Resume download".
     */
    @Query(
        """
        SELECT s.mediaId, s.externalId AS itemId, s.cTag, s.sizeBytes FROM media_source s
        WHERE s.kind = 'ONEDRIVE' AND s.missingSince IS NULL AND s.mediaId IN (:mediaIds)
          AND EXISTS(SELECT 1 FROM media_source c WHERE c.mediaId = s.mediaId AND c.kind = 'OFFLINE_COPY')
          AND EXISTS(SELECT 1 FROM download_task t WHERE t.mediaId = s.mediaId AND t.state IN ('PAUSED', 'FAILED'))
        """,
    )
    suspend fun stoppedUpdateCandidates(mediaIds: List<Long>): List<OfflineCandidateRow>

    /**
     * Space saver: visible local copies already present on OneDrive, verified by hash (see
     * [SPACE_SAVER_SELECT]), most recent first.
     */
    @Query("$SPACE_SAVER_SELECT ORDER BY m.dateTaken DESC, l.id DESC")
    suspend fun spaceSaverCandidates(): List<SpaceSaverRow>

    /** Same rules, limited to sources [sourceIds]: recheck right before deletion. */
    @Query("$SPACE_SAVER_SELECT AND l.id IN (:sourceIds)")
    suspend fun spaceSaverCandidatesAmong(sourceIds: List<Long>): List<SpaceSaverRow>
}

/**
 * Local copies the space saver may offer. The rules apply to sources, whether or not the local
 * copy and the OneDrive item are grouped in the same media item:
 * - visible LOCAL source, with a MediaStore URI;
 * - QuickXorHash computed by the app and up to date: `hashSignature` equals the file's current
 *   signature, in the format `"<sizeBytes>:<dateModified>:<generation>"`;
 * - at least one visible OneDrive item with the same hash and the same size.
 * [SpaceSaverRow.mergedWithCloud]: that OneDrive item belongs to the same media item;
 * [SpaceSaverRow.hasUserData]: media is a favorite or a member of a user album.
 */
internal const val SPACE_SAVER_SELECT = """
    SELECT l.id AS sourceId, l.mediaId, l.uri, l.sizeBytes, l.dateModified,
           m.mediaType, m.mimeType, m.displayName, m.dateTaken,
           EXISTS(SELECT 1 FROM media_source c WHERE c.mediaId = l.mediaId AND c.kind = 'ONEDRIVE'
                  AND c.missingSince IS NULL AND c.quickXorHash = l.quickXorHash AND c.sizeBytes = l.sizeBytes) AS mergedWithCloud,
           (EXISTS(SELECT 1 FROM favorite f WHERE f.mediaId = l.mediaId)
            OR EXISTS(SELECT 1 FROM album_media am JOIN album a ON a.id = am.albumId
                      WHERE am.mediaId = l.mediaId AND a.type = 'VIRTUAL')) AS hasUserData
    FROM media_source l
    JOIN media m ON m.id = l.mediaId
    WHERE l.kind = 'LOCAL' AND l.missingSince IS NULL AND l.uri IS NOT NULL
      AND l.quickXorHash IS NOT NULL AND l.quickXorHash != ''
      AND l.hashSignature = (l.sizeBytes || ':' || l.dateModified || ':' || l.generation)
      AND EXISTS(SELECT 1 FROM media_source c WHERE c.kind = 'ONEDRIVE' AND c.missingSince IS NULL
                 AND c.quickXorHash = l.quickXorHash AND c.sizeBytes = l.sizeBytes)
"""

/** Local copy offered by the space saver (see [SPACE_SAVER_SELECT]). */
data class SpaceSaverRow(
    val sourceId: Long,
    val mediaId: Long,
    val uri: String,
    val sizeBytes: Long,
    val dateModified: Long,
    val mediaType: MediaType,
    val mimeType: String?,
    val displayName: String,
    val dateTaken: Long,
    val mergedWithCloud: Boolean,
    val hasUserData: Boolean,
)

/** OneDrive item to keep offline. */
data class OfflineCandidateRow(
    val mediaId: Long,
    val itemId: String,
    val cTag: String?,
    val sizeBytes: Long,
)

data class BucketRow(
    val bucketKey: String,
    val bucketName: String?,
    val relativePath: String?,
)
