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
import fr.eva.cloudgallery.data.local.SecureFolderDetector
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
import fr.eva.cloudgallery.ui.securefolder.SecureFolderCandidate
import fr.eva.cloudgallery.ui.securefolder.SecureFolderMoveState
import fr.eva.cloudgallery.ui.securefolder.SecureFolderPlan
import fr.eva.cloudgallery.ui.securefolder.SecureFolderPrompt
import fr.eva.cloudgallery.ui.securefolder.planSecureFolderMove
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
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One-off events sent to the UI. */
sealed interface ViewerEvent {
    /** System consent request (delete / write) to launch with StartIntentSenderForResult. */
    data class LaunchIntentSender(val sender: IntentSender) : ViewerEvent
    data class Toast(@StringRes val message: Int, val arg: String? = null) : ViewerEvent

    /** Short plural message ([count] is used as both quantity and argument). */
    data class PluralToast(@PluralsRes val message: Int, val count: Int) : ViewerEvent
    /** The crop was saved: close the editor. */
    data object CropSaved : ViewerEvent

    /** Open the share sheet (read-only `content://` URIs). */
    data class Share(val uris: List<Uri>, val mimeTypes: List<String?>) : ViewerEvent

    /** Open the media in another app (read-only `content://` URI). */
    data class OpenWith(val uri: Uri, val mimeType: String?) : ViewerEvent
}

/** EXIF state of the media whose Details sheet is open. */
data class ExifState(val mediaId: Long, val loading: Boolean, val info: ExifInfo?)

/** Action awaiting the result of a system consent request. */
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
    private val secureFolderDetector: SecureFolderDetector,
    @IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val route: ViewerRoute = savedStateHandle.toRoute<ViewerRoute>()

    /** Ids of the context, null until they are loaded. */
    val ids: StateFlow<List<Long>?> = mediaRepository.observeMediaIds(route.context)
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _currentId = MutableStateFlow(savedStateHandle.get<Long>(KEY_CURRENT) ?: route.mediaId)

    /** Displayed media (updated by the pager). */
    val currentId: StateFlow<Long> = _currentId.asStateFlow()

    val currentMedia: StateFlow<MediaDetails?> = _currentId
        .flatMapLatest { mediaRepository.observeMedia(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val settings: StateFlow<AppSettings> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /** Local folders (targets of "Copy to folder"). */
    val localFolders: StateFlow<List<AlbumSummary>> = albumRepository.observeAlbums(AlbumSort.NAME)
        .map { albums -> albums.filter { it.type == AlbumType.LOCAL_FOLDER && !it.relativePath.isNullOrBlank() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _summaries = MutableStateFlow<Map<Long, MediaSummary>>(emptyMap())

    /** Summaries loaded on demand for the thumbnail strip. */
    val summaries: StateFlow<Map<Long, MediaSummary>> = _summaries.asStateFlow()
    private val loadingSummaries = mutableSetOf<Long>()

    private val _exif = MutableStateFlow<ExifState?>(null)
    val exif: StateFlow<ExifState?> = _exif.asStateFlow()

    private val _busy = MutableStateFlow(false)

    /** True during a write (rotation, crop, copy). */
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _events = Channel<ViewerEvent>(Channel.BUFFERED)
    val events: Flow<ViewerEvent> = _events.receiveAsFlow()

    private var pendingAction: PendingAction? = null

    private val _cloudOriginal = MutableStateFlow(CloudOriginalState(_currentId.value, CloudOriginalUi.Idle))

    /** OneDrive original of the current image (preview, downloading, ready, needs confirmation, failed). */
    val cloudOriginal: StateFlow<CloudOriginalState> = _cloudOriginal.asStateFlow()
    private var originalJob: Job? = null

    private val _sharePreparation = MutableStateFlow<SharePreparation?>(null)

    /** Download in progress before a share or an "Open with" (null otherwise). */
    val sharePreparation: StateFlow<SharePreparation?> = _sharePreparation.asStateFlow()
    private var shareJob: Job? = null

    /**
     * Metered network (mobile data, data saver): smaller OneDrive previews.
     * Null until the actual state is read (pages wait before choosing their tier): direct read
     * first, then change tracking (the shared flow's initial value, which may be an "unknown"
     * state, is ignored).
     */
    val costlyNetwork: StateFlow<Boolean?> = flow {
        emit(network.current().costly)
        emitAll(network.state.drop(1).map { it.costly })
    }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Offline state of the current media (copy, download queued or in progress, failure). */
    val offlineState: StateFlow<OfflineItemState> = _currentId
        .flatMapLatest { offline.observeItem(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OfflineItemState.NONE)

    private val _offlinePrompt = MutableStateFlow<OfflinePrompt?>(null)

    /** Dialog of an offline action (large batch, waiting for Wi-Fi, storage space, last copy). */
    val offlinePrompt: StateFlow<OfflinePrompt?> = _offlinePrompt.asStateFlow()

    init {
        // Keeps the thumbnail strip up to date after a change to the current media (rotation,
        // favorite, local file appeared or removed, new OneDrive content).
        viewModelScope.launch {
            currentMedia.collect { media ->
                if (media == null) return@collect
                val cached = _summaries.value[media.id] ?: return@collect
                val refreshed = refreshedSummary(cached, media)
                if (refreshed != cached) _summaries.update { it + (media.id to refreshed) }
            }
        }
        // New current image, or new version of the image (sync): the original tracked so far is
        // dropped; an already downloaded, up-to-date original is displayed directly.
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

    /** Loads (in batches) the missing summaries for the visible window of the thumbnail strip. */
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
                // The cells stay on the loading background.
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

    // --- OneDrive original ------------------------------------------------------------------

    /**
     * Deep zoom on the OneDrive preview of [mediaId] ([wanted]) or zooming back out: decides
     * whether the original is needed (see [originalDecision]) and loads it automatically, or offers
     * it on a metered network (unless the setting says otherwise). Without a usable account: preview only.
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
                // Deep zoom without a usable account (sign-in needed, different account): the chip
                // explains it, if the original would really bring more detail.
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

    /** "Load original" (or retry after a failure) for the current image [mediaId]. */
    fun loadOriginal(mediaId: Long) {
        val media = currentMedia.value?.takeIf { it.id == mediaId } ?: return
        val target = cloudOriginalTargetOf(media) ?: return
        if (_cloudOriginal.value.ui is CloudOriginalUi.Downloading) return
        startOriginal(target, media)
    }

    /** Abandons the original's download (the preview stays displayed). */
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
                Log.w(TAG, "OneDrive original unavailable: $error", e)
                _cloudOriginal.update { if (it.mediaId == id) CloudOriginalState(id, CloudOriginalUi.Failed(error)) else it }
            }
        }
    }

    // --- Offline copies -------------------------------------------------------------------------

    /** "Keep offline" (or resume a paused / failed download) for the current media. */
    fun keepOffline() = keep(listOf(_currentId.value), confirmed = false)

    /** Large batch confirmed by the user. */
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

    /** "Download now": the queue waiting for Wi-Fi uses the mobile network. */
    fun downloadOfflineNow() {
        viewModelScope.launch { runCatchingIo { offline.downloadNow() } }
    }

    /** "Free up space": immediate, except for a last copy (confirmation). */
    fun releaseOffline() {
        val ids = listOf(_currentId.value)
        viewModelScope.launch {
            // Count failed: nothing is deleted (a last copy would have been deleted without confirmation).
            val lastCopies = runCatchingIo { offline.lastCopiesAmong(ids) }.getOrNull()
            when (val step = releaseStep(ids, lastCopies)) {
                ReleaseStep.Release -> release(ids)
                is ReleaseStep.Confirm -> _offlinePrompt.value = step.prompt
                ReleaseStep.Abort -> send(ViewerEvent.Toast(R.string.offline_release_check_failed))
            }
        }
    }

    /** Releasing a confirmed last copy. */
    fun confirmRelease(ids: List<Long>) = release(ids)

    private fun release(ids: List<Long>) {
        viewModelScope.launch {
            runCatchingIo { offline.release(ids) }
                .onSuccess { send(ViewerEvent.PluralToast(R.plurals.offline_released, it)) }
                .onFailure { send(ViewerEvent.Toast(R.string.common_error_generic)) }
        }
    }

    /** Cancels the offline download of the current media (an already present copy stays). */
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

    /** The notification permission has been requested (only once). */
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

    // --- Share / Open with --------------------------------------------------------------------

    /** Shares the current media: local file, offline copy, otherwise downloaded OneDrive original (capped). */
    fun shareCurrent() = prepareAndSend(openWith = false)

    /** "Open with": local file, offline copy, otherwise downloaded OneDrive original (capped). */
    fun openCurrentWith() = prepareAndSend(openWith = true)

    /** Cancels the download that precedes a share. */
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
            // Intact offline copy: shared as is (no download).
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
                Log.w(TAG, "OneDrive share failed: $error", e)
                send(ViewerEvent.Toast(downloadErrorMessage(error)))
            } finally {
                _sharePreparation.value = null
            }
        }
    }

    // --- Deletion ----------------------------------------------------------------------------

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

    // --- Secure Folder ------------------------------------------------------------------------

    /** Samsung Secure Folder app installed ("Move to Secure Folder"). */
    val secureFolderAvailable: StateFlow<Boolean> = flow { emit(secureFolderDetector.isInstalled()) }
        .catch { emit(false) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val secureFolderMove = SecureFolderMoveState()

    /** Move to the Secure Folder in progress (dialogs). */
    val secureFolderPrompt: StateFlow<SecureFolderPrompt?> = secureFolderMove.prompt

    /** "Move to Secure Folder": on-device file only. */
    fun moveCurrentToSecureFolder() {
        val media = currentMedia.value ?: return
        val uri = currentLocalUri()
        val candidate = SecureFolderCandidate(media.id, hasLocalFile = uri != null, hasCloud = media.hasCloud)
        when (val plan = planSecureFolderMove(listOf(candidate))) {
            is SecureFolderPlan.Ready -> secureFolderMove.start(plan, uriOf = { uri }, mimeTypeOf = { media.mimeType })
            else -> send(ViewerEvent.Toast(R.string.common_cloud_only_not_supported))
        }
    }

    fun onSecureFolderShareLaunched() = secureFolderMove.onShareLaunched()

    fun onSecureFolderShareReturned() = secureFolderMove.onShareReturned()

    fun dismissSecureFolder() = secureFolderMove.dismiss()

    /**
     * Addition to the Secure Folder confirmed by the user: the original goes to the Android trash
     * (always the trash, whatever the setting), after the system confirmation.
     */
    fun trashAfterSecureFolder() {
        val moved = secureFolderMove.takeForTrash() ?: return
        viewModelScope.launch {
            runCatchingIo { writer.createDeleteRequest(moved.uris, toTrash = true) }
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
        // Check the format BEFORE asking the user for write permission.
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

    /** Lossless rotation possible for this media (format whose EXIF orientation can be modified). */
    fun canRotate(media: MediaDetails?): Boolean =
        media != null && media.type == MediaType.IMAGE && writer.canRotate(media.mimeType)

    /** Result of the system consent dialog. */
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

    // --- Crop / copy ------------------------------------------------------------------------

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

    /** Forgets the cached EXIF (e.g. after a rotation, the sheet will re-read the file). */
    fun clearExif() {
        _exif.value = null
    }

    // --- Utilities ---------------------------------------------------------------------------

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
            // The MediaStore observer will catch up with the change.
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
 * Thumbnail strip summary updated from the current media's details: everything that changes the
 * displayed image (version, local file, OneDrive thumbnail, offline copy) and the favorite flag.
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
