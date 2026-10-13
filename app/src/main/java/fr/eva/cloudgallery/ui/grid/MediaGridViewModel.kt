package fr.eva.cloudgallery.ui.grid

import android.content.IntentSender
import android.net.Uri
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import androidx.paging.insertSeparators
import androidx.paging.map
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.data.cloud.CloudAccessGuard
import fr.eva.cloudgallery.data.cloud.content.CloudOriginalStore
import fr.eva.cloudgallery.data.cloud.content.ShareUriProvider
import fr.eva.cloudgallery.data.cloud.content.cloudFileTargetOf
import fr.eva.cloudgallery.data.cloud.content.downloadErrorOf
import fr.eva.cloudgallery.data.local.SecureFolderDetector
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.DownloadError
import fr.eva.cloudgallery.domain.model.MediaContext
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.repository.AlbumRepository
import fr.eva.cloudgallery.domain.repository.FavoritesRepository
import fr.eva.cloudgallery.domain.repository.LocalMediaIndexer
import fr.eva.cloudgallery.domain.repository.LocalMediaWriter
import fr.eva.cloudgallery.domain.repository.MediaRepository
import fr.eva.cloudgallery.domain.repository.OfflineRepository
import fr.eva.cloudgallery.domain.repository.SettingsRepository
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
import fr.eva.cloudgallery.ui.util.MAX_SHARE_CLOUD_BYTES
import fr.eva.cloudgallery.ui.video.PlaybackSourceResolver
import fr.eva.cloudgallery.ui.util.MAX_SHARE_CLOUD_ITEMS
import fr.eva.cloudgallery.ui.util.ShareCandidate
import fr.eva.cloudgallery.ui.util.ShareRefusal
import fr.eva.cloudgallery.ui.util.downloadErrorMessage
import fr.eva.cloudgallery.ui.util.planShare
import fr.eva.cloudgallery.ui.util.progressPercent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.ZoneId
import javax.inject.Inject

/** Common dependencies of the grid screens (grouped to keep the Hilt constructors light). */
class MediaGridDeps @Inject constructor(
    val mediaRepository: MediaRepository,
    val favoritesRepository: FavoritesRepository,
    val albumRepository: AlbumRepository,
    val settingsRepository: SettingsRepository,
    val localMediaIndexer: LocalMediaIndexer,
    val localMediaWriter: LocalMediaWriter,
    val cloudOriginals: CloudOriginalStore,
    val shareUris: ShareUriProvider,
    val cloudAccess: CloudAccessGuard,
    val offline: OfflineRepository,
    val secureFolder: SecureFolderDetector,
    @param:IoDispatcher val ioDispatcher: CoroutineDispatcher,
    @param:ApplicationScope val applicationScope: CoroutineScope,
)

/**
 * Download from OneDrive before a share: [count] items, [done] / [total] bytes
 * ([total] ≤ 0 if unknown).
 */
data class GridSharePreparation(val count: Int, val done: Long, val total: Long)

/** One-off message to display (toast), resolved in the UI. */
sealed interface GridMessage {
    data class Text(@StringRes val resId: Int, val args: List<Any> = emptyList()) : GridMessage
    data class Plural(@PluralsRes val resId: Int, val count: Int, val args: List<Any> = emptyList()) : GridMessage
}

/** One-off VM -> UI events. */
sealed interface GridEvent {
    data class Share(val uris: List<Uri>, val mimeTypes: List<String?>) : GridEvent
    data class LaunchDeleteRequest(val intentSender: IntentSender) : GridEvent
    data class Message(val message: GridMessage) : GridEvent
}

/**
 * Common base of the grids (Photos, albums, favorites, videos): paged data with headers,
 * persistent zoom level, selection and actions on the selection.
 */
abstract class MediaGridViewModel(
    val mediaContext: MediaContext,
    protected val deps: MediaGridDeps,
) : ViewModel() {

    /** Zoom level saved for this screen. */
    protected abstract fun zoomLevelOf(settings: AppSettings): Int

    protected abstract suspend fun persistZoomLevel(level: Int)

    private val zone: ZoneId = ZoneId.systemDefault()

    val settings: StateFlow<AppSettings?> = deps.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Local value applied immediately, before the DataStore round trip. */
    private val zoomOverride = MutableStateFlow<Int?>(null)

    val zoomLevel: StateFlow<Int?> = combine(settings, zoomOverride) { s, override ->
        override ?: s?.let { GridZoomLevels.clamp(zoomLevelOf(it)) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val grouping: Flow<GridGrouping> = zoomLevel.filterNotNull()
        .map { GridZoomLevels.grouping(it) }
        .distinctUntilChanged()

    /**
     * Scope of the paging caches: the transformations (conversion, insertion of headers on all
     * cached pages when the grouping changes) run off the main thread; the UI then collects the
     * result on the main thread.
     */
    private val pagingScope = CoroutineScope(viewModelScope.coroutineContext + Dispatchers.Default)

    /**
     * Fast-scroll jump: the paging restarts from the requested rank (a new request, even an
     * identical one, recreates the Pager: [JumpRequest.seq]).
     */
    private data class JumpRequest(val offset: Int, val seq: Int)

    private val jumpRequest = MutableStateFlow(JumpRequest(0, 0))

    @OptIn(ExperimentalCoroutinesApi::class)
    private val basePaging: Flow<PagingData<GridItem.Media>> = jumpRequest
        .flatMapLatest { request -> deps.mediaRepository.pagedMedia(mediaContext, request.offset) }
        .map { data -> data.map { GridItem.Media(it) } }
        .cachedIn(pagingScope)

    /**
     * Paged grid with day / month headers. The base paging is cached: a grouping change does not
     * reload the pages, it only re-inserts the headers.
     */
    val gridItems: Flow<PagingData<GridItem>> = combine(basePaging, grouping) { data, g ->
        val withHeaders: PagingData<GridItem> = data.insertSeparators { before: GridItem.Media?, after: GridItem.Media? ->
            headerBetween(before?.media, after?.media, g, zone)
        }
        withHeaders
    }.flowOn(Dispatchers.Default).cachedIn(pagingScope)

    val count: StateFlow<Int?> = deps.mediaRepository.observeCount(mediaContext)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Current grouping (day / month), for the bubble and the header keys of the fast scroll. */
    val gridGrouping: StateFlow<GridGrouping?> = grouping
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Dates of all media in the context (fast scroll), built off the main thread. */
    val fastScrollIndex: StateFlow<FastScrollIndex?> = deps.mediaRepository.observeDates(mediaContext)
        .map { dates -> FastScrollIndex(dates.toLongArray()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** Reloads the grid starting from the media at rank [offset] (target outside the loaded pages). */
    fun jumpTo(offset: Int) {
        jumpRequest.update { JumpRequest(offset.coerceAtLeast(0), it.seq + 1) }
    }

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    val allSelected: StateFlow<Boolean> = combine(_selection, count) { sel, c ->
        c != null && c > 0 && sel.size >= c
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Local files of the selection (counted in the database, on the I/O thread): enables or not
     * Share / Delete / Copy and gives the exact counts for the confirmation dialogs.
     * null if unknown (read failure): the actions remain possible and filter by themselves.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val selectionTargets: StateFlow<SelectionTargets?> = _selection
        .mapLatest { ids ->
            if (ids.isEmpty()) {
                SelectionTargets(ids, 0, 0)
            } else {
                try {
                    SelectionTargets(
                        forSelection = ids,
                        local = deps.mediaRepository.countWithLocalFile(ids),
                        shareable = deps.mediaRepository.countShareable(ids),
                        keepableOffline = deps.mediaRepository.countKeepableOffline(ids),
                        withOfflineCopy = deps.mediaRepository.countWithOfflineCopy(ids),
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Exception) {
                    null
                }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _events = Channel<GridEvent>(Channel.BUFFERED)
    val events: Flow<GridEvent> = _events.receiveAsFlow()

    private val _offlinePrompt = MutableStateFlow<OfflinePrompt?>(null)

    /** Dialog of an offline action (large batch, waiting for Wi-Fi, space, last copy). */
    val offlinePrompt: StateFlow<OfflinePrompt?> = _offlinePrompt.asStateFlow()

    private val _sharePreparation = MutableStateFlow<GridSharePreparation?>(null)

    /** Download in progress before a share (null otherwise). */
    val sharePreparation: StateFlow<GridSharePreparation?> = _sharePreparation.asStateFlow()
    private var shareJob: Job? = null

    /**
     * Complete IDs (from the database) of the days whose header was tapped: the paging only loads
     * part of a large day, so the checkbox state cannot depend on it alone.
     */
    private val _dayIds = MutableStateFlow<Map<Long, List<Long>>>(emptyMap())
    val dayIds: StateFlow<Map<Long, List<Long>>> = _dayIds.asStateFlow()

    /** Samsung Secure Folder app installed ("Move to Secure Folder"). */
    val secureFolderAvailable: StateFlow<Boolean> = flow { emit(deps.secureFolder.isInstalled()) }
        .catch { emit(false) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val secureFolderMove = SecureFolderMoveState()

    /** Move to Secure Folder in progress (dialogs). */
    val secureFolderPrompt: StateFlow<SecureFolderPrompt?> = secureFolderMove.prompt

    /** Deletion split into batches (one system confirmation per batch). */
    private val pendingDeleteBatches = ArrayDeque<List<Uri>>()
    private var pendingDeleteToTrash = true
    private var completedDeleteBatches = 0

    init {
        // Content changed (addition, deletion): the cached per-day lists are no longer reliable.
        viewModelScope.launch {
            deps.mediaRepository.observeCount(mediaContext).collect { _dayIds.value = emptyMap() }
        }
    }

    // --- Zoom ---

    fun onZoomChange(delta: Int) {
        val current = zoomLevel.value ?: return
        val next = GridZoomLevels.step(current, delta)
        if (next == current) return
        zoomOverride.value = next
        viewModelScope.launch { runCatching { persistZoomLevel(next) } }
    }

    // --- Selection ---

    fun enterSelectionMode() {
        _selectionMode.value = true
    }

    fun exitSelectionMode() {
        _selectionMode.value = false
        _selection.value = emptySet()
        _dayIds.value = emptyMap()
    }

    fun toggle(id: Long) {
        _selection.update { if (id in it) it - id else it + id }
    }

    /** Long press: enters selection mode with this item, or toggles its state. */
    fun onLongPress(id: Long) {
        if (!_selectionMode.value) {
            _selectionMode.value = true
            _selection.value = setOf(id)
        } else {
            toggle(id)
        }
    }

    fun setSelected(ids: Collection<Long>, selected: Boolean) {
        if (ids.isEmpty()) return
        _selectionMode.value = true
        _selection.update { if (selected) it + ids else it - ids.toSet() }
    }

    /**
     * Day header tapped in selection mode: selects (or deselects if all already are) ALL media of
     * the day, read from the database, including those the paging has not loaded yet.
     */
    fun toggleDay(header: GridItem.Header) {
        if (header.grouping != GridGrouping.DAY) return
        viewModelScope.launch {
            val (start, end) = periodBoundsMs(header.epochDay, header.grouping, zone)
            val ids = try {
                deps.mediaRepository.idsInPeriod(mediaContext, start, end)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                toast(R.string.common_error_generic)
                return@launch
            }
            if (ids.isEmpty()) return@launch
            _dayIds.update { it + (header.epochDay to ids) }
            val selected = _selection.value
            setSelected(ids, !ids.all { it in selected })
        }
    }

    /**
     * End of a drag selection: the range between [anchor] and [end] is resolved in the database to
     * include the items the paging had not loaded yet during the gesture.
     */
    fun finishDragSelect(anchor: MediaSummary, end: MediaSummary, select: Boolean) {
        if (anchor.id == end.id) return
        viewModelScope.launch {
            val ids = try {
                deps.mediaRepository.idsBetween(mediaContext, anchor, end)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            if (ids.isNotEmpty()) setSelected(ids, select)
        }
    }

    fun toggleAll() {
        viewModelScope.launch {
            if (allSelected.value) {
                _selection.value = emptySet()
            } else {
                val ids = withContext(deps.ioDispatcher) {
                    deps.mediaRepository.observeMediaIds(mediaContext).first()
                }
                _selection.value = ids.toSet()
            }
        }
    }

    // --- Actions ---

    protected fun emit(event: GridEvent) {
        _events.trySend(event)
    }

    protected fun toast(@StringRes resId: Int, vararg args: Any) {
        emit(GridEvent.Message(GridMessage.Text(resId, args.toList())))
    }

    protected suspend fun selectedSummaries(ids: Collection<Long> = _selection.value): List<MediaSummary> =
        withContext(deps.ioDispatcher) {
            ids.toList().chunked(SQL_CHUNK).flatMap { deps.mediaRepository.getSummaries(it) }
        }

    /**
     * Selected media that have a local file ([localActionTargets]). Items only on OneDrive are
     * never processed: message if there is no local file, and number of skipped items if
     * [notifySkipped] (otherwise already shown by a confirmation dialog).
     */
    private suspend fun localSelection(notifySkipped: Boolean): List<MediaSummary> {
        val all = selectedSummaries()
        val local = localActionTargets(all)
        val skipped = all.size - local.size
        when {
            local.isEmpty() -> toast(R.string.common_cloud_only_not_supported)
            skipped > 0 && notifySkipped ->
                emit(GridEvent.Message(GridMessage.Plural(R.plurals.grid_cloud_items_skipped, skipped, listOf(skipped))))
        }
        return local
    }

    // --- Offline copies ---

    /** "Keep offline" for the selection (large batch: confirmation first). */
    fun keepOffline() = keep(_selection.value.toList(), confirmed = false)

    /** Large batch confirmed by the user. */
    fun confirmLargeBatch(ids: List<Long>) = keep(ids, confirmed = true)

    private fun keep(ids: List<Long>, confirmed: Boolean) {
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val result = try {
                deps.offline.keepOffline(ids, confirmed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch toast(R.string.common_error_generic)
            }
            val feedback = keepFeedback(result, ids)
            showOfflineFeedback(feedback)
            if (feedback !is OfflineFeedback.Prompt || feedback.prompt is OfflinePrompt.WaitingForWifi) exitSelectionMode()
        }
    }

    /** "Download now": the queue waiting for Wi-Fi uses the mobile network. */
    fun downloadOfflineNow() {
        viewModelScope.launch { runCatching { deps.offline.downloadNow() } }
    }

    /** "Free up space": immediate, unless there are last copies (confirmation). */
    fun releaseOffline() {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            // Count failed: nothing is deleted (a last copy would have been deleted without confirmation).
            val lastCopies = try {
                deps.offline.lastCopiesAmong(ids)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            when (val step = releaseStep(ids, lastCopies)) {
                ReleaseStep.Release -> release(ids)
                is ReleaseStep.Confirm -> _offlinePrompt.value = step.prompt
                ReleaseStep.Abort -> toast(R.string.offline_release_check_failed)
            }
        }
    }

    /** Release of last copies confirmed. */
    fun confirmRelease(ids: List<Long>) = release(ids)

    private fun release(ids: List<Long>) {
        viewModelScope.launch {
            val released = try {
                deps.offline.release(ids)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch toast(R.string.common_error_generic)
            }
            emit(GridEvent.Message(GridMessage.Plural(R.plurals.offline_released, released, listOf(released))))
            exitSelectionMode()
        }
    }

    fun dismissOfflinePrompt() {
        _offlinePrompt.value = null
    }

    /** The notification permission has been requested (only once). */
    fun markNotificationsAsked() {
        viewModelScope.launch { runCatching { deps.settingsRepository.setOfflineNotificationsAsked(true) } }
    }

    private fun showOfflineFeedback(feedback: OfflineFeedback) {
        when (feedback) {
            is OfflineFeedback.Prompt -> _offlinePrompt.value = feedback.prompt
            is OfflineFeedback.Text -> toast(feedback.resId)
            is OfflineFeedback.Plural ->
                emit(GridEvent.Message(GridMessage.Plural(feedback.resId, feedback.count, listOf(feedback.count))))
        }
    }

    /**
     * Shares the selection: local files and offline copies as is, items only on OneDrive after
     * downloading their original (signed-in account, at most [MAX_SHARE_CLOUD_ITEMS] items and
     * [MAX_SHARE_CLOUD_BYTES] bytes, cancellable progress).
     */
    fun share() {
        if (shareJob?.isActive == true) return
        shareJob = viewModelScope.launch {
            val all = selectedSummaries()
            val local = localActionTargets(all)
            val nonLocal = all.filter { !isLocalActionTarget(it.hasLocal, it.uri != null) }
            // Intact offline copies: shared without download; otherwise fall back to OneDrive.
            val offlineCopies = withContext(deps.ioDispatcher) {
                nonLocal.filter { it.hasOffline }.mapNotNull { summary ->
                    val details = deps.mediaRepository.getMedia(summary.id) ?: return@mapNotNull null
                    val copy = details.offlineCopy?.takeIf { PlaybackSourceResolver.isOfflineFileUsable(it) } ?: return@mapNotNull null
                    details to copy
                }
            }
            val offlineIds = offlineCopies.mapTo(HashSet()) { it.first.id }
            val cloud = nonLocal.filter { it.id !in offlineIds && it.hasCloud }
            if (local.size + offlineCopies.size > MAX_SHARE) {
                emit(GridEvent.Message(GridMessage.Plural(R.plurals.grid_share_limit, MAX_SHARE, listOf(MAX_SHARE))))
                return@launch
            }
            val cloudAllowed = cloud.isNotEmpty() && deps.cloudAccess.canAccess()
            if (cloudAllowed && cloud.size > MAX_SHARE_CLOUD_ITEMS) {
                // Refuse before reading the item details.
                emit(GridEvent.Message(GridMessage.Plural(R.plurals.share_cloud_too_many, MAX_SHARE_CLOUD_ITEMS, listOf(MAX_SHARE_CLOUD_ITEMS))))
                return@launch
            }
            val targets = if (cloudAllowed) {
                withContext(deps.ioDispatcher) {
                    cloud.mapNotNull { summary -> deps.mediaRepository.getMedia(summary.id)?.let(::cloudFileTargetOf) }
                }
            } else {
                emptyList()
            }
            val candidates = local.map { ShareCandidate(it.id, hasLocalFile = true, offlinePath = null, cloudItemId = null, sizeBytes = 0) } +
                offlineCopies.map { (details, copy) ->
                    ShareCandidate(details.id, hasLocalFile = false, offlinePath = copy.file.path, cloudItemId = null, sizeBytes = copy.sizeBytes)
                } +
                targets.map { ShareCandidate(it.mediaId, hasLocalFile = false, offlinePath = null, cloudItemId = it.itemId, sizeBytes = it.sizeBytes) }
            val plan = planShare(candidates, cloudAllowed)
            when (plan.refusal) {
                ShareRefusal.TOO_MANY -> return@launch emit(
                    GridEvent.Message(GridMessage.Plural(R.plurals.share_cloud_too_many, MAX_SHARE_CLOUD_ITEMS, listOf(MAX_SHARE_CLOUD_ITEMS))),
                )
                ShareRefusal.TOO_LARGE -> return@launch toast(R.string.share_cloud_too_large)
                null -> Unit
            }
            if (plan.local.isEmpty() && plan.offline.isEmpty() && plan.toDownload.isEmpty()) {
                return@launch toast(if (cloud.isNotEmpty() && !cloudAllowed) R.string.share_cloud_signed_out else R.string.common_cloud_only_not_supported)
            }
            if (cloud.isNotEmpty() && !cloudAllowed) {
                emit(GridEvent.Message(GridMessage.Plural(R.plurals.share_cloud_skipped_signed_out, cloud.size, listOf(cloud.size))))
            }
            val uris = ArrayList<Uri>(local.mapNotNull { it.uri })
            val mimeTypes = ArrayList<String?>(local.filter { it.uri != null }.map { it.mimeType })
            try {
                for ((details, copy) in offlineCopies) {
                    uris += deps.shareUris.uriFor(copy.file, details.displayName)
                    mimeTypes += details.mimeType
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch toast(R.string.common_error_generic)
            }
            if (targets.isNotEmpty()) {
                val total = plan.downloadBytes
                _sharePreparation.value = GridSharePreparation(targets.size, 0, total)
                try {
                    var before = 0L
                    for (target in targets) {
                        var lastPercent = -1
                        val file = deps.cloudOriginals.ensureOriginal(target) { done, _ ->
                            val percent = progressPercent(before + done, total)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _sharePreparation.update { it?.copy(done = before + done) }
                            }
                        }
                        uris += deps.shareUris.uriFor(file, target.displayName)
                        mimeTypes += target.mimeType
                        before += target.sizeBytes.coerceAtLeast(0)
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    return@launch toast(downloadErrorMessage(downloadErrorOf(e) ?: DownloadError.SERVER))
                } finally {
                    _sharePreparation.value = null
                }
            }
            emit(GridEvent.Share(uris, mimeTypes))
            exitSelectionMode()
        }
    }

    /** Cancels the download that precedes a share. */
    fun cancelSharePreparation() {
        shareJob?.cancel()
        shareJob = null
        _sharePreparation.value = null
    }

    /** Adds the selection to favorites, or removes it if all items already are. */
    fun toggleFavorite() {
        viewModelScope.launch {
            val ids = _selection.value
            if (ids.isEmpty()) return@launch
            val summaries = selectedSummaries(ids)
            val allFavorite = summaries.isNotEmpty() && summaries.all { it.isFavorite }
            val ok = withContext(deps.ioDispatcher) {
                runCatching { deps.favoritesRepository.setFavorite(ids, !allFavorite) }.isSuccess
            }
            when {
                !ok -> toast(R.string.common_error_generic)
                allFavorite -> toast(R.string.grid_favorites_removed)
                else -> toast(R.string.grid_favorites_added)
            }
            if (ok) exitSelectionMode()
        }
    }

    fun addToAlbum(albumId: Long) {
        viewModelScope.launch {
            val ids = _selection.value
            if (ids.isEmpty()) return@launch
            val ok = withContext(deps.ioDispatcher) {
                runCatching { deps.albumRepository.addToAlbum(albumId, ids) }.isSuccess
            }
            if (ok) {
                toast(R.string.grid_added_to_album)
                exitSelectionMode()
            } else {
                toast(R.string.common_error_generic)
            }
        }
    }

    /**
     * Prepares the system deletion request (trash or permanent depending on the settings).
     * A large selection is split into batches: all URIs of a request go through a single Binder
     * transaction (~1 MB). Each batch is still confirmed by the system dialog.
     */
    fun requestDelete() {
        viewModelScope.launch {
            if (pendingDeleteBatches.isNotEmpty()) return@launch
            // With confirmation, the dialog has already mentioned the OneDrive items left out.
            val local = localSelection(notifySkipped = settings.value?.confirmBeforeDelete == false)
            if (local.isEmpty()) return@launch
            startDeleteBatches(local.mapNotNull { it.uri }, toTrash = settings.value?.useTrash ?: true)
        }
    }

    private suspend fun startDeleteBatches(uris: List<Uri>, toTrash: Boolean) {
        if (pendingDeleteBatches.isNotEmpty()) return
        pendingDeleteToTrash = toTrash
        val batches = uris.chunked(MAX_DELETE_BATCH)
        if (batches.isEmpty()) return
        completedDeleteBatches = 0
        pendingDeleteBatches.addAll(batches)
        if (batches.size > 1) {
            emit(GridEvent.Message(GridMessage.Plural(R.plurals.grid_delete_batches, batches.size, listOf(batches.size))))
        }
        launchNextDeleteBatch()
    }

    // --- Secure Folder ---

    /**
     * "Move to Secure Folder": phone files of the selection (items only on OneDrive are skipped),
     * explanations then share sheet.
     */
    fun moveToSecureFolder() {
        viewModelScope.launch {
            val all = try {
                selectedSummaries()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                return@launch toast(R.string.common_error_generic)
            }
            val candidates = all.map { SecureFolderCandidate(it.id, isLocalActionTarget(it.hasLocal, it.uri != null), it.hasCloud) }
            when (val plan = planSecureFolderMove(candidates)) {
                SecureFolderPlan.NothingLocal -> toast(R.string.common_cloud_only_not_supported)
                is SecureFolderPlan.TooMany ->
                    emit(GridEvent.Message(GridMessage.Plural(R.plurals.secure_folder_too_many, plan.max, listOf(plan.max))))
                is SecureFolderPlan.Ready -> {
                    val byId = all.associateBy { it.id }
                    secureFolderMove.start(plan, uriOf = { byId[it]?.uri }, mimeTypeOf = { byId[it]?.mimeType })
                }
            }
        }
    }

    fun onSecureFolderShareLaunched() = secureFolderMove.onShareLaunched()

    fun onSecureFolderShareReturned() = secureFolderMove.onShareReturned()

    /** Cancelled before sending: the selection is kept. */
    fun cancelSecureFolder() = secureFolderMove.dismiss()

    /** Sent, originals kept. */
    fun keepOriginalsAfterSecureFolder() {
        secureFolderMove.dismiss()
        exitSelectionMode()
    }

    /**
     * Addition to Secure Folder confirmed by the user: originals sent to the Android trash (always
     * the trash, whatever the setting), one system request per batch.
     */
    fun trashAfterSecureFolder() {
        val moved = secureFolderMove.takeForTrash() ?: return
        viewModelScope.launch { startDeleteBatches(moved.uris, toTrash = true) }
    }

    private suspend fun launchNextDeleteBatch() {
        val batch = pendingDeleteBatches.removeFirstOrNull() ?: return finishDelete()
        val toTrash = pendingDeleteToTrash
        val sender = withContext(deps.ioDispatcher) {
            runCatching { deps.localMediaWriter.createDeleteRequest(batch, toTrash) }.getOrNull()
        }
        if (sender != null) {
            emit(GridEvent.LaunchDeleteRequest(sender))
        } else {
            pendingDeleteBatches.clear()
            toast(R.string.common_error_generic)
            finishDelete()
        }
    }

    private fun finishDelete() {
        if (completedDeleteBatches > 0) {
            exitSelectionMode()
            resync()
        }
        completedDeleteBatches = 0
    }

    fun onDeleteResult(granted: Boolean) {
        if (!granted) {
            // Refused: stop here (batches already confirmed stay deleted).
            pendingDeleteBatches.clear()
            finishDelete()
            return
        }
        completedDeleteBatches++
        if (pendingDeleteBatches.isEmpty()) {
            finishDelete()
        } else {
            viewModelScope.launch { launchNextDeleteBatch() }
        }
    }

    /** Copies the selected local files to "Pictures/<name>/". */
    fun copyToFolder(folderName: String) {
        val clean = sanitizeFolderName(folderName)
        if (clean.isEmpty()) {
            toast(R.string.grid_copy_invalid_name)
            return
        }
        viewModelScope.launch {
            // The input dialog has already mentioned the OneDrive items left out.
            val local = localSelection(notifySkipped = false)
            if (local.isEmpty()) return@launch
            val path = "Pictures/$clean/"
            val result = withContext(deps.ioDispatcher) {
                runCatching { deps.localMediaWriter.copyToFolder(local.mapNotNull { it.uri }, path) }
                    .getOrElse { Result.failure(it) }
            }
            result.onSuccess { copied ->
                emit(GridEvent.Message(GridMessage.Plural(R.plurals.grid_copy_done, copied, listOf(copied, path))))
                exitSelectionMode()
                resync()
            }.onFailure {
                toast(R.string.grid_copy_failed)
            }
        }
    }

    /** Restarts indexing after a write (survives the screen being closed). */
    protected fun resync() {
        deps.applicationScope.launch(deps.ioDispatcher) {
            runCatching { deps.localMediaIndexer.sync() }
        }
    }

    companion object {
        /** SQLite variable limit per query: ID lists are split into chunks. */
        private const val SQL_CHUNK = 500

        /** Beyond this, the share intent exceeds the maximum size of a Binder transaction. */
        const val MAX_SHARE = 500

        /** URIs per system deletion request (one Binder transaction per request). */
        const val MAX_DELETE_BATCH = 1_000

        /** Folder name safe for MediaStore (no separators or reserved characters). */
        fun sanitizeFolderName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").trim().trim('.').trim()
    }
}
