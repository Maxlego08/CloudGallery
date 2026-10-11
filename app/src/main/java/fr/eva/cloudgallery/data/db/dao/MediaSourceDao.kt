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

    /** Vrai s'il existe au moins une source de ce type, masquée ou non (contrairement à [count]). */
    @Query("SELECT EXISTS(SELECT 1 FROM media_source WHERE kind = :kind)")
    suspend fun existsAny(kind: SourceKind): Boolean

    /** Médias ayant au moins une source de ce type (masquée ou non). */
    @Query("SELECT DISTINCT mediaId FROM media_source WHERE kind = :kind")
    suspend fun mediaIdsByKind(kind: SourceKind): List<Long>

    @Query("DELETE FROM media_source WHERE kind = :kind")
    suspend fun deleteByKind(kind: SourceKind)

    /** Dossiers distincts d'un type de source (pour créer/mettre à jour les albums-dossiers). */
    @Query(
        """
        SELECT bucketKey, bucketName, relativePath FROM media_source
        WHERE kind = :kind AND bucketKey IS NOT NULL
        GROUP BY bucketKey
        """,
    )
    suspend fun buckets(kind: SourceKind): List<BucketRow>

    /** Comme [buckets], en ignorant les sources masquées (albums OneDrive). */
    @Query(
        """
        SELECT bucketKey, bucketName, relativePath FROM media_source
        WHERE kind = :kind AND bucketKey IS NOT NULL AND missingSince IS NULL
        GROUP BY bucketKey
        """,
    )
    suspend fun visibleBuckets(kind: SourceKind): List<BucketRow>

    /** Nom et chemin lisibles d'un dossier OneDrive, recopiés sur ses sources (sans réécriture inutile). */
    @Query(
        """
        UPDATE media_source SET bucketName = :name, relativePath = :path
        WHERE kind = 'ONEDRIVE' AND bucketKey = :key AND (bucketName IS NOT :name OR relativePath IS NOT :path)
        """,
    )
    suspend fun updateCloudBucketInfo(key: String, name: String, path: String?)

    /** Médias ayant une source OneDrive visible dans ces dossiers (au plus 500 clés par appel). */
    @Query(
        """
        SELECT DISTINCT mediaId FROM media_source
        WHERE kind = 'ONEDRIVE' AND bucketKey IN (:keys) AND missingSince IS NULL
        """,
    )
    suspend fun visibleCloudMediaIdsInBuckets(keys: List<String>): List<Long>

    /** Masque les sources OneDrive de ces dossiers (dossier supprimé sur OneDrive). */
    @Query(
        """
        UPDATE media_source SET missingSince = :now
        WHERE kind = 'ONEDRIVE' AND bucketKey IN (:keys) AND missingSince IS NULL
        """,
    )
    suspend fun markMissingInBuckets(keys: List<String>, now: Long)

    /**
     * Dossier restauré possible : vrai si une source OneDrive masquée appartient à l'un des
     * dossiers [keys] (dossiers nouvellement reçus), ou à un dossier absent de `cloud_folder`.
     * Ce second cas couvre les sous-dossiers d'un dossier supprimé puis restauré : leurs lignes
     * ont été retirées avec lui, plus rien ne les rattache au dossier restauré. Les clés suivent
     * `cloudBucketKey` (« onedrive:<id du dossier> »).
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

    /** Vrai si ce dossier contient au moins une source OneDrive visible. */
    @Query("SELECT EXISTS(SELECT 1 FROM media_source WHERE kind = 'ONEDRIVE' AND bucketKey = :key AND missingSince IS NULL)")
    suspend fun existsVisibleCloudInBucket(key: String): Boolean
}

data class BucketRow(
    val bucketKey: String,
    val bucketName: String?,
    val relativePath: String?,
)
