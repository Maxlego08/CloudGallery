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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
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

/** Dépendances communes des écrans de grille (regroupées pour alléger les constructeurs Hilt). */
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
    @param:IoDispatcher val ioDispatcher: CoroutineDispatcher,
    @param:ApplicationScope val applicationScope: CoroutineScope,
)

/**
 * Téléchargement depuis OneDrive avant un partage : [count] éléments, [done] / [total] octets
 * ([total] ≤ 0 si inconnu).
 */
data class GridSharePreparation(val count: Int, val done: Long, val total: Long)

/** Message ponctuel à afficher (toast), résolu dans l'UI. */
sealed interface GridMessage {
    data class Text(@StringRes val resId: Int, val args: List<Any> = emptyList()) : GridMessage
    data class Plural(@PluralsRes val resId: Int, val count: Int, val args: List<Any> = emptyList()) : GridMessage
}

/** Evénements ponctuels VM -> UI. */
sealed interface GridEvent {
    data class Share(val uris: List<Uri>, val mimeTypes: List<String?>) : GridEvent
    data class LaunchDeleteRequest(val intentSender: IntentSender) : GridEvent
    data class Message(val message: GridMessage) : GridEvent
}

/**
 * Base commune des grilles (Photos, albums, favoris, vidéos) : données paginées avec en-têtes,
 * niveau de zoom persistant, sélection et actions sur la sélection.
 */
abstract class MediaGridViewModel(
    val mediaContext: MediaContext,
    protected val deps: MediaGridDeps,
) : ViewModel() {

    /** Niveau de zoom enregistré pour cet écran. */
    protected abstract fun zoomLevelOf(settings: AppSettings): Int

    protected abstract suspend fun persistZoomLevel(level: Int)

    private val zone: ZoneId = ZoneId.systemDefault()

    val settings: StateFlow<AppSettings?> = deps.settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Valeur locale appliquée immédiatement, avant l'aller-retour DataStore. */
    private val zoomOverride = MutableStateFlow<Int?>(null)

    val zoomLevel: StateFlow<Int?> = combine(settings, zoomOverride) { s, override ->
        override ?: s?.let { GridZoomLevels.clamp(zoomLevelOf(it)) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val grouping: Flow<GridGrouping> = zoomLevel.filterNotNull()
        .map { GridZoomLevels.grouping(it) }
        .distinctUntilChanged()

    /**
     * Portée des caches de pagination : les transformations (conversion, insertion des en-têtes
     * sur toutes les pages en cache lors d'un changement de regroupement) tournent hors du thread
     * principal ; l'UI collecte ensuite le résultat sur le thread principal.
     */
    private val pagingScope = CoroutineScope(viewModelScope.coroutineContext + Dispatchers.Default)

    private val basePaging: Flow<PagingData<GridItem.Media>> = deps.mediaRepository.pagedMedia(mediaContext)
        .map { data -> data.map { GridItem.Media(it) } }
        .cachedIn(pagingScope)

    /**
     * Grille paginée avec en-têtes de jour / mois. La pagination de base est mise en cache :
     * un changement de regroupement ne recharge pas les pages, il réinsère seulement les en-têtes.
     */
    val gridItems: Flow<PagingData<GridItem>> = combine(basePaging, grouping) { data, g ->
        val withHeaders: PagingData<GridItem> = data.insertSeparators { before: GridItem.Media?, after: GridItem.Media? ->
            headerBetween(before?.media, after?.media, g, zone)
        }
        withHeaders
    }.flowOn(Dispatchers.Default).cachedIn(pagingScope)

    val count: StateFlow<Int?> = deps.mediaRepository.observeCount(mediaContext)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _selection = MutableStateFlow<Set<Long>>(emptySet())
    val selection: StateFlow<Set<Long>> = _selection.asStateFlow()

    private val _selectionMode = MutableStateFlow(false)
    val selectionMode: StateFlow<Boolean> = _selectionMode.asStateFlow()

    val allSelected: StateFlow<Boolean> = combine(_selection, count) { sel, c ->
        c != null && c > 0 && sel.size >= c
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /**
     * Fichiers locaux de la sélection (comptés en base, sur le thread d'E/S) : active ou non
     * Partager / Supprimer / Copier et donne les nombres exacts des boîtes de confirmation.
     * null si inconnu (échec de lecture) : les actions restent possibles et filtrent elles-mêmes.
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

    /** Boîte de dialogue d'une action hors ligne (gros lot, attente du Wi-Fi, espace, dernière copie). */
    val offlinePrompt: StateFlow<OfflinePrompt?> = _offlinePrompt.asStateFlow()

    private val _sharePreparation = MutableStateFlow<GridSharePreparation?>(null)

    /** Téléchargement en cours avant un partage (null sinon). */
    val sharePreparation: StateFlow<GridSharePreparation?> = _sharePreparation.asStateFlow()
    private var shareJob: Job? = null

    /**
     * Identifiants complets (base de données) des jours dont l'en-tête a été touché : la pagination
     * ne charge qu'une partie d'un grand jour, l'état de la case ne peut donc pas en dépendre seul.
     */
    private val _dayIds = MutableStateFlow<Map<Long, List<Long>>>(emptyMap())
    val dayIds: StateFlow<Map<Long, List<Long>>> = _dayIds.asStateFlow()

    /** Suppression découpée en lots (une confirmation système par lot). */
    private val pendingDeleteBatches = ArrayDeque<List<Uri>>()
    private var pendingDeleteToTrash = true
    private var completedDeleteBatches = 0

    init {
        // Contenu modifié (ajout, suppression) : les listes par jour en cache ne sont plus fiables.
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

    // --- Sélection ---

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

    /** Appui long : entre en mode sélection avec cet élément, ou bascule son état. */
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
     * En-tête de jour touché en mode sélection : sélectionne (ou désélectionne si tout l'est déjà)
     * TOUS les médias du jour, lus en base, y compris ceux que la pagination n'a pas encore chargés.
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
     * Fin d'un glisser de sélection : la plage entre [anchor] et [end] est résolue en base pour
     * inclure les éléments que la pagination n'avait pas encore chargés pendant le geste.
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
     * Médias sélectionnés disposant d'un fichier local ([localActionTargets]). Les éléments
     * uniquement sur OneDrive ne sont jamais traités : message si aucun fichier local, et
     * nombre d'éléments ignorés si [notifySkipped] (déjà indiqué par une boîte de confirmation sinon).
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

    // --- Copies hors ligne ---

    /** « Conserver hors ligne » pour la sélection (gros lot : confirmation d'abord). */
    fun keepOffline() = keep(_selection.value.toList(), confirmed = false)

    /** Gros lot confirmé par l'utilisateur. */
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

    /** « Télécharger maintenant » : la file en attente du Wi-Fi utilise le réseau mobile. */
    fun downloadOfflineNow() {
        viewModelScope.launch { runCatching { deps.offline.downloadNow() } }
    }

    /** « Libérer l'espace » : immédiat, sauf s'il y a des dernières copies (confirmation). */
    fun releaseOffline() {
        val ids = _selection.value.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            // Compte impossible : rien n'est supprimé (une dernière copie l'aurait été sans confirmation).
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

    /** Libération de dernières copies confirmée. */
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

    /** L'autorisation des notifications a été demandée (une seule fois). */
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
     * Partage la sélection : fichiers locaux et copies hors ligne tels quels, éléments uniquement
     * sur OneDrive après téléchargement de leur original (compte connecté, au plus
     * [MAX_SHARE_CLOUD_ITEMS] éléments et [MAX_SHARE_CLOUD_BYTES] octets, progression annulable).
     */
    fun share() {
        if (shareJob?.isActive == true) return
        shareJob = viewModelScope.launch {
            val all = selectedSummaries()
            val local = localActionTargets(all)
            val nonLocal = all.filter { !isLocalActionTarget(it.hasLocal, it.uri != null) }
            // Copies hors ligne intactes : partagées sans téléchargement ; sinon repli sur OneDrive.
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
                // Refus avant de lire les détails des éléments.
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

    /** Annule le téléchargement qui précède un partage. */
    fun cancelSharePreparation() {
        shareJob?.cancel()
        shareJob = null
        _sharePreparation.value = null
    }

    /** Ajoute la sélection aux favoris, ou l'en retire si tous les éléments y sont déjà. */
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
     * Prépare la demande système de suppression (corbeille ou définitive selon les réglages).
     * Une grande sélection est découpée en lots : toutes les URI d'une demande transitent dans une
     * seule transaction Binder (~1 Mo). Chaque lot reste confirmé par la boîte de dialogue système.
     */
    fun requestDelete() {
        viewModelScope.launch {
            if (pendingDeleteBatches.isNotEmpty()) return@launch
            // Avec confirmation, la boîte de dialogue a déjà indiqué les éléments OneDrive non concernés.
            val local = localSelection(notifySkipped = settings.value?.confirmBeforeDelete == false)
            if (local.isEmpty()) return@launch
            pendingDeleteToTrash = settings.value?.useTrash ?: true
            val batches = local.mapNotNull { it.uri }.chunked(MAX_DELETE_BATCH)
            if (batches.isEmpty()) return@launch
            completedDeleteBatches = 0
            pendingDeleteBatches.addAll(batches)
            if (batches.size > 1) {
                emit(GridEvent.Message(GridMessage.Plural(R.plurals.grid_delete_batches, batches.size, listOf(batches.size))))
            }
            launchNextDeleteBatch()
        }
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
            // Refus : on s'arrête là (les lots déjà confirmés restent supprimés).
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

    /** Copie les fichiers locaux sélectionnés vers « Pictures/<nom>/ ». */
    fun copyToFolder(folderName: String) {
        val clean = sanitizeFolderName(folderName)
        if (clean.isEmpty()) {
            toast(R.string.grid_copy_invalid_name)
            return
        }
        viewModelScope.launch {
            // La boîte de saisie a déjà indiqué les éléments OneDrive non concernés.
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

    /** Relance l'indexation après une écriture (survit à la fermeture de l'écran). */
    protected fun resync() {
        deps.applicationScope.launch(deps.ioDispatcher) {
            runCatching { deps.localMediaIndexer.sync() }
        }
    }

    companion object {
        /** Limite de variables SQLite par requête : on découpe les listes d'identifiants. */
        private const val SQL_CHUNK = 500

        /** Au-delà, l'intent de partage dépasse la taille maximale d'une transaction Binder. */
        const val MAX_SHARE = 500

        /** URI par demande de suppression système (une transaction Binder par demande). */
        const val MAX_DELETE_BATCH = 1_000

        /** Nom de dossier sûr pour MediaStore (pas de séparateurs ni caractères réservés). */
        fun sanitizeFolderName(name: String): String =
            name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "").trim().trim('.').trim()
    }
}
