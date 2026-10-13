package fr.eva.cloudgallery.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import fr.eva.cloudgallery.data.db.entity.MediaRow
import kotlinx.coroutines.flow.Flow

/** Capture date in the device's local time (`dateTaken` is in milliseconds). */
private const val LOCAL_DATE = "m.dateTaken / 1000, 'unixepoch', 'localtime'"

/**
 * Folder names of a media item for the index: folders of its visible sources, virtual albums
 * containing it, custom names of folder albums. Each subquery follows an index
 * (`media_source.mediaId`, `album_media.mediaId`, `album.sourceKey`).
 */
internal const val FTS_FOLDERS_SQL = """trim(
    COALESCE((SELECT group_concat(DISTINCT s.bucketName) FROM media_source s
        WHERE s.mediaId = m.id AND s.missingSince IS NULL AND s.bucketName IS NOT NULL), '') || ' ' ||
    COALESCE((SELECT group_concat(DISTINCT COALESCE(a.customName, a.name)) FROM album_media am
        JOIN album a ON a.id = am.albumId WHERE am.mediaId = m.id), '') || ' ' ||
    COALESCE((SELECT group_concat(DISTINCT a2.customName) FROM media_source s2
        JOIN album a2 ON a2.sourceKey = s2.bucketKey
        WHERE s2.mediaId = m.id AND s2.missingSince IS NULL AND a2.customName IS NOT NULL), ''))"""

/**
 * Date and type labels of a media item: year, month (French and English), day of month, day of
 * week, then "vidéo video" or "photo image". Same order as MONTH_LABELS and WEEKDAY_LABELS
 * (SearchLogic.kt, checked by SearchLogicTest). Unknown date: type only.
 */
internal const val FTS_DATES_SQL = """trim(CASE WHEN m.dateTaken > 0 THEN
    strftime('%Y', $LOCAL_DATE) || ' ' ||
    CASE strftime('%m', $LOCAL_DATE)
        WHEN '01' THEN 'janvier january' WHEN '02' THEN 'février february' WHEN '03' THEN 'mars march'
        WHEN '04' THEN 'avril april' WHEN '05' THEN 'mai may' WHEN '06' THEN 'juin june'
        WHEN '07' THEN 'juillet july' WHEN '08' THEN 'août august' WHEN '09' THEN 'septembre september'
        WHEN '10' THEN 'octobre october' WHEN '11' THEN 'novembre november' WHEN '12' THEN 'décembre december'
        ELSE '' END || ' ' ||
    CAST(strftime('%d', $LOCAL_DATE) AS INTEGER) || ' ' ||
    CASE strftime('%w', $LOCAL_DATE)
        WHEN '0' THEN 'dimanche sunday' WHEN '1' THEN 'lundi monday' WHEN '2' THEN 'mardi tuesday'
        WHEN '3' THEN 'mercredi wednesday' WHEN '4' THEN 'jeudi thursday' WHEN '5' THEN 'vendredi friday'
        WHEN '6' THEN 'samedi saturday' ELSE '' END
    ELSE '' END || ' ' ||
    CASE m.mediaType WHEN 'VIDEO' THEN 'vidéo video' ELSE 'photo image' END)"""

/** Media whose index entry matches the FTS expression [match] (see ftsMatchExpression). */
private const val MATCHES = "m.id IN (SELECT rowid FROM media_fts WHERE media_fts MATCH :match)"

/**
 * Full-text search over `media_fts` and upkeep of that index from the database.
 *
 * Results: visible media ([VISIBLE]: at least one source present), OneDrive-only items included
 * whatever the Photos setting (they are also visible in their albums), in grid order.
 */
@Dao
interface SearchDao {

    // --- Results (grid, viewer, selection) ------------------------------------------------

    @Query("SELECT $ROW_COLUMNS FROM media m WHERE $VISIBLE AND $MATCHES $ORDER")
    fun pagingSearch(match: String): PagingSource<Int, MediaRow>

    @Query("SELECT m.id FROM media m WHERE $VISIBLE AND $MATCHES $ORDER")
    fun observeSearchIds(match: String): Flow<List<Long>>

    @Query("SELECT COUNT(*) FROM media m WHERE $VISIBLE AND $MATCHES")
    fun observeSearchCount(match: String): Flow<Int>

    /** Result dates in grid order (fast scrolling). */
    @Query("SELECT m.dateTaken FROM media m WHERE $VISIBLE AND $MATCHES $ORDER")
    fun observeSearchDates(match: String): Flow<List<Long>>

    @Query("SELECT m.id FROM media m WHERE $VISIBLE AND $MATCHES AND $IN_PERIOD $ORDER")
    suspend fun searchIdsInPeriod(match: String, startMs: Long, endMs: Long): List<Long>

    @Query("SELECT m.id FROM media m WHERE $VISIBLE AND $MATCHES AND $BETWEEN $ORDER")
    suspend fun searchIdsBetween(match: String, newerDate: Long, newerId: Long, olderDate: Long, olderId: Long): List<Long>

    // --- Index upkeep ----------------------------------------------------------------------

    /** Entries of deleted media. */
    @Query("DELETE FROM media_fts WHERE rowid NOT IN (SELECT id FROM media)")
    suspend fun deleteOrphanEntries(): Int

    /** Entries whose name, folders or date labels are out of date. */
    @Query(
        """DELETE FROM media_fts WHERE rowid IN (
            SELECT m.id FROM media m JOIN media_fts f ON f.rowid = m.id
            WHERE f.name != m.displayName OR f.folders != $FTS_FOLDERS_SQL OR f.dates != $FTS_DATES_SQL)""",
    )
    suspend fun deleteStaleEntries(): Int

    /** Entries of media missing from the index (new, or just removed as stale). */
    @Query(
        """INSERT INTO media_fts (rowid, name, folders, dates)
            SELECT m.id, m.displayName, $FTS_FOLDERS_SQL, $FTS_DATES_SQL FROM media m
            WHERE m.id NOT IN (SELECT rowid FROM media_fts)""",
    )
    suspend fun insertMissingEntries()

    @Query("SELECT COUNT(*) FROM media_fts")
    suspend fun entryCount(): Int

    /**
     * Incremental update, in one transaction: only new, deleted or modified entries are
     * rewritten. Returns the number of removed entries (deleted or stale).
     */
    @Transaction
    suspend fun refreshIndex(): Int {
        val removed = deleteOrphanEntries() + deleteStaleEntries()
        insertMissingEntries()
        return removed
    }
}
