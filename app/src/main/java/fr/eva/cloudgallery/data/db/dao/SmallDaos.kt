package fr.eva.cloudgallery.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.data.db.entity.CachedFileEntity
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

    /** Efface le lien de reprise (deltaLink) : la prochaine synchro repartira de zéro. */
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

/** Utilisé à partir de la phase 4 (cache cloud). */
@Dao
interface CachedFileDao {
    @Query("SELECT * FROM cached_file WHERE mediaId = :mediaId AND kind = :kind")
    suspend fun get(mediaId: Long, kind: CacheKind): CachedFileEntity?

    @Upsert
    suspend fun upsert(file: CachedFileEntity)

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM cached_file WHERE pinned = :pinned")
    fun observeTotalSize(pinned: Boolean): Flow<Long>
}

/** Utilisé à partir de la phase 4 (téléchargements hors connexion). */
@Dao
interface DownloadTaskDao {
    @Query("SELECT * FROM download_task WHERE mediaId = :mediaId ORDER BY createdAt DESC LIMIT 1")
    fun observeLatest(mediaId: Long): Flow<DownloadTaskEntity?>

    @Upsert
    suspend fun upsert(task: DownloadTaskEntity): Long
}
