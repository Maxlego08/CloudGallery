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
     * Dossiers (clés `cloudBucketKey`) qui contiennent des sources OneDrive masquées : les
     * marqueurs de suppression de leurs dossiers et de leurs ancêtres sont gardés.
     */
    @Query(
        """
        SELECT DISTINCT bucketKey FROM media_source
        WHERE kind = 'ONEDRIVE' AND missingSince IS NOT NULL AND bucketKey IS NOT NULL
        """,
    )
    suspend fun hiddenCloudBuckets(): List<String>

    /**
     * Dossier restauré possible sans marqueur : vrai si une source OneDrive masquée appartient à
     * l'un des dossiers [keys] (dossiers nouvellement reçus), ou à un dossier absent de
     * `cloud_folder` (ni présent ni marqué). Depuis la v5, un dossier supprimé garde un marqueur
     * tant que son sous-arbre a des éléments masqués : ce cas ne concerne plus que les dossiers
     * supprimés avant la v5 (leurs lignes ont été retirées), jusqu'à la purge de leurs éléments.
     * Les clés suivent `cloudBucketKey` (« onedrive:<id du dossier> »).
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

    // --- Copies hors ligne (OFFLINE_COPY) ----------------------------------------------------

    /**
     * Une copie hors ligne suit la visibilité de l'élément OneDrive de son média : masquée avec
     * lui (élément supprimé ou dossier supprimé sur OneDrive), de nouveau visible s'il revient.
     * Sans source OneDrive (copie conservée après sa suppression de OneDrive), elle est visible.
     * Appelé dans chaque transaction de synchro, juste avant le recalcul des indicateurs.
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

    /** Nombre de sources de ce type, masquées ou non. */
    @Query("SELECT COUNT(*) FROM media_source WHERE kind = :kind")
    suspend fun countAll(kind: SourceKind): Int

    /**
     * Copies hors ligne d'éléments supprimés de OneDrive : média sans source OneDrive visible
     * (élément masqué en délai de grâce, en attente de décision, ou copie déjà conservée seule).
     * Ce sont les dernières copies connues de ces éléments.
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
     * Comme [countOfflineLastCopies], parmi [mediaIds] : copies hors ligne, visibles ou masquées,
     * sans source OneDrive visible ni fichier local visible. Même règle que « Libérer », qui
     * supprime aussi les copies masquées.
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
     * Médias en attente de décision (décision D1) : copie hors ligne d'un élément masqué sur
     * OneDrive depuis au moins [expiredBefore] (délai de grâce écoulé).
     */
    @Query(
        """
        SELECT DISTINCT o.mediaId FROM media_source o
        WHERE o.kind = 'ONEDRIVE' AND o.missingSince IS NOT NULL AND o.missingSince <= :expiredBefore
          AND EXISTS(SELECT 1 FROM media_source c WHERE c.mediaId = o.mediaId AND c.kind = 'OFFLINE_COPY')
        """,
    )
    suspend fun awaitingDecisionMediaIds(expiredBefore: Long): List<Long>

    /** Comme [awaitingDecisionMediaIds], observable (invite de l'écran Compte et du Menu). */
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
     * Parmi [mediaIds] (liste confirmée par l'utilisateur), ceux qui sont toujours en attente de
     * décision : à relire dans la transaction qui applique la décision (un élément revenu sur
     * OneDrive entre-temps n'est plus concerné).
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

    /** Supprime les sources masquées de ce type pour [mediaIds] (jamais une source visible). */
    @Query("DELETE FROM media_source WHERE kind = :kind AND mediaId IN (:mediaIds) AND missingSince IS NOT NULL")
    suspend fun deleteHiddenForMedia(kind: SourceKind, mediaIds: List<Long>)

    /**
     * Elément OneDrive visible de chaque média de [mediaIds] sans copie hors ligne (candidats à
     * « Conserver hors ligne »), avec ce qu'il faut pour le télécharger.
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
     * Eléments OneDrive visibles de [mediaIds] qui ont déjà une copie hors ligne mais dont la mise
     * à jour (nouvelle version) est en pause ou en échec : « Reprendre le téléchargement ».
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
}

/** Elément OneDrive à conserver hors ligne. */
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
