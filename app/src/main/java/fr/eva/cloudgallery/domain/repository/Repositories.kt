package fr.eva.cloudgallery.domain.repository

import android.content.IntentSender
import android.net.Uri
import androidx.paging.PagingData
import fr.eva.cloudgallery.domain.model.AlbumSort
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.IndexState
import fr.eva.cloudgallery.domain.model.MediaContext
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.ThemeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Accès en lecture à la bibliothèque unifiée (locale + cloud), indépendamment de la source. */
interface MediaRepository {
    /** Médias du contexte, du plus récent au plus ancien (dateTaken DESC, id DESC). */
    fun pagedMedia(context: MediaContext): Flow<PagingData<MediaSummary>>

    /** Identifiants du contexte, dans le même ordre que [pagedMedia]. Utilisé par la visionneuse. */
    fun observeMediaIds(context: MediaContext): Flow<List<Long>>

    fun observeMedia(id: Long): Flow<MediaDetails?>

    suspend fun getMedia(id: Long): MediaDetails?

    suspend fun getSummaries(ids: List<Long>): List<MediaSummary>

    /** Identifiants du contexte dont la date de prise de vue est dans [startMs, endMs[, dans l'ordre de [pagedMedia]. */
    suspend fun idsInPeriod(context: MediaContext, startMs: Long, endMs: Long): List<Long>

    /**
     * Identifiants du contexte situés entre deux médias de la liste (bornes incluses, dans
     * n'importe quel ordre), y compris ceux qui ne sont pas encore chargés par la pagination.
     */
    suspend fun idsBetween(context: MediaContext, a: MediaSummary, b: MediaSummary): List<Long>

    /** Nombre de médias du contexte. */
    fun observeCount(context: MediaContext): Flow<Int>

    /** Retrouve (ou indexe immédiatement) le média correspondant à une URI MediaStore. */
    suspend fun findByContentUri(uri: Uri): Long?
}

interface FavoritesRepository {
    suspend fun setFavorite(mediaIds: Collection<Long>, favorite: Boolean)
    suspend fun toggle(mediaId: Long): Boolean
}

interface AlbumRepository {
    fun observeAlbums(sort: AlbumSort): Flow<List<AlbumSummary>>
    fun observeAlbum(albumId: Long): Flow<AlbumSummary?>
    suspend fun createVirtualAlbum(name: String): Long
    suspend fun renameAlbum(albumId: Long, name: String)

    /** Supprime uniquement un album virtuel (les médias référencés ne sont pas touchés). */
    suspend fun deleteVirtualAlbum(albumId: Long)
    suspend fun addToAlbum(albumId: Long, mediaIds: Collection<Long>)
    suspend fun removeFromAlbum(albumId: Long, mediaIds: Collection<Long>)
    suspend fun setPinned(albumId: Long, pinned: Boolean)
}

/** Indexation incrémentale de MediaStore vers la base Room. */
interface LocalMediaIndexer {
    val state: StateFlow<IndexState>

    /** Synchronise MediaStore -> Room. [full] force une relecture complète. */
    suspend fun sync(full: Boolean = false)

    /** Démarre l'observation des changements MediaStore (ContentObserver). Idempotent. */
    fun startObserving()
}

/**
 * Opérations d'écriture sur les fichiers locaux. Celles qui exigent une confirmation
 * système renvoient un [IntentSender] à lancer par l'UI (StartIntentSenderForResult).
 */
interface LocalMediaWriter {
    /** Corbeille Android (récupérable) ou suppression définitive selon [toTrash]. */
    fun createDeleteRequest(uris: List<Uri>, toTrash: Boolean): IntentSender
    fun createWriteRequest(uris: List<Uri>): IntentSender

    /** Vrai si [mimeType] permet une rotation sans perte (orientation EXIF modifiable). */
    fun canRotate(mimeType: String?): Boolean

    /** Pivote une image de [degrees] (multiple de 90). Nécessite l'accès en écriture accordé. */
    suspend fun rotateImage(uri: Uri, degrees: Int): Result<Unit>

    /** Enregistre une copie recadrée (rectangle normalisé 0..1) et renvoie l'URI créée. */
    suspend fun saveCroppedCopy(
        uri: Uri,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        rotationDegrees: Int,
    ): Result<Uri>

    /** Copie des fichiers locaux vers un dossier relatif (ex. "Pictures/Vacances/"). */
    suspend fun copyToFolder(uris: List<Uri>, relativePath: String): Result<Int>
}

interface SettingsRepository {
    val settings: Flow<AppSettings>
    suspend fun setThemeMode(mode: ThemeMode)
    suspend fun setPhotosZoomLevel(level: Int)
    suspend fun setAlbumZoomLevel(level: Int)
    suspend fun setAlbumSort(sort: AlbumSort)
    suspend fun setShowVideoDuration(show: Boolean)
    suspend fun setAutoPlayVideos(enabled: Boolean)
    suspend fun setConfirmBeforeDelete(enabled: Boolean)
    suspend fun setUseTrash(enabled: Boolean)
}

interface PlaybackPositionRepository {
    suspend fun get(mediaId: Long): Long
    suspend fun save(mediaId: Long, positionMs: Long, durationMs: Long)
    suspend fun clear(mediaId: Long)
}
