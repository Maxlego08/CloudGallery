package fr.eva.cloudgallery.ui.viewer

import android.content.IntentSender
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.AlbumSort
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.repository.AlbumRepository
import fr.eva.cloudgallery.domain.repository.FavoritesRepository
import fr.eva.cloudgallery.domain.repository.LocalMediaIndexer
import fr.eva.cloudgallery.domain.repository.LocalMediaWriter
import fr.eva.cloudgallery.domain.repository.MediaRepository
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import fr.eva.cloudgallery.ui.navigation.ViewerRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Evénements ponctuels envoyés à l'UI. */
sealed interface ViewerEvent {
    /** Demande de consentement système (suppression / écriture) à lancer avec StartIntentSenderForResult. */
    data class LaunchIntentSender(val sender: IntentSender) : ViewerEvent
    data class Toast(@StringRes val message: Int, val arg: String? = null) : ViewerEvent
    /** Le recadrage a été enregistré : fermer l'éditeur. */
    data object CropSaved : ViewerEvent
}

/** Etat EXIF du média dont le panneau Détails est ouvert. */
data class ExifState(val mediaId: Long, val loading: Boolean, val info: ExifInfo?)

/** Action en attente du résultat d'une demande de consentement système. */
private sealed interface PendingAction {
    data object Delete : PendingAction
    data class Rotate(val uri: Uri, val degrees: Int) : PendingAction
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ViewerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val mediaRepository: MediaRepository,
    private val favoritesRepository: FavoritesRepository,
    private val albumRepository: AlbumRepository,
    private val writer: LocalMediaWriter,
    private val indexer: LocalMediaIndexer,
    private val exifReader: ExifReader,
    settingsRepository: SettingsRepository,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val route: ViewerRoute = savedStateHandle.toRoute<ViewerRoute>()

    /** Identifiants du contexte, null tant qu'ils ne sont pas chargés. */
    val ids: StateFlow<List<Long>?> = mediaRepository.observeMediaIds(route.context)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _currentId = MutableStateFlow(savedStateHandle.get<Long>(KEY_CURRENT) ?: route.mediaId)

    /** Média affiché (mis à jour par le pager). */
    val currentId: StateFlow<Long> = _currentId.asStateFlow()

    val currentMedia: StateFlow<MediaDetails?> = _currentId
        .flatMapLatest { mediaRepository.observeMedia(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Dossiers locaux (cibles de « Copier vers un dossier »). */
    val localFolders: StateFlow<List<AlbumSummary>> = albumRepository.observeAlbums(AlbumSort.NAME)
        .map { albums -> albums.filter { it.type == AlbumType.LOCAL_FOLDER && !it.relativePath.isNullOrBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _summaries = MutableStateFlow<Map<Long, MediaSummary>>(emptyMap())

    /** Résumés chargés à la demande pour la bande de miniatures. */
    val summaries: StateFlow<Map<Long, MediaSummary>> = _summaries.asStateFlow()
    private val loadingSummaries = mutableSetOf<Long>()

    private val _exif = MutableStateFlow<ExifState?>(null)
    val exif: StateFlow<ExifState?> = _exif.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** Vrai pendant une écriture (rotation, recadrage, copie). */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = Channel<ViewerEvent>(Channel.BUFFERED)
    val events: Flow<ViewerEvent> = _events.receiveAsFlow()

    private var pendingAction: PendingAction? = null

    init {
        // Tient la bande de miniatures à jour après une modification du média courant (rotation, favori).
        viewModelScope.launch {
            currentMedia.collect { media ->
                if (media == null) return@collect
                val cached = _summaries.value[media.id] ?: return@collect
                if (cached.dateModified != media.dateModified || cached.isFavorite != media.isFavorite || cached.uri != media.uri) {
                    _summaries.update { it + (media.id to cached.copy(dateModified = media.dateModified, isFavorite = media.isFavorite, uri = media.uri)) }
                }
            }
        }
    }

    fun observeMedia(id: Long): Flow<MediaDetails?> = mediaRepository.observeMedia(id)

    fun onPageSelected(id: Long) {
        if (_currentId.value == id) return
        _currentId.value = id
        savedStateHandle[KEY_CURRENT] = id
    }

    /** Charge (par lots) les résumés manquants de la fenêtre visible de la bande de miniatures. */
    fun requestSummaries(window: List<Long>) {
        val missing = synchronized(loadingSummaries) {
            window.filter { it !in _summaries.value && it !in loadingSummaries }.also { loadingSummaries.addAll(it) }
        }
        if (missing.isEmpty()) return
        viewModelScope.launch {
            try {
                val loaded = withContext(io) { mediaRepository.getSummaries(missing) }
                _summaries.update { map -> map + loaded.associateBy { it.id } }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Les cellules restent sur le fond de chargement.
            } finally {
                synchronized(loadingSummaries) { loadingSummaries.removeAll(missing.toSet()) }
            }
        }
    }

    fun toggleFavorite() {
        val id = _currentId.value
        viewModelScope.launch {
            runCatchingIo { favoritesRepository.toggle(id) }
                .onFailure { send(ViewerEvent.Toast(R.string.common_error_generic)) }
        }
    }

    fun addToAlbum(albumId: Long) {
        val id = _currentId.value
        viewModelScope.launch {
            runCatchingIo { albumRepository.addToAlbum(albumId, listOf(id)) }
                .onSuccess { send(ViewerEvent.Toast(R.string.viewer_added_to_album)) }
                .onFailure { send(ViewerEvent.Toast(R.string.common_error_generic)) }
        }
    }

    // --- Suppression -------------------------------------------------------------------------

    fun requestDelete() {
        val uri = currentLocalUri() ?: return send(ViewerEvent.Toast(R.string.common_cloud_only_not_supported))
        viewModelScope.launch {
            val useTrash = settings.value.useTrash
            runCatchingIo { writer.createDeleteRequest(listOf(uri), useTrash) }
                .onSuccess { sender ->
                    pendingAction = PendingAction.Delete
                    send(ViewerEvent.LaunchIntentSender(sender))
                }
                .onFailure { send(ViewerEvent.Toast(R.string.common_error_generic)) }
        }
    }

    // --- Rotation ----------------------------------------------------------------------------

    fun requestRotate(degrees: Int) {
        val media = currentMedia.value ?: return
        val uri = media.uri
        if (uri == null || !media.hasLocal) return send(ViewerEvent.Toast(R.string.common_cloud_only_not_supported))
        if (media.type != MediaType.IMAGE || _busy.value) return
        // Vérifier le format AVANT de demander l'autorisation d'écriture à l'utilisateur.
        if (!writer.canRotate(media.mimeType)) return send(ViewerEvent.Toast(R.string.data_error_rotate_unsupported))
        viewModelScope.launch {
            runCatchingIo { writer.createWriteRequest(listOf(uri)) }
                .onSuccess { sender ->
                    pendingAction = PendingAction.Rotate(uri, degrees)
                    send(ViewerEvent.LaunchIntentSender(sender))
                }
                .onFailure { send(ViewerEvent.Toast(R.string.common_error_generic)) }
        }
    }

    /** Rotation sans perte possible pour ce média (format dont l'orientation EXIF est modifiable). */
    fun canRotate(media: MediaDetails?): Boolean =
        media != null && media.type == MediaType.IMAGE && writer.canRotate(media.mimeType)

    /** Résultat de la boîte de consentement système. */
    fun onIntentSenderResult(granted: Boolean) {
        val action = pendingAction ?: return
        pendingAction = null
        if (!granted) return
        when (action) {
            PendingAction.Delete -> viewModelScope.launch { syncQuietly() }
            is PendingAction.Rotate -> viewModelScope.launch {
                _busy.value = true
                try {
                    val result = runCatchingIo { writer.rotateImage(action.uri, action.degrees).getOrThrow() }
                    if (result.isSuccess) {
                        clearExif()
                        syncQuietly()
                    } else {
                        send(ViewerEvent.Toast(R.string.viewer_rotate_failed))
                    }
                } finally {
                    _busy.value = false
                }
            }
        }
    }

    // --- Recadrage / copie ------------------------------------------------------------------

    fun saveCrop(rect: CropRect, rotationDegrees: Int) {
        val uri = currentLocalUri() ?: return send(ViewerEvent.Toast(R.string.common_cloud_only_not_supported))
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = runCatchingIo {
                    writer.saveCroppedCopy(uri, rect.left, rect.top, rect.right, rect.bottom, rotationDegrees).getOrThrow()
                }
                if (result.isSuccess) {
                    syncQuietly()
                    send(ViewerEvent.Toast(R.string.viewer_copy_saved))
                    send(ViewerEvent.CropSaved)
                } else {
                    send(ViewerEvent.Toast(R.string.viewer_crop_failed))
                }
            } finally {
                _busy.value = false
            }
        }
    }

    fun copyToFolder(folder: AlbumSummary) {
        val uri = currentLocalUri() ?: return send(ViewerEvent.Toast(R.string.common_cloud_only_not_supported))
        val path = folder.relativePath ?: return
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                val result = runCatchingIo { writer.copyToFolder(listOf(uri), path).getOrThrow() }
                if (result.isSuccess && (result.getOrNull() ?: 0) > 0) {
                    syncQuietly()
                    send(ViewerEvent.Toast(R.string.viewer_copy_done, folder.name))
                } else {
                    send(ViewerEvent.Toast(R.string.viewer_copy_failed))
                }
            } finally {
                _busy.value = false
            }
        }
    }

    // --- EXIF --------------------------------------------------------------------------------

    fun loadExif(media: MediaDetails) {
        val current = _exif.value
        if (current != null && current.mediaId == media.id && (current.loading || current.info != null)) return
        val uri = media.uri
        if (uri == null || !media.hasLocal || media.type != MediaType.IMAGE) {
            _exif.value = ExifState(media.id, loading = false, info = null)
            return
        }
        _exif.value = ExifState(media.id, loading = true, info = null)
        viewModelScope.launch {
            val info = try {
                exifReader.read(uri)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            _exif.update { if (it?.mediaId == media.id) ExifState(media.id, false, info) else it }
        }
    }

    /** Oublie l'EXIF en cache (ex. après rotation, le panneau relira le fichier). */
    fun clearExif() {
        _exif.value = null
    }

    // --- Utilitaires -------------------------------------------------------------------------

    private fun currentLocalUri(): Uri? {
        val media = currentMedia.value ?: return null
        return media.uri?.takeIf { media.hasLocal }
    }

    private suspend fun syncQuietly() {
        try {
            withContext(io) { indexer.sync() }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // L'observateur MediaStore rattrapera la modification.
        }
    }

    private suspend fun <T> runCatchingIo(block: suspend () -> T): Result<T> = try {
        Result.success(withContext(io) { block() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun send(event: ViewerEvent) {
        _events.trySend(event)
    }

    private companion object {
        const val KEY_CURRENT = "viewer_current_id"
    }
}
