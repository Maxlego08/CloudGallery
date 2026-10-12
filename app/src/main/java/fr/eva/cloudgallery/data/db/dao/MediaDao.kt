package fr.eva.cloudgallery.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import fr.eva.cloudgallery.data.db.entity.MediaEntity
import fr.eva.cloudgallery.data.db.entity.MediaRow
import kotlinx.coroutines.flow.Flow

internal const val ROW_COLUMNS = """
    m.id, m.mediaType, m.mimeType, m.dateTaken, m.dateModified, m.durationMs, m.width, m.height,
    m.orientation, m.primaryUri, m.hasLocal, m.hasCloud, m.hasOffline,
    EXISTS(SELECT 1 FROM favorite f WHERE f.mediaId = m.id) AS isFavorite,
    m.cloudItemId, m.cloudCTag
"""

/**
 * Source qui fait d'un média un membre du dossier de sa `bucketKey` : source visible, ou source
 * masquée d'un média qui n'a aucune source visible rattachée à un dossier (média à une seule
 * source, ou copie hors ligne d'un élément masqué : il reste dans son dossier tant qu'il est
 * affiché). Un média fusionné (fichier local + élément OneDrive) n'apparaît ainsi que dans les
 * dossiers de ses sources visibles. Même règle dans ALBUM_ROWS (AlbumDao).
 */
internal const val FOLDER_MEMBER_SOURCE = """
    (s.missingSince IS NULL OR NOT EXISTS(
        SELECT 1 FROM media_source v WHERE v.mediaId = s.mediaId AND v.missingSince IS NULL AND v.bucketKey IS NOT NULL
    ))
"""

/** Membres d'un album : dossier (via media_source.bucketKey) ou album virtuel (album_media). */
private const val ALBUM_MEMBERS = """
    SELECT s.mediaId FROM media_source s JOIN album a ON a.sourceKey = s.bucketKey
    WHERE a.id = :albumId AND $FOLDER_MEMBER_SOURCE
    UNION
    SELECT am.mediaId FROM album_media am WHERE am.albumId = :albumId
"""

internal const val ORDER = "ORDER BY m.dateTaken DESC, m.id DESC"

/**
 * Médias affichables : au moins une source présente. Un média dont toutes les sources sont
 * masquées (missingSince, voir MediaSourceEntity) reste en base avec favoris et albums.
 */
internal const val VISIBLE = "(m.hasLocal = 1 OR m.hasCloud = 1 OR m.hasOffline = 1)"

/**
 * Photos et Vidéos (réglage « Afficher les éléments OneDrive dans Photos ») : sans
 * `:includeCloudOnly`, seuls les médias présents sur l'appareil ou disponibles hors connexion y
 * figurent ; les éléments uniquement sur OneDrive restent dans leurs albums OneDrive, les
 * Favoris et les albums de l'utilisateur. Sous-ensemble de [VISIBLE].
 */
private const val LIBRARY = "(m.hasLocal = 1 OR m.hasOffline = 1 OR (:includeCloudOnly = 1 AND m.hasCloud = 1))"

/** Eléments situés entre deux éléments de la liste triée (bornes incluses). */
internal const val BETWEEN = """
    (m.dateTaken < :newerDate OR (m.dateTaken = :newerDate AND m.id <= :newerId))
    AND (m.dateTaken > :olderDate OR (m.dateTaken = :olderDate AND m.id >= :olderId))
"""

internal const val IN_PERIOD = "m.dateTaken >= :startMs AND m.dateTaken < :endMs"

@Dao
interface MediaDao {

    // --- Grilles paginées ---------------------------------------------------------------

    @Query("SELECT $ROW_COLUMNS FROM media m WHERE $LIBRARY $ORDER")
    fun pagingTimeline(includeCloudOnly: Boolean): PagingSource<Int, MediaRow>

    @Query("SELECT $ROW_COLUMNS FROM media m WHERE $LIBRARY AND m.mediaType = 'VIDEO' $ORDER")
    fun pagingVideos(includeCloudOnly: Boolean): PagingSource<Int, MediaRow>

    @Query("SELECT $ROW_COLUMNS FROM media m WHERE $VISIBLE AND m.id IN (SELECT mediaId FROM favorite) $ORDER")
    fun pagingFavorites(): PagingSource<Int, MediaRow>

    @Query("SELECT $ROW_COLUMNS FROM media m WHERE $VISIBLE AND m.id IN ($ALBUM_MEMBERS) $ORDER")
    fun pagingAlbum(albumId: Long): PagingSource<Int, MediaRow>

    // --- Identifiants ordonnés (visionneuse) --------------------------------------------

    @Query("SELECT m.id FROM media m WHERE $LIBRARY $ORDER")
    fun observeTimelineIds(includeCloudOnly: Boolean): Flow<List<Long>>

    @Query("SELECT m.id FROM media m WHERE $LIBRARY AND m.mediaType = 'VIDEO' $ORDER")
    fun observeVideoIds(includeCloudOnly: Boolean): Flow<List<Long>>

    @Query("SELECT m.id FROM media m WHERE $VISIBLE AND m.id IN (SELECT mediaId FROM favorite) $ORDER")
    fun observeFavoriteIds(): Flow<List<Long>>

    @Query("SELECT m.id FROM media m WHERE $VISIBLE AND m.id IN ($ALBUM_MEMBERS) $ORDER")
    fun observeAlbumIds(albumId: Long): Flow<List<Long>>

    // --- Dates ordonnées (défilement rapide), même ordre que les grilles ------------------

    @Query("SELECT m.dateTaken FROM media m WHERE $LIBRARY $ORDER")
    fun observeTimelineDates(includeCloudOnly: Boolean): Flow<List<Long>>

    @Query("SELECT m.dateTaken FROM media m WHERE $LIBRARY AND m.mediaType = 'VIDEO' $ORDER")
    fun observeVideoDates(includeCloudOnly: Boolean): Flow<List<Long>>

    @Query("SELECT m.dateTaken FROM media m WHERE $VISIBLE AND m.id IN (SELECT mediaId FROM favorite) $ORDER")
    fun observeFavoriteDates(): Flow<List<Long>>

    @Query("SELECT m.dateTaken FROM media m WHERE $VISIBLE AND m.id IN ($ALBUM_MEMBERS) $ORDER")
    fun observeAlbumDates(albumId: Long): Flow<List<Long>>

    // --- Comptages ----------------------------------------------------------------------

    @Query("SELECT COUNT(*) FROM media m WHERE $LIBRARY")
    fun observeTimelineCount(includeCloudOnly: Boolean): Flow<Int>

    @Query("SELECT COUNT(*) FROM media m WHERE $LIBRARY AND m.mediaType = 'VIDEO'")
    fun observeVideoCount(includeCloudOnly: Boolean): Flow<Int>

    @Query("SELECT COUNT(*) FROM media m WHERE $VISIBLE AND m.id IN (SELECT mediaId FROM favorite)")
    fun observeFavoriteCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM media m WHERE $VISIBLE AND m.id IN ($ALBUM_MEMBERS)")
    fun observeAlbumCount(albumId: Long): Flow<Int>

    // --- Plages d'identifiants (sélection par jour / par glisser), même ordre que les grilles ---

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

    // --- Accès unitaire -----------------------------------------------------------------

    @Query("SELECT * FROM media WHERE id = :id")
    suspend fun getById(id: Long): MediaEntity?

    @Query("SELECT * FROM media WHERE id = :id")
    fun observeById(id: Long): Flow<MediaEntity?>

    @Query("SELECT $ROW_COLUMNS FROM media m WHERE m.id IN (:ids)")
    suspend fun getRows(ids: List<Long>): List<MediaRow>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite WHERE mediaId = :id)")
    fun observeIsFavorite(id: Long): Flow<Boolean>

    /** Médias parmi [ids] qui ont un fichier local utilisable (actions de partage, suppression, copie). */
    @Query("SELECT COUNT(*) FROM media WHERE id IN (:ids) AND hasLocal = 1 AND primaryUri IS NOT NULL")
    suspend fun countWithLocalFile(ids: List<Long>): Int

    /**
     * Médias partageables parmi [ids] : fichier local, copie hors ligne ou élément OneDrive
     * visible (téléchargé au moment du partage). Lecture seule : rien d'autre n'est autorisé ainsi.
     */
    @Query(
        "SELECT COUNT(*) FROM media WHERE id IN (:ids) AND " +
            "((hasLocal = 1 AND primaryUri IS NOT NULL) OR hasOffline = 1 OR hasCloud = 1)",
    )
    suspend fun countShareable(ids: List<Long>): Int

    /**
     * Eléments OneDrive visibles sans copie hors ligne parmi [ids] (« Conserver hors ligne »),
     * hors éléments déjà en attente ou en cours de téléchargement (en pause ou en échec : comptés,
     * « Conserver hors ligne » les relance).
     */
    @Query(
        "SELECT COUNT(*) FROM media m WHERE m.id IN (:ids) AND m.hasCloud = 1 AND m.hasOffline = 0 " +
            "AND NOT EXISTS(SELECT 1 FROM download_task t WHERE t.mediaId = m.id AND t.state IN ('QUEUED', 'RUNNING'))",
    )
    suspend fun countKeepableOffline(ids: List<Long>): Int

    /** Médias parmi [ids] qui ont une copie hors ligne visible (« Libérer l'espace »). */
    @Query("SELECT COUNT(*) FROM media WHERE id IN (:ids) AND hasOffline = 1")
    suspend fun countWithOfflineCopy(ids: List<Long>): Int

    /** Ligne de grille observable pour un seul média (contexte Single). */
    @Query("SELECT $ROW_COLUMNS FROM media m WHERE m.id = :id")
    fun observeRow(id: Long): Flow<MediaRow?>

    /** Entités complètes par lot (indexation : conserver les champs non issus de MediaStore). */
    @Query("SELECT * FROM media WHERE id IN (:ids)")
    suspend fun getByIds(ids: List<Long>): List<MediaEntity>

    // --- Ecriture (indexation) ----------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(media: MediaEntity): Long

    @Update
    suspend fun update(media: MediaEntity)

    @Query("DELETE FROM media WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<Long>)

    /** Médias logiques qui n'ont plus aucune source. */
    @Query("DELETE FROM media WHERE id NOT IN (SELECT DISTINCT mediaId FROM media_source)")
    suspend fun deleteOrphans(): Int

    /**
     * Recalcule les colonnes dénormalisées à partir des sources visibles : une source masquée
     * (missingSince) ne compte plus, et `primaryUri` n'est renseignée que par un fichier local
     * présent (uri non nulle = fichier local utilisable).
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
}
