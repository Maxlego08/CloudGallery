package fr.eva.cloudgallery.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.data.db.entity.CachedFileEntity
import fr.eva.cloudgallery.data.db.entity.DownloadState
import fr.eva.cloudgallery.data.db.entity.DownloadTaskEntity
import fr.eva.cloudgallery.data.db.entity.FavoriteEntity
import fr.eva.cloudgallery.data.db.entity.PlaybackPositionEntity
import fr.eva.cloudgallery.data.db.entity.SyncCursorEntity
import fr.eva.cloudgallery.data.db.entity.UserSettingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoriteDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(favorites: List<FavoriteEntity>)

    @Query("DELETE FROM favorite WHERE mediaId IN (:mediaIds)")
    suspend fun deleteAll(mediaIds: List<Long>)

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE mediaId = :mediaId)")
    suspend fun isFavorite(mediaId: Long): Boolean
}

@Dao
interface SyncCursorDao {
    @Query("SELECT * FROM sync_cursor WHERE provider = :provider")
    suspend fun get(provider: String): SyncCursorEntity?

    @Query("SELECT * FROM sync_cursor WHERE provider = :provider")
    fun observe(provider: String): Flow<SyncCursorEntity?>

    @Upsert
    suspend fun upsert(cursor: SyncCursorEntity)

    @Query("DELETE FROM sync_cursor WHERE provider = :provider")
    suspend fun delete(provider: String)

    /** Clears the resume link (deltaLink): the next sync will start from scratch. */
    @Query("UPDATE sync_cursor SET cursor = NULL WHERE provider = :provider")
    suspend fun clearLink(provider: String)
}

@Dao
interface PlaybackPositionDao {
    @Query("SELECT positionMs FROM playback_position WHERE mediaId = :mediaId")
    suspend fun get(mediaId: Long): Long?

    @Upsert
    suspend fun upsert(position: PlaybackPositionEntity)

    @Query("DELETE FROM playback_position WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: Long)
}

@Dao
interface UserSettingDao {
    @Query("SELECT value FROM user_settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Upsert
    suspend fun upsert(setting: UserSettingEntity)
}

/**
 * OneDrive files copied to the device (temporary originals, offline copies). A single row per
 * (media, kind): [replace] updates the existing row instead of creating a new one.
 */
@Dao
interface CachedFileDao {
    @Query("SELECT * FROM cached_file WHERE mediaId = :mediaId AND kind = :kind")
    suspend fun get(mediaId: Long, kind: CacheKind): CachedFileEntity?

    @Query("SELECT * FROM cached_file WHERE mediaId IN (:mediaIds) AND kind = :kind")
    suspend fun getMany(mediaIds: List<Long>, kind: CacheKind): List<CachedFileEntity>

    @Query("SELECT * FROM cached_file WHERE mediaId = :mediaId AND kind = :kind")
    fun observe(mediaId: Long, kind: CacheKind): Flow<CachedFileEntity?>

    @Query("SELECT * FROM cached_file WHERE kind = :kind")
    suspend fun listByKind(kind: CacheKind): List<CachedFileEntity>

    @Query("SELECT COUNT(*) FROM cached_file WHERE kind = :kind")
    fun observeCount(kind: CacheKind): Flow<Int>

    /**
     * Raw insert. Do not use it to replace a row: with id 0 and an existing (media, kind) row,
     * Room's upsert falls back to an update by primary key that touches no row (see [replace]).
     */
    @Upsert
    suspend fun upsert(file: CachedFileEntity)

    /**
     * Stores [file] as THE (media, kind) copy: the existing row is updated (same id), otherwise a
     * row is created. Returns the previous row (its file, if different, is left for the caller to
     * delete after the transaction).
     */
    @Transaction
    suspend fun replace(file: CachedFileEntity): CachedFileEntity? {
        val existing = get(file.mediaId, file.kind)
        upsert(file.copy(id = existing?.id ?: 0))
        return existing
    }

    @Query("UPDATE cached_file SET lastAccessAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long)

    @Query("DELETE FROM cached_file WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /**
     * Deletes row [id] only if it is unchanged since it was read (same file, no access since):
     * a cleanup never removes a row that was reused or replaced between its read and its
     * deletion. Returns 1 if the row was deleted.
     */
    @Query("DELETE FROM cached_file WHERE id = :id AND path = :path AND lastAccessAt = :seenAccessAt")
    suspend fun deleteIfUnchanged(id: Long, path: String, seenAccessAt: Long): Int

    @Query("DELETE FROM cached_file WHERE mediaId IN (:mediaIds) AND kind = :kind")
    suspend fun deleteForMedia(mediaIds: List<Long>, kind: CacheKind)

    @Query("DELETE FROM cached_file WHERE kind = :kind")
    suspend fun deleteByKind(kind: CacheKind)

    @Query("DELETE FROM cached_file")
    suspend fun deleteAll()

    @Query("SELECT EXISTS(SELECT 1 FROM cached_file)")
    suspend fun existsAny(): Boolean

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM cached_file WHERE kind = :kind")
    fun observeTotalSize(kind: CacheKind): Flow<Long>
}

/**
 * Queue of offline copies to download. A single open task (queued, running, paused or failed) per
 * media item: guaranteed by [enqueueIfAbsent] (no partial unique index, which Room cannot
 * declare). A successful task is deleted (the copy is then in `cached_file` and `media_source`).
 * No URL or free text: `error` = name of a DownloadError.
 */
@Dao
interface DownloadTaskDao {

    @Query("SELECT * FROM download_task WHERE id = :id")
    suspend fun getById(id: Long): DownloadTaskEntity?

    /** Open tasks, in queue order. */
    @Query("SELECT * FROM download_task WHERE state IN ($OPEN_STATES) ORDER BY createdAt, id")
    fun observeOpen(): Flow<List<DownloadTaskEntity>>

    @Query("SELECT * FROM download_task WHERE state IN ($OPEN_STATES) ORDER BY createdAt, id")
    suspend fun listOpen(): List<DownloadTaskEntity>

    /** Open task of a media item (null if it has none). */
    @Query("SELECT * FROM download_task WHERE mediaId = :mediaId AND state IN ($OPEN_STATES) ORDER BY id DESC LIMIT 1")
    fun observeOpenForMedia(mediaId: Long): Flow<DownloadTaskEntity?>

    @Query("SELECT * FROM download_task WHERE mediaId IN (:mediaIds) AND state IN ($OPEN_STATES)")
    suspend fun openForMedia(mediaIds: List<Long>): List<DownloadTaskEntity>

    /** Next task to download (the oldest queued one). */
    @Query("SELECT * FROM download_task WHERE state = 'QUEUED' ORDER BY createdAt, id LIMIT 1")
    suspend fun nextQueued(): DownloadTaskEntity?

    /**
     * Next downloadable task: the oldest queued one, excluding tasks waiting for the index to
     * catch up with OneDrive ([RUNNABLE_QUEUED]) and [skip] (already set aside by this pass).
     */
    @Query("SELECT * FROM download_task t WHERE $RUNNABLE_QUEUED AND t.id NOT IN (:skip) ORDER BY t.createdAt, t.id LIMIT 1")
    suspend fun nextRunnable(skip: List<Long>): DownloadTaskEntity?

    /** Like [nextRunnable], among tasks [ids] only ("Download now" on a mobile network). */
    @Query(
        "SELECT * FROM download_task t WHERE $RUNNABLE_QUEUED AND t.id IN (:ids) AND t.id NOT IN (:skip) " +
            "ORDER BY t.createdAt, t.id LIMIT 1",
    )
    suspend fun nextRunnableAmong(ids: List<Long>, skip: List<Long>): DownloadTaskEntity?

    /** True if a downloadable task remains (see [nextRunnable]). */
    @Query("SELECT EXISTS(SELECT 1 FROM download_task t WHERE $RUNNABLE_QUEUED)")
    suspend fun existsRunnable(): Boolean

    @Query("SELECT EXISTS(SELECT 1 FROM download_task t WHERE $RUNNABLE_QUEUED AND t.id IN (:ids))")
    suspend fun existsRunnableAmong(ids: List<Long>): Boolean

    @Query("SELECT * FROM download_task WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<DownloadTaskEntity>

    /** Queued or running tasks (the batch affected by "Download now"). */
    @Query("SELECT * FROM download_task WHERE state IN ('QUEUED', 'RUNNING')")
    suspend fun listPending(): List<DownloadTaskEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM download_task WHERE state = 'RUNNING')")
    suspend fun existsRunning(): Boolean

    @Query("SELECT COALESCE(SUM(MAX(bytesTotal - bytesDownloaded, 0)), 0) FROM download_task WHERE state IN ('QUEUED', 'RUNNING')")
    suspend fun remainingBytes(): Long

    @Query("SELECT EXISTS(SELECT 1 FROM download_task WHERE state = 'QUEUED')")
    suspend fun existsQueued(): Boolean

    @Query("SELECT COUNT(*) FROM download_task WHERE state = 'QUEUED'")
    suspend fun countQueued(): Int

    /** Version and size of the content the running task is downloading. */
    @Query("UPDATE download_task SET cTag = :cTag, bytesTotal = :total WHERE id = :id")
    suspend fun setVersion(id: Long, cTag: String?, total: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM download_task)")
    suspend fun existsAny(): Boolean

    @Insert
    suspend fun insert(task: DownloadTaskEntity): Long

    @Update
    suspend fun update(task: DownloadTaskEntity)

    /**
     * Enqueues one download per item of [tasks] without creating duplicates: an open task of the
     * same media is reused (same id, P0-1) and requeued with the new version; a running task
     * keeps its state (the engine detects the version change when saving). Returns the number
     * of tasks created or requeued.
     */
    @Transaction
    suspend fun enqueueIfAbsent(tasks: List<DownloadTaskEntity>): Int {
        var changed = 0
        for (chunk in tasks.chunked(CHUNK)) {
            val open = openForMedia(chunk.map { it.mediaId }).associateBy { it.mediaId }
            for (task in chunk) {
                val existing = open[task.mediaId]
                when {
                    existing == null -> {
                        insert(task.copy(id = 0))
                        changed++
                    }
                    existing.state == DownloadState.RUNNING -> {
                        if (existing.cTag != task.cTag || existing.cloudItemId != task.cloudItemId) {
                            update(existing.copy(cloudItemId = task.cloudItemId, cTag = task.cTag, bytesTotal = task.bytesTotal, updatedAt = task.updatedAt))
                        }
                    }
                    existing.state == DownloadState.QUEUED &&
                        existing.cTag == task.cTag && existing.cloudItemId == task.cloudItemId -> Unit
                    else -> {
                        // Paused, failed, or queued for a previous version: requeue.
                        val sameVersion = existing.cTag == task.cTag && existing.cloudItemId == task.cloudItemId
                        update(
                            existing.copy(
                                state = DownloadState.QUEUED,
                                cloudItemId = task.cloudItemId,
                                cTag = task.cTag,
                                bytesTotal = task.bytesTotal,
                                bytesDownloaded = if (sameVersion) existing.bytesDownloaded else 0,
                                error = null,
                                attempts = 0,
                                updatedAt = task.updatedAt,
                            ),
                        )
                        changed++
                    }
                }
            }
        }
        return changed
    }

    @Query("UPDATE download_task SET state = 'RUNNING', error = NULL, updatedAt = :now WHERE id = :id AND state = 'QUEUED'")
    suspend fun markRunning(id: Long, now: Long): Int

    /** Progress of a running task; 0 if it no longer exists (freed, cancelled) or is no longer running. */
    @Query("UPDATE download_task SET bytesDownloaded = :bytes, bytesTotal = :total, updatedAt = :now WHERE id = :id AND state = 'RUNNING'")
    suspend fun updateProgress(id: Long, bytes: Long, total: Long, now: Long): Int

    /** Puts a running task back in the queue (work stopped, retry later). */
    @Query(
        "UPDATE download_task SET state = 'QUEUED', error = :error, attempts = :attempts, updatedAt = :now " +
            "WHERE id = :id AND state = 'RUNNING'",
    )
    suspend fun requeue(id: Long, error: String?, attempts: Int, now: Long)

    @Query("UPDATE download_task SET state = 'FAILED', error = :error, attempts = :attempts, updatedAt = :now WHERE id = :id")
    suspend fun markFailed(id: Long, error: String, attempts: Int, now: Long)

    /** All queued tasks fail with [error] (account signed out, out of space). */
    @Query("UPDATE download_task SET state = 'FAILED', error = :error, updatedAt = :now WHERE state = 'QUEUED'")
    suspend fun failAllQueued(error: String, now: Long)

    /** Running tasks of a dead process (or of stopped work): queued again. */
    @Query("UPDATE download_task SET state = 'QUEUED', updatedAt = :now WHERE state = 'RUNNING'")
    suspend fun resetRunningToQueued(now: Long): Int

    /** Stop requested by the user: paused until explicitly resumed. */
    @Query("UPDATE download_task SET state = 'PAUSED', updatedAt = :now WHERE state IN ('QUEUED', 'RUNNING')")
    suspend fun pauseActive(now: Long): Int

    /** Explicit resume: paused or failed tasks queued again. */
    @Query("UPDATE download_task SET state = 'QUEUED', error = NULL, attempts = 0, updatedAt = :now WHERE state IN ('PAUSED', 'FAILED')")
    suspend fun resumeStopped(now: Long): Int

    @Query("DELETE FROM download_task WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM download_task WHERE mediaId IN (:mediaIds)")
    suspend fun deleteForMedia(mediaIds: List<Long>)

    @Query("DELETE FROM download_task WHERE state IN ($OPEN_STATES)")
    suspend fun deleteOpen()

    /** Finished rows (cancelled, or succeeded for a previous version) older than [before]. */
    @Query("DELETE FROM download_task WHERE state IN ('DONE', 'CANCELLED') AND updatedAt < :before")
    suspend fun deleteFinishedBefore(before: Long)

    @Query("DELETE FROM download_task")
    suspend fun deleteAll()
}

private const val CHUNK = 500

/** States of a task still to process or resume (SQL list). */
private const val OPEN_STATES = "'QUEUED', 'RUNNING', 'PAUSED', 'FAILED'"

/**
 * Task `t` queued and downloadable: not a task rejected because the content changed on OneDrive
 * (CONTENT_CHANGED) as long as the index has not changed since (same version and same size of
 * the OneDrive item); it restarts as soon as the sync has caught up with OneDrive.
 * `IS` (not `=`): a NULL error must not exclude the task.
 */
private const val RUNNABLE_QUEUED = "t.state = 'QUEUED' AND NOT (t.error IS 'CONTENT_CHANGED' AND EXISTS(" +
    "SELECT 1 FROM media_source s WHERE s.kind = 'ONEDRIVE' AND s.externalId = t.cloudItemId " +
    "AND s.cTag IS t.cTag AND s.sizeBytes = t.bytesTotal))"
