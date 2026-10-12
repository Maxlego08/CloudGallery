package fr.eva.cloudgallery.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import fr.eva.cloudgallery.data.db.entity.AlbumEntity
import fr.eva.cloudgallery.data.db.entity.AlbumMediaEntity
import fr.eva.cloudgallery.data.db.entity.AlbumRow
import fr.eva.cloudgallery.domain.model.AlbumType
import kotlinx.coroutines.flow.Flow

/*
 * members : couples (albumId, mediaId) des albums-dossiers (sources visibles, voir
 *           FOLDER_MEMBER_SOURCE) et des albums virtuels.
 * stats   : nombre de médias, date la plus récente et média le plus récent (colonne « nue »
 *           associée à MAX(), comportement documenté de SQLite) pour la couverture.
 */
private const val ALBUM_ROWS = """
    WITH members AS (
        SELECT a.id AS albumId, s.mediaId AS mediaId
        FROM album a JOIN media_source s ON s.bucketKey = a.sourceKey
        WHERE a.sourceKey IS NOT NULL AND $FOLDER_MEMBER_SOURCE
        UNION
        SELECT am.albumId AS albumId, am.mediaId AS mediaId FROM album_media am
    ),
    stats AS (
        SELECT mb.albumId AS albumId, COUNT(*) AS cnt, MAX(m.dateTaken) AS latest, m.id AS latestId
        FROM members mb JOIN media m ON m.id = mb.mediaId
        WHERE (m.hasLocal = 1 OR m.hasCloud = 1 OR m.hasOffline = 1)
        GROUP BY mb.albumId
    )
    SELECT a.id, a.name, a.customName, a.type, a.sourceKey, a.relativePath, a.isPinned, a.sortIndex,
        COALESCE(st.cnt, 0) AS mediaCount,
        c.id AS coverMediaId, c.primaryUri AS coverUri, c.mediaType AS coverType,
        c.dateModified AS coverDateModified, c.cloudItemId AS coverCloudItemId, c.cloudCTag AS coverCloudCTag,
        st.latest AS latestDate
    FROM album a
    LEFT JOIN stats st ON st.albumId = a.id
    LEFT JOIN media c ON c.id = COALESCE(a.coverMediaId, st.latestId)
"""

@Dao
interface AlbumDao {

    @Query("$ALBUM_ROWS WHERE a.isHidden = 0 AND (a.type = 'VIRTUAL' OR COALESCE(st.cnt, 0) > 0)")
    fun observeAlbumRows(): Flow<List<AlbumRow>>

    @Query("$ALBUM_ROWS WHERE a.id = :albumId")
    fun observeAlbumRow(albumId: Long): Flow<AlbumRow?>

    @Query("SELECT * FROM album WHERE id = :id")
    suspend fun getById(id: Long): AlbumEntity?

    @Query("SELECT * FROM album WHERE sourceKey = :sourceKey")
    suspend fun getBySourceKey(sourceKey: String): AlbumEntity?

    @Query("SELECT * FROM album WHERE type = :type")
    suspend fun getByType(type: AlbumType): List<AlbumEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(album: AlbumEntity): Long

    @Query("UPDATE album SET name = :name, relativePath = :relativePath, updatedAt = :now WHERE id = :id")
    suspend fun updateFolderInfo(id: Long, name: String, relativePath: String?, now: Long)

    @Query("UPDATE album SET customName = :name, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String?, now: Long)

    @Query("UPDATE album SET name = :name, updatedAt = :now WHERE id = :id AND type = 'VIRTUAL'")
    suspend fun renameVirtual(id: Long, name: String, now: Long)

    @Query("UPDATE album SET isPinned = :pinned, updatedAt = :now WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean, now: Long)

    @Query("UPDATE album SET coverMediaId = :mediaId, updatedAt = :now WHERE id = :id")
    suspend fun setCover(id: Long, mediaId: Long?, now: Long)

    @Query("DELETE FROM album WHERE id = :id AND type = 'VIRTUAL'")
    suspend fun deleteVirtual(id: Long)

    /**
     * Supprime des albums-dossiers sans aucune personnalisation de l'utilisateur (nom, épingle,
     * masquage, couverture) : un dossier personnalisé est conservé même vide.
     */
    @Query(
        """
        DELETE FROM album WHERE id IN (:ids) AND type = :type
            AND customName IS NULL AND isPinned = 0 AND isHidden = 0 AND coverMediaId IS NULL
        """,
    )
    suspend fun deleteUncustomizedFolders(ids: List<Long>, type: AlbumType)

    /** Supprime tous les albums d'un type (ex. albums OneDrive lors de la déconnexion). */
    @Query("DELETE FROM album WHERE type = :type")
    suspend fun deleteByType(type: AlbumType)

    @Query("SELECT EXISTS(SELECT 1 FROM album WHERE type = :type)")
    suspend fun existsByType(type: AlbumType): Boolean

    /** coverMediaId n'a pas de clé étrangère : on retire les couvertures qui pointent vers un média disparu. */
    @Query("UPDATE album SET coverMediaId = NULL WHERE coverMediaId IS NOT NULL AND coverMediaId NOT IN (SELECT id FROM media)")
    suspend fun clearDanglingCovers()

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertMembers(members: List<AlbumMediaEntity>)

    @Query("DELETE FROM album_media WHERE albumId = :albumId AND mediaId IN (:mediaIds)")
    suspend fun removeMembers(albumId: Long, mediaIds: List<Long>)
}
