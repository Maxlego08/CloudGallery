package fr.eva.cloudgallery.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import fr.eva.cloudgallery.data.db.entity.CloudFolderEntity

/** OneDrive folders (delta sync). */
@Dao
interface CloudFolderDao {

    @Upsert
    suspend fun upsertAll(folders: List<CloudFolderEntity>)

    @Query("SELECT * FROM cloud_folder")
    suspend fun getAll(): List<CloudFolderEntity>

    /** Known ids among [ids] (at most 500 per call). */
    @Query("SELECT itemId FROM cloud_folder WHERE itemId IN (:ids)")
    suspend fun knownIds(ids: List<String>): List<String>

    /** Folders marked as deleted among [ids] (at most 500 per call). */
    @Query("SELECT itemId FROM cloud_folder WHERE itemId IN (:ids) AND deletedAt IS NOT NULL")
    suspend fun markedIds(ids: List<String>): List<String>

    /** Marks these folders as deleted (the date of the first deletion is kept). */
    @Query("UPDATE cloud_folder SET deletedAt = :now WHERE itemId IN (:ids) AND deletedAt IS NULL")
    suspend fun markDeleted(ids: List<String>, now: Long)

    @Query("DELETE FROM cloud_folder WHERE itemId IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM cloud_folder")
    suspend fun deleteAll()

    @Query("SELECT EXISTS(SELECT 1 FROM cloud_folder)")
    suspend fun existsAny(): Boolean
}
