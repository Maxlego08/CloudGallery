package fr.eva.cloudgallery.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import fr.eva.cloudgallery.data.db.entity.MediaSourceEntity
import fr.eva.cloudgallery.domain.model.SourceKind
import kotlinx.coroutines.flow.Flow

/** Paire légère (externalId, generation) utilisée pour comparer MediaStore et la base. */
data class SourceStamp(
    val id: Long,
    val mediaId: Long,
    val externalId: String,
    val generation: Long,
    val volumeName: String?,
    val missingSince: Long?,
)

/** Empreinte d'une source à ré-identifier après un changement de version MediaStore. */
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

    /** Masque des sources (sans écraser un horodatage déjà posé). */
    @Query("UPDATE media_source SET missingSince = :now WHERE id IN (:ids) AND missingSince IS NULL")
    suspend fun markMissing(ids: List<Long>, now: Long)

    /**
     * Version MediaStore changée : les _ID ne sont plus fiables. Les identifiants externes LOCAL
     * sont préfixés de « stale: » puis ré-associés par empreinte lors de la relecture complète.
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

    /** Dossiers distincts d'un type de source (pour créer/mettre à jour les albums-dossiers). */
    @Query(
        """
        SELECT bucketKey, bucketName, relativePath FROM media_source
        WHERE kind = :kind AND bucketKey IS NOT NULL
        GROUP BY bucketKey
        """,
    )
    suspend fun buckets(kind: SourceKind): List<BucketRow>
}

data class BucketRow(
    val bucketKey: String,
    val bucketName: String?,
    val relativePath: String?,
)
