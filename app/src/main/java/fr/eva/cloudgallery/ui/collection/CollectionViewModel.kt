package fr.eva.cloudgallery.ui.collection

import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.MediaContext
import fr.eva.cloudgallery.ui.grid.MediaGridDeps
import fr.eva.cloudgallery.ui.grid.MediaGridViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Grid of a collection (album, Favorites, Videos). The context is provided by the screen
 * through Hilt assisted injection.
 */
@HiltViewModel(assistedFactory = CollectionViewModel.Factory::class)
class CollectionViewModel @AssistedInject constructor(
    @Assisted context: MediaContext,
    deps: MediaGridDeps,
) : MediaGridViewModel(context, deps) {

    @AssistedFactory
    interface Factory {
        fun create(context: MediaContext): CollectionViewModel
    }

    /** Displayed album (null for Favorites / Videos, or if the album has disappeared). */
    val album: StateFlow<AlbumSummary?> =
        ((context as? MediaContext.Album)?.let { deps.albumRepository.observeAlbum(it.albumId) } ?: flowOf(null))
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val oneDriveLinkChannel = Channel<String>(Channel.BUFFERED)

    /** Web link of the OneDrive folder to open ("Open in OneDrive"). */
    val oneDriveLinks: Flow<String> = oneDriveLinkChannel.receiveAsFlow()

    /** OneDrive album: requests the web link of its folder (Graph request). */
    fun openInOneDrive() {
        val albumId = (mediaContext as? MediaContext.Album)?.albumId ?: return
        viewModelScope.launch {
            val url = runCatching { deps.albumRepository.oneDriveFolderUrl(albumId) }.getOrNull()
            if (url != null) oneDriveLinkChannel.send(url) else toast(R.string.albums_open_onedrive_failed)
        }
    }

    override fun zoomLevelOf(settings: AppSettings): Int = settings.albumZoomLevel

    override suspend fun persistZoomLevel(level: Int) {
        deps.settingsRepository.setAlbumZoomLevel(level)
    }

    /** Removes the selection from a virtual album (references only, no file deleted). */
    fun removeFromAlbum() {
        val albumId = (mediaContext as? MediaContext.Album)?.albumId ?: return
        viewModelScope.launch {
            val ids = selection.value
            if (ids.isEmpty()) return@launch
            val ok = withContext(deps.ioDispatcher) {
                runCatching { deps.albumRepository.removeFromAlbum(albumId, ids) }.isSuccess
            }
            if (ok) {
                toast(R.string.collection_removed_from_album)
                exitSelectionMode()
            } else {
                toast(R.string.common_error_generic)
            }
        }
    }

    /** Removes the selection from favorites (Favorites screen). */
    fun removeFromFavorites() {
        viewModelScope.launch {
            val ids = selection.value
            if (ids.isEmpty()) return@launch
            val ok = withContext(deps.ioDispatcher) {
                runCatching { deps.favoritesRepository.setFavorite(ids, false) }.isSuccess
            }
            if (ok) {
                toast(R.string.grid_favorites_removed)
                exitSelectionMode()
            } else {
                toast(R.string.common_error_generic)
            }
        }
    }
}
