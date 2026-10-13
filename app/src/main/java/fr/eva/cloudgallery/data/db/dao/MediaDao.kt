package fr.eva.cloudgallery.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaRow
import fr.eva.cloudgallery.domain.model.MediaType
import kotlinx.coroutines.flow.Flow

internal const val ROW_COLUMNS = """
    m.id, m.mediaType, m.mimeType, m.dateTaken, m.dateModified, m.durationMs, m.width, m.height,
    m.orientation, m.primaryUri, m.hasLocal, m.hasCloud, m.hasOffline,
    EXISTS(SELECT 1 FROM favorite f WHERE f.mediaId = m.id) AS isFavorite,
    m.cloudItemId, m.cloudCTag
"""

/**
 * Source that makes a media item a member of the folder of its `bucketKey`: a visible source, or
 * a hidden source of a media item that has no visible source attached to a folder (single-source
 * media, or offline copy of a hidden item: it stays in its folder as long as it is displayed).
 * A merged media item (local file + OneDrive item) thus only appears in the folders of its
 * visible sources. Same rule in ALBUM_ROWS (AlbumDao).
 */
internal const val FOLDER_MEMBER_SOURCE = """
    (s.missingSince IS NULL OR NOT EXISTS(
        SELECT 1 FROM media_source v WHERE v.mediaId = s.mediaId AND v.missingSince IS NULL AND v.bucketKey IS NOT NULL
    ))
"""

/** Members of an album: folder (via media_source.bucketKey) or virtual album (album_media). */
private const val ALBUM_MEMBERS = """
    SELECT s.mediaId FROM media_source s JOIN album a ON a.sourceKey = s.bucketKey
    WHERE a.id = :albumId AND $FOLDER_MEMBER_SOURCE
    UNION
    SELECT am.mediaId FROM album_media am WHERE am.albumId = :albumId
"""

internal const val ORDER = "ORDER BY m.dateTaken DESC, m.id DESC"

/**
 * Displayable media: at least one source present. A media item whose sources are all hidden
 * (missingSince, see MediaSourceEntity) stays in the database with its favorites and albums.
 */
internal const val VISIBLE = "(m.hasLocal = 1 OR m.hasCloud = 1 OR m.hasOffline = 1)"

/**
 * Outside albums hidden "in Albums and Photos" (album.hideMedia): phone or OneDrive folder (one
 * of the media's sources is stored there) or custom album.
 */
internal const val NOT_IN_HIDDEN_ALBUM = """
    NOT EXISTS (
        SELECT 1 FROM media_source hs JOIN album ha ON ha.sourceKey = hs.bucketKey
        WHERE hs.mediaId = m.id AND ha.hideMedia = 1
    )
    AND NOT EXISTS (
        SELECT 1 FROM album_media ham JOIN album hv ON hv.id = ham.albumId
        WHERE ham.mediaId = m.id AND hv.hideMedia = 1
    )
"""

/**
 * Photos and Videos ("Show OneDrive items in Photos" setting): without `:includeCloudOnly`, only
 * media present on the device or available offline are listed; OneDrive-only items stay in
 * their OneDrive albums, Favorites and the user's albums. Media of albums hidden "in Albums and
 * Photos" are excluded ([NOT_IN_HIDDEN_ALBUM]). Subset of [VISIBLE].
 */
private const val LIBRARY = """
    (m.hasLocal = 1 OR m.hasOffline = 1 OR (:includeCloudOnly = 1 AND m.hasCloud = 1)) AND $NOT_IN_HIDDEN_ALBUM
"""

/** Items located between two items of the sorted list (bounds included). */
internal const val BETWEEN = """
    (m.dateTaken < :newerDate OR (m.dateTaken = :newerDate AND m.id <= :newerId))
    AND (m.dateTaken > :olderDate OR (m.dateTaken = :olderDate AND m.id >= :olderId))
"""

internal const val IN_PERIOD = "m.dateTaken >= :startMs AND m.dateTaken < :endMs"

@Dao
interface MediaDao {

    // --- Paged grids --------------------------------------------------------------------

    @Query("SELECT $ROW_COLUMNS FROM media m WHERE $LIBRARY $ORDER")
    fun pagingTimeline(includeCloudOnly: Boolean): PagingSource<Int, MediaRow>

    @Query("SELECT $ROW_COLUMNS FROM media m WHERE $LIBRARY AND m.mediaType = 'VIDEO' $ORDER")
    fun pagingVideos(includeCloudOnly: Boolean): PagingSource<Int, MediaRow>

    @Query("SELECT $ROW_COLUMNS FROM media m WHERE $VISIBLE AND m.id IN (SELECT mediaId FROM favorite) $ORDER")
    fun pagingFavorites(): PagingSource<Int, MediaRow>

    @Query("SELECT $ROW_COLUMNS FROM media m WHERE $VISIBLE AND m.id IN ($ALBUM_MEMBERS) $ORDER")
    fun pagingAlbum(albumId: Long): PagingSource<Int, MediaRow>

    // --- Ordered ids (viewer) -----------------------------------------------------------

    @Query("SELECT m.id FROM media m WHERE $LIBRARY $ORDER")
    fun observeTimelineIds(includeCloudOnly: Boolean): Flow<List<Long>>

    @Query("SELECT m.id FROM media m WHERE $LIBRARY AND m.mediaType = 'VIDEO' $ORDER")
    fun observeVideoIds(includeCloudOnly: Boolean): Flow<List<Long>>

    @Query("SELECT m.id FROM media m WHERE $VISIBLE AND m.id IN (SELECT mediaId FROM favorite) $ORDER")
    fun observeFavoriteIds(): Flow<List<Long>>

    @Query("SELECT m.id FROM media m WHERE $VISIBLE AND m.id IN ($ALBUM_MEMBERS) $ORDER")
    fun observeAlbumIds(albumId: Long): Flow<List<Long>>

    // --- Ordered dates (fast scrolling), same order as the grids ------------------------

    @Query("SELECT m.dateTaken FROM media m WHERE $LIBRARY $ORDER")
    fun observeTimelineDates(includeCloudOnly: Boolean): Flow<List<Long>>

    @Query("SELECT m.dateTaken FROM media m WHERE $LIBRARY AND m.mediaType = 'VIDEO' $ORDER")
    fun observeVideoDates(includeCloudOnly: Boolean): Flow<List<Long>>

    @Query("SELECT m.dateTaken FROM media m WHERE $VISIBLE AND m.id IN (SELECT mediaId FROM favorite) $ORDER")
    fun observeFavoriteDates(): Flow<List<Long>>

    @Query("SELECT m.dateTaken FROM media m WHERE $VISIBLE AND m.id IN ($ALBUM_MEMBERS) $ORDER")
    fun observeAlbumDates(albumId: Long): Flow<List<Long>>

    // --- Counts -------------------------------------------------------------------------

    @Query("SELECT COUNT(*) FROM media m WHERE $LIBRARY")
    fun observeTimelineCount(includeCloudOnly: Boolean): Flow<Int>

    @Query("SELECT COUNT(*) FROM media m WHERE $LIBRARY AND m.mediaType = 'VIDEO'")
    fun observeVideoCount(includeCloudOnly: Boolean): Flow<Int>

    @Query("SELECT COUNT(*) FROM media m WHERE $VISIBLE AND m.id IN (SELECT mediaId FROM favorite)")
    fun observeFavoriteCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media m WHERE $VISIBLE AND m.id IN ($ALBUM_MEMBERS)")
    fun observeAlbumCount(albumId: Long): Flow<Int>

    // --- Id ranges (select by day / by drag), same order as the grids ---------------------

    @Query("SELECT m.id FROM media m WHERE $LIBRARY AND $IN_PERIOD $ORDER")
    suspend fun timelineIdsInPeriod(includeCloudOnly: Boolean, startMs: Long, endMs: Long): List<Long>

    @Query("SELECT m.id FROM media m WHERE $LIBRARY AND m.mediaType = 'VIDEO' AND $IN_PERIOD $ORDER")
    suspend fun videoIdsInPeriod(includeCloudOnly: Boolean, startMs: Long, endMs: Long): List<Long>

    @Query("SELECT m.id FROM media m WHERE $VISIBLE AND m.id IN (SELECT mediaId FROM favorite) AND $IN_PERIOD $ORDER")
    suspend fun favoriteIdsInPeriod(startMs: Long, endMs: Long): List<Long>

    @Query("SELECT m.id FROM media m WHERE $VISIBLE AND m.id IN ($ALBUM_MEMBERS) AND $IN_PERIOD $ORDER")
    suspend fun albumIdsInPeriod(albumId: Long, startMs: Long, endMs: Long): List<Long>

    @Query("SELECT m.id FROM media m WHERE $LIBRARY AND $BETWEEN $ORDER")
    suspend fun timelineIdsBetween(includeCloudOnly: Boolean, newerDate: Long, newerId: Long, olderDate: Long, olderId: Long): List<Long>

    @Query("SELECT m.id FROM media m WHERE $LIBRARY AND m.mediaType = 'VIDEO' AND $BETWEEN $ORDER")
    suspend fun videoIdsBetween(includeCloudOnly: Boolean, newerDate: Long, newerId: Long, olderDate: Long, olderId: Long): List<Long>

    @Query("SELECT m.id FROM media m WHERE $VISIBLE AND m.id IN (SELECT mediaId FROM favorite) AND $BETWEEN $ORDER")
    suspend fun favoriteIdsBetween(newerDate: Long, newerId: Long, olderDate: Long, olderId: Long): List<Long>

    @Query("SELECT m.id FROM media m WHERE $VISIBLE AND m.id IN ($ALBUM_MEMBERS) AND $BETWEEN $ORDER")
    suspend fun albumIdsBetween(albumId: Long, newerDate: Long, newerId: Long, olderDate: Long, olderId: Long): List<Long>

    // --- Single-item access -------------------------------------------------------------

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun getById(id: Long): MediaEntity?

    @Query("SELECT * FROM media WHERE id = :id")
    fun observeById(id: Long): Flow<MediaEntity?>

    @Query("SELECT $ROW_COLUMNS FROM media m WHERE m.id IN (:ids)")
    suspend fun getRows(ids: List<Long>): List<MediaRow>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE mediaId = :id)")
    fun observeIsFavorite(id: Long): Flow<Boolean>

    /** Media among [ids] that have a usable local file (share, delete, copy actions). */
    @Query("SELECT COUNT(*) FROM media WHERE id IN (:ids) AND hasLocal = 1 AND primaryUri IS NOT NULL")
    suspend fun countWithLocalFile(ids: List<Long>): Int

    /**
     * Shareable media among [ids]: local file, offline copy or visible OneDrive item (downloaded
     * at share time). Read-only: nothing else is allowed this way.
     */
    @Query(
        "SELECT COUNT(*) FROM media WHERE id IN (:ids) AND " +
            "((hasLocal = 1 AND primaryUri IS NOT NULL) OR hasOffline = 1 OR hasCloud = 1)",
    )
    suspend fun countShareable(ids: List<Long>): Int

    /**
     * Visible OneDrive items without an offline copy among [ids] ("Keep offline"), excluding
     * items already queued or downloading (paused or failed: counted, "Keep offline" restarts
     * them).
     */
    @Query(
        "SELECT COUNT(*) FROM media m WHERE m.id IN (:ids) AND m.hasCloud = 1 AND m.hasOffline = 0 " +
            "AND NOT EXISTS(SELECT 1 FROM download_task t WHERE t.mediaId = m.id AND t.state IN ('QUEUED', 'RUNNING'))",
    )
    suspend fun countKeepableOffline(ids: List<Long>): Int

    /** Media among [ids] that have a visible offline copy ("Free up space"). */
    @Query("SELECT COUNT(*) FROM media WHERE id IN (:ids) AND hasOffline = 1")
    suspend fun countWithOfflineCopy(ids: List<Long>): Int

    /** Observable grid row for a single media item (Single context). */
    @Query("SELECT $ROW_COLUMNS FROM media m WHERE m.id = :id")
    fun observeRow(id: Long): Flow<MediaRow?>

    /** Full entities in batch (indexing: keep the fields that do not come from MediaStore). */
    @Query("SELECT * FROM media WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<MediaEntity>

    // --- Writes (indexing) --------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(media: MediaEntity): Long

    @Update
    suspend fun update(media: MediaEntity)

    @Query("DELETE FROM media WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Logical media that no longer have any source. */
    @Query("DELETE FROM media WHERE id NOT IN (SELECT DISTINCT mediaId FROM media_source)")
    suspend fun deleteOrphans(): Int

    /**
     * Recomputes the denormalized columns from the visible sources: a hidden source
     * (missingSince) no longer counts, and `primaryUri` is only filled in by a present local file
     * (non-null uri = usable local file).
     */
    @Query(
        """
        UPDATE media SET
            hasLocal = EXISTS(SELECT 1 FROM media_source s WHERE s.mediaId = media.id AND s.kind = 'LOCAL' AND s.missingSince IS NULL),
            hasCloud = EXISTS(SELECT 1 FROM media_source s WHERE s.mediaId = media.id AND s.kind = 'ONEDRIVE' AND s.missingSince IS NULL),
            hasOffline = EXISTS(SELECT 1 FROM media_source s WHERE s.mediaId = media.id AND s.kind = 'OFFLINE_COPY' AND s.missingSince IS NULL),
            primaryUri = (SELECT s.uri FROM media_source s WHERE s.mediaId = media.id AND s.kind = 'LOCAL'
                AND s.missingSince IS NULL ORDER BY s.id LIMIT 1),
            cloudItemId = (SELECT s.externalId FROM media_source s WHERE s.mediaId = media.id AND s.kind = 'ONEDRIVE'
                AND s.missingSince IS NULL ORDER BY s.id LIMIT 1),
            cloudCTag = (SELECT s.cTag FROM media_source s WHERE s.mediaId = media.id AND s.kind = 'ONEDRIVE'
                AND s.missingSince IS NULL ORDER BY s.id LIMIT 1)
        WHERE id IN (:ids)
        """,
    )
    suspend fun refreshSourceFlags(ids: List<Long>)

    /**
     * Media whose thumbnail and preview come from OneDrive (visible OneDrive item, no local
     * file): caching of all of OneDrive, in pages of descending id (most recent first),
     * [beforeId] excluded.
     */
    @Query(
        """
        SELECT id, mediaType, width, height, dateModified, cloudItemId, cloudCTag FROM media
        WHERE cloudItemId IS NOT NULL AND hasLocal = 0 AND id < :beforeId
        ORDER BY id DESC LIMIT :limit
        """,
    )
    suspend fun precacheCandidates(beforeId: Long, limit: Int): List<PrecacheRow>

    @Query("SELECT COUNT(*) FROM media WHERE cloudItemId IS NOT NULL AND hasLocal = 0")
    suspend fun countPrecacheCandidates(): Int
}

/** Media item to cache (see [MediaDao.precacheCandidates]). */
data class PrecacheRow(
    val id: Long,
    val mediaType: MediaType,
    val width: Int,
    val height: Int,
    val dateModified: Long,
    val cloudItemId: String,
    val cloudCTag: String?,
)
