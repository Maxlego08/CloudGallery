package fr.eva.cloudgallery.ui.viewer

import android.content.IntentSender
import android.net.Uri
import android.util.Log
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.data.cloud.CloudAccessGuard
import fr.eva.cloudgallery.data.cloud.content.CloudFileTarget
import fr.eva.cloudgallery.data.cloud.content.CloudOriginalStore
import fr.eva.cloudgallery.data.cloud.content.ShareUriProvider
import fr.eva.cloudgallery.data.cloud.content.cloudFileTargetOf
import fr.eva.cloudgallery.data.cloud.content.downloadErrorOf
import fr.eva.cloudgallery.data.cloud.thumb.OriginalDecision
import fr.eva.cloudgallery.data.cloud.thumb.originalDecision
import fr.eva.cloudgallery.data.net.NetworkStatus
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.AlbumSort
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.DownloadError
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.OfflineItemState
import fr.eva.cloudgallery.domain.repository.AlbumRepository
import fr.eva.cloudgallery.domain.repository.FavoritesRepository
import fr.eva.cloudgallery.domain.repository.LocalMediaIndexer
import fr.eva.cloudgallery.domain.repository.LocalMediaWriter
import fr.eva.cloudgallery.domain.repository.MediaRepository
import fr.eva.cloudgallery.domain.repository.OfflineRepository
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import fr.eva.cloudgallery.ui.navigation.ViewerRoute
import fr.eva.cloudgallery.ui.offline.OfflineFeedback
import fr.eva.cloudgallery.ui.offline.OfflinePrompt
import fr.eva.cloudgallery.ui.offline.keepFeedback
import fr.eva.cloudgallery.ui.offline.ReleaseStep
import fr.eva.cloudgallery.ui.offline.releaseStep
import fr.eva.cloudgallery.ui.video.PlaybackSourceResolver
import fr.eva.cloudgallery.ui.util.ShareCandidate
import fr.eva.cloudgallery.ui.util.downloadErrorMessage
import fr.eva.cloudgallery.ui.util.planShare
import fr.eva.cloudgallery.ui.util.progressPercent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
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

    /** Message court au pluriel ([count] sert de quantité et d'argument). */
    data class PluralToast(@PluralsRes val message: Int, val count: Int) : ViewerEvent
    /** Le recadrage a été enregistré : fermer l'éditeur. */
    data object CropSaved : ViewerEvent

    /** Ouvrir la feuille de partage (URI `content://` en lecture seule). */
    data class Share(val uris: List<Uri>, val mimeTypes: List<String?>) : ViewerEvent

    /** Ouvrir le média dans une autre appli (URI `content://` en lecture seule). */
    data class OpenWith(val uri: Uri, val mimeType: String?) : ViewerEvent
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
    private val settingsRepository: SettingsRepository,
    private val originals: CloudOriginalStore,
    private val offline: OfflineRepository,
    private val shareUris: ShareUriProvider,
    private val cloudAccess: CloudAccessGuard,
    private val network: NetworkStatus,
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

    private val _cloudOriginal = MutableStateFlow(CloudOriginalState(_currentId.value, CloudOriginalUi.Idle))

    /** Original OneDrive de l'image courante (aperçu, téléchargement, prêt, à confirmer, échec). */
    val cloudOriginal: StateFlow<CloudOriginalState> = _cloudOriginal.asStateFlow()
    private var originalJob: Job? = null

    private val _sharePreparation = MutableStateFlow<SharePreparation?>(null)

    /** Téléchargement en cours avant un partage ou un « Ouvrir avec » (null sinon). */
    val sharePreparation: StateFlow<SharePreparation?> = _sharePreparation.asStateFlow()
    private var shareJob: Job? = null

    /**
     * Réseau facturé (données mobiles, économiseur de données) : aperçus OneDrive plus petits.
     * Null tant que l'état réel n'est pas lu (les pages attendent avant de choisir leur palier) :
     * lecture directe d'abord, puis suivi des changements (la valeur initiale du flux partagé,
     * qui peut être un état « inconnu », est ignorée).
     */
    val costlyNetwork: StateFlow<Boolean?> = flow {
        emit(network.current().costly)
        emitAll(network.state.drop(1).map { it.costly })
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Etat hors ligne du média courant (copie, téléchargement en file ou en cours, échec). */
    val offlineState: StateFlow<OfflineItemState> = _currentId
        .flatMapLatest { offline.observeItem(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineItemState.NONE)

    private val _offlinePrompt = MutableStateFlow<OfflinePrompt?>(null)

    /** Boîte de dialogue d'une action hors ligne (gros lot, attente du Wi-Fi, espace, dernière copie). */
    val offlinePrompt: StateFlow<OfflinePrompt?> = _offlinePrompt.asStateFlow()

    init {
        // Tient la bande de miniatures à jour après une modification du média courant (rotation,
        // favori, fichier local apparu ou retiré, nouveau contenu OneDrive).
        viewModelScope.launch {
            currentMedia.collect { media ->
                if (media == null) return@collect
                val cached = _summaries.value[media.id] ?: return@collect
                val refreshed = refreshedSummary(cached, media)
                if (refreshed != cached) _summaries.update { it + (media.id to refreshed) }
            }
        }
        // Nouvelle image courante, ou nouvelle version de l'image (synchro) : l'original suivi
        // jusque-là est abandonné ; un original déjà téléchargé et à jour est affiché directement.
        viewModelScope.launch {
            currentMedia
                .filterNotNull()
                .map { it.id to cloudOriginalTargetOf(it) }
                .distinctUntilChanged()
                .collectLatest { (id, target) ->
                    originalJob?.cancel()
                    originalJob = null
                    _cloudOriginal.value = CloudOriginalState(id, CloudOriginalUi.Idle)
                    if (target == null) return@collectLatest
                    val file = try {
                        originals.existing(target)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                    if (file != null) {
                        _cloudOriginal.update {
                            if (it.mediaId == id) CloudOriginalState(id, CloudOriginalUi.Ready(Uri.fromFile(file), file.name)) else it
                        }
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

    // --- Original OneDrive ------------------------------------------------------------------

    /**
     * Zoom profond sur l'aperçu OneDrive de [mediaId] ([wanted]) ou retour en arrière : décide
     * s'il faut l'original (voir [originalDecision]) et le charge automatiquement, ou le propose
     * sur un réseau facturé (sauf réglage contraire). Sans compte utilisable : aperçu seul.
     */
    fun onHiResWanted(mediaId: Long, wanted: Boolean, requestedLongPx: Int, previewSide: Int) {
        val media = currentMedia.value?.takeIf { it.id == mediaId } ?: return
        val target = cloudOriginalTargetOf(media) ?: return
        viewModelScope.launch {
            val current = _cloudOriginal.value
            if (current.mediaId != mediaId) return@launch
            when (current.ui) {
                is CloudOriginalUi.Downloading, is CloudOriginalUi.Ready -> return@launch
                is CloudOriginalUi.NeedsConfirmation -> if (!wanted) {
                    _cloudOriginal.value = CloudOriginalState(mediaId, CloudOriginalUi.Idle)
                    return@launch
                }
                CloudOriginalUi.Idle, is CloudOriginalUi.Failed -> Unit
            }
            if (!wanted) return@launch
            if (!cloudAccess.canAccess()) {
                // Zoom profond sans compte utilisable (reconnexion nécessaire, autre compte) : la
                // pastille l'explique, si l'original apporterait vraiment plus de détail.
                val useful = originalDecision(
                    deepZoom = true,
                    requestedLongPx = requestedLongPx,
                    previewSide = previewSide,
                    imageLongPx = imageLongSide(media),
                    costlyNetwork = false,
                    allowOnCostly = true,
                ) != OriginalDecision.NOT_NEEDED
                if (useful) {
                    _cloudOriginal.update {
                        if (it.mediaId == mediaId) CloudOriginalState(mediaId, CloudOriginalUi.Failed(DownloadError.AUTH_REQUIRED)) else it
                    }
                }
                return@launch
            }
            val decision = originalDecision(
                deepZoom = true,
                requestedLongPx = requestedLongPx,
                previewSide = previewSide,
                imageLongPx = imageLongSide(media),
                costlyNetwork = network.current().costly,
                allowOnCostly = settings.value.loadOriginalsOnMobileData,
            )
            if (_cloudOriginal.value.mediaId != mediaId) return@launch
            when (decision) {
                OriginalDecision.NOT_NEEDED -> Unit
                OriginalDecision.AUTO -> startOriginal(target, media)
                OriginalDecision.ASK -> _cloudOriginal.value =
                    CloudOriginalState(mediaId, CloudOriginalUi.NeedsConfirmation(target.sizeBytes))
            }
        }
    }

    /** « Charger l'original » (ou nouvel essai après un échec) pour l'image courante [mediaId]. */
    fun loadOriginal(mediaId: Long) {
        val media = currentMedia.value?.takeIf { it.id == mediaId } ?: return
        val target = cloudOriginalTargetOf(media) ?: return
        if (_cloudOriginal.value.ui is CloudOriginalUi.Downloading) return
        startOriginal(target, media)
    }

    /** Abandonne le téléchargement de l'original (l'aperçu reste affiché). */
    fun cancelOriginal() {
        originalJob?.cancel()
        originalJob = null
        _cloudOriginal.update { CloudOriginalState(it.mediaId, CloudOriginalUi.Idle) }
    }

    private fun startOriginal(target: CloudFileTarget, media: MediaDetails) {
        val id = media.id
        originalJob?.cancel()
        _cloudOriginal.value = CloudOriginalState(id, CloudOriginalUi.Downloading(0, target.sizeBytes))
        originalJob = viewModelScope.launch {
            var lastPercent = -1
            try {
                val file = originals.ensureOriginal(target) { done, total ->
                    val percent = progressPercent(done, total)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _cloudOriginal.update {
                            if (it.mediaId == id && it.ui is CloudOriginalUi.Downloading) {
                                CloudOriginalState(id, CloudOriginalUi.Downloading(done, total))
                            } else {
                                it
                            }
                        }
                    }
                }
                _cloudOriginal.update {
                    if (it.mediaId == id) CloudOriginalState(id, CloudOriginalUi.Ready(Uri.fromFile(file), file.name)) else it
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = downloadErrorOf(e) ?: DownloadError.SERVER
                Log.w(TAG, "Original OneDrive indisponible : $error", e)
                _cloudOriginal.update { if (it.mediaId == id) CloudOriginalState(id, CloudOriginalUi.Failed(error)) else it }
            }
        }
    }

    // --- Copies hors ligne ----------------------------------------------------------------------

    /** « Conserver hors ligne » (ou reprise d'un téléchargement en pause / en échec) du média courant. */
    fun keepOffline() = keep(listOf(_currentId.value), confirmed = false)

    /** Gros lot confirmé par l'utilisateur. */
    fun confirmLargeBatch(ids: List<Long>) = keep(ids, confirmed = true)

    private fun keep(ids: List<Long>, confirmed: Boolean) {
        viewModelScope.launch {
            val result = try {
                offline.keepOffline(ids, confirmed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch send(ViewerEvent.Toast(R.string.common_error_generic))
            }
            showFeedback(keepFeedback(result, ids))
        }
    }

    /** « Télécharger maintenant » : la file en attente du Wi-Fi utilise le réseau mobile. */
    fun downloadOfflineNow() {
        viewModelScope.launch { runCatchingIo { offline.downloadNow() } }
    }

    /** « Libérer l'espace » : immédiat, sauf pour une dernière copie (confirmation). */
    fun releaseOffline() {
        val ids = listOf(_currentId.value)
        viewModelScope.launch {
            // Compte impossible : rien n'est supprimé (une dernière copie l'aurait été sans confirmation).
            val lastCopies = runCatchingIo { offline.lastCopiesAmong(ids) }.getOrNull()
            when (val step = releaseStep(ids, lastCopies)) {
                ReleaseStep.Release -> release(ids)
                is ReleaseStep.Confirm -> _offlinePrompt.value = step.prompt
                ReleaseStep.Abort -> send(ViewerEvent.Toast(R.string.offline_release_check_failed))
            }
        }
    }

    /** Libération d'une dernière copie confirmée. */
    fun confirmRelease(ids: List<Long>) = release(ids)

    private fun release(ids: List<Long>) {
        viewModelScope.launch {
            runCatchingIo { offline.release(ids) }
                .onSuccess { send(ViewerEvent.PluralToast(R.plurals.offline_released, it)) }
                .onFailure { send(ViewerEvent.Toast(R.string.common_error_generic)) }
        }
    }

    /** Annule le téléchargement hors ligne du média courant (une copie déjà présente reste). */
    fun cancelOfflineDownload() {
        val id = _currentId.value
        viewModelScope.launch {
            runCatchingIo { offline.cancel(listOf(id)) }
                .onSuccess { send(ViewerEvent.Toast(R.string.offline_cancelled)) }
                .onFailure { send(ViewerEvent.Toast(R.string.common_error_generic)) }
        }
    }

    fun dismissOfflinePrompt() {
        _offlinePrompt.value = null
    }

    /** L'autorisation des notifications a été demandée (une seule fois). */
    fun markNotificationsAsked() {
        viewModelScope.launch { runCatching { settingsRepository.setOfflineNotificationsAsked(true) } }
    }

    private fun showFeedback(feedback: OfflineFeedback) {
        when (feedback) {
            is OfflineFeedback.Prompt -> _offlinePrompt.value = feedback.prompt
            is OfflineFeedback.Text -> send(ViewerEvent.Toast(feedback.resId))
            is OfflineFeedback.Plural -> send(ViewerEvent.PluralToast(feedback.resId, feedback.count))
        }
    }

    // --- Partage / Ouvrir avec ----------------------------------------------------------------

    /** Partage le média courant : fichier local, copie hors ligne, sinon original OneDrive téléchargé (plafonné). */
    fun shareCurrent() = prepareAndSend(openWith = false)

    /** « Ouvrir avec » : fichier local, copie hors ligne, sinon original OneDrive téléchargé (plafonné). */
    fun openCurrentWith() = prepareAndSend(openWith = true)

    /** Annule le téléchargement qui précède un partage. */
    fun cancelSharePreparation() {
        shareJob?.cancel()
        shareJob = null
        _sharePreparation.value = null
    }

    private fun prepareAndSend(openWith: Boolean) {
        val media = currentMedia.value ?: return
        val local = media.uri?.takeIf { media.hasLocal }
        if (local != null) {
            send(if (openWith) ViewerEvent.OpenWith(local, media.mimeType) else ViewerEvent.Share(listOf(local), listOf(media.mimeType)))
            return
        }
        if (shareJob?.isActive == true) return
        shareJob = viewModelScope.launch {
            // Copie hors ligne intacte : partagée telle quelle (aucun téléchargement).
            val copy = media.offlineCopy
            if (copy != null && withContext(io) { PlaybackSourceResolver.isOfflineFileUsable(copy) }) {
                val uri = try {
                    shareUris.uriFor(copy.file, media.displayName)
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    return@launch send(ViewerEvent.Toast(R.string.common_error_generic))
                }
                return@launch send(if (openWith) ViewerEvent.OpenWith(uri, media.mimeType) else ViewerEvent.Share(listOf(uri), listOf(media.mimeType)))
            }
            val target = cloudFileTargetOf(media) ?: return@launch send(ViewerEvent.Toast(R.string.common_cloud_only_not_supported))
            if (!cloudAccess.canAccess()) return@launch send(ViewerEvent.Toast(R.string.share_cloud_signed_out))
            val plan = planShare(
                listOf(ShareCandidate(media.id, hasLocalFile = false, offlinePath = null, cloudItemId = target.itemId, sizeBytes = target.sizeBytes)),
                cloudAllowed = true,
            )
            if (plan.refusal != null) return@launch send(ViewerEvent.Toast(R.string.share_cloud_too_large))
            _sharePreparation.value = SharePreparation(0, target.sizeBytes)
            var lastPercent = -1
            try {
                val file = originals.ensureOriginal(target) { done, total ->
                    val percent = progressPercent(done, total)
                    if (percent != lastPercent) {
                        lastPercent = percent
                        _sharePreparation.update { it?.let { SharePreparation(done, total) } }
                    }
                }
                val uri = shareUris.uriFor(file, media.displayName)
                send(if (openWith) ViewerEvent.OpenWith(uri, media.mimeType) else ViewerEvent.Share(listOf(uri), listOf(media.mimeType)))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val error = downloadErrorOf(e) ?: DownloadError.SERVER
                Log.w(TAG, "Partage OneDrive impossible : $error", e)
                send(ViewerEvent.Toast(downloadErrorMessage(error)))
            } finally {
                _sharePreparation.value = null
            }
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
        const val TAG = "ViewerViewModel"
        const val KEY_CURRENT = "viewer_current_id"
    }
}

/**
 * Résumé de la bande de miniatures mis à jour depuis les détails du média courant : tout ce qui
 * change l'image affichée (version, fichier local, miniature OneDrive, copie hors ligne) et le favori.
 */
internal fun refreshedSummary(cached: MediaSummary, media: MediaDetails): MediaSummary = cached.copy(
    dateModified = media.dateModified,
    isFavorite = media.isFavorite,
    uri = media.uri,
    hasLocal = media.hasLocal,
    hasCloud = media.hasCloud,
    hasOffline = media.hasOffline,
    cloudThumb = media.cloudThumb,
)
