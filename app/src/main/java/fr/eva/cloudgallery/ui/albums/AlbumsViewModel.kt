package fr.eva.cloudgallery.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.AlbumSort
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.MediaContext
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.repository.AlbumRepository
import fr.eva.cloudgallery.domain.repository.MediaRepository
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Quick-access entry (Favorites, Videos) presented as an album. */
data class QuickAlbum(
    val count: Int = 0,
    val cover: MediaSummary? = null,
)

data class AlbumsUiState(
    val loading: Boolean = true,
    val albums: List<AlbumSummary> = emptyList(),
    val favorites: QuickAlbum = QuickAlbum(),
    val videos: QuickAlbum = QuickAlbum(),
    val sort: AlbumSort = AlbumSort.CUSTOM,
    /** Selection mode active (it can be with 0 items, via "Select"). */
    val selectionMode: Boolean = false,
    val selection: Set<Long> = emptySet(),
) {
    val selectedAlbums: List<AlbumSummary> get() = albums.filter { it.id in selection }
    val allSelected: Boolean get() = AlbumsLogic.isAllSelected(selection, albums)
    val actions: AlbumSelectionActions get() = AlbumsLogic.selectionActions(selectedAlbums)
}

/** Internal selection state. */
internal data class AlbumSelection(val active: Boolean = false, val ids: Set<Long> = emptySet())

sealed interface AlbumsEvent {
    data object Error : AlbumsEvent
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlbumsViewModel @Inject constructor(
    private val albumRepository: AlbumRepository,
    private val mediaRepository: MediaRepository,
    private val settingsRepository: SettingsRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    private val selection = MutableStateFlow(AlbumSelection())
    private val eventChannel = Channel<AlbumsEvent>(Channel.BUFFERED)
    val events: Flow<AlbumsEvent> = eventChannel.receiveAsFlow()

    private val sort: Flow<AlbumSort> = settingsRepository.settings
        .map { it.albumSort }
        .distinctUntilChanged()

    private val albums: Flow<List<AlbumSummary>> = sort
        .flatMapLatest { albumRepository.observeAlbums(it) }
        .map { AlbumsLogic.visibleAlbums(it) }
        .flowOn(io)
        .catch {
            eventChannel.trySend(AlbumsEvent.Error)
            emit(emptyList())
        }

    val uiState: StateFlow<AlbumsUiState> = combine(
        albums,
        quickAlbum(MediaContext.Favorites),
        quickAlbum(MediaContext.Videos),
        sort,
        selection,
    ) { albums, favorites, videos, sort, selected ->
        AlbumsUiState(
            loading = false,
            albums = albums,
            favorites = favorites,
            videos = videos,
            sort = sort,
            selectionMode = selected.active,
            selection = AlbumsLogic.pruneSelection(selected.ids, albums),
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumsUiState())

    /** Item count + first thumbnail (most recent media item) of a context. */
    private fun quickAlbum(context: MediaContext): Flow<QuickAlbum> = combine(
        mediaRepository.observeCount(context),
        mediaRepository.observeMediaIds(context)
            .map { it.firstOrNull() }
            .mapLatest { id -> id?.let { mediaRepository.getSummaries(listOf(it)).firstOrNull() } },
    ) { count, cover -> QuickAlbum(count, cover) }
        .flowOn(io)
        .catch { emit(QuickAlbum()) }

    // --- Selection ---

    /** Enters selection mode, optionally selecting [albumId] (long press). */
    fun startSelection(albumId: Long? = null) = selection.update {
        AlbumSelection(active = true, ids = if (albumId != null) it.ids + albumId else it.ids)
    }

    fun toggleSelection(albumId: Long) = selection.update {
        it.copy(active = true, ids = AlbumsLogic.toggle(it.ids, albumId))
    }

    fun toggleSelectAll() = selection.update {
        it.copy(active = true, ids = AlbumsLogic.toggleAll(it.ids, uiState.value.albums))
    }

    fun clearSelection() = selection.update { AlbumSelection() }

    // --- Actions ---

    fun setSort(sort: AlbumSort) = launchSafe { settingsRepository.setAlbumSort(sort) }

    fun createAlbum(name: String) {
        val clean = AlbumsLogic.normalizeName(name) ?: return
        launchSafe { albumRepository.createVirtualAlbum(clean) }
    }

    /** Renames the selected album (display name only for a folder album). */
    fun renameSelected(name: String) {
        val clean = AlbumsLogic.normalizeName(name) ?: return
        val album = uiState.value.selectedAlbums.singleOrNull() ?: return
        clearSelection()
        launchSafe { albumRepository.renameAlbum(album.id, clean) }
    }

    fun togglePinSelected() {
        val selected = uiState.value.selectedAlbums
        if (selected.isEmpty()) return
        val pin = AlbumsLogic.selectionActions(selected).pinTarget
        clearSelection()
        launchSafe { selected.forEach { albumRepository.setPinned(it.id, pin) } }
    }

    /** Deletes the selected virtual albums; media items are never touched. */
    fun deleteSelected() {
        val selected = uiState.value.selectedAlbums
        if (!AlbumsLogic.selectionActions(selected).canDelete) return
        clearSelection()
        launchSafe {
            selected.filter { it.type == AlbumType.VIRTUAL }.forEach { albumRepository.deleteVirtualAlbum(it.id) }
        }
    }

    private fun launchSafe(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                withContext(io) { block() }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                eventChannel.trySend(AlbumsEvent.Error)
            }
        }
    }
}
