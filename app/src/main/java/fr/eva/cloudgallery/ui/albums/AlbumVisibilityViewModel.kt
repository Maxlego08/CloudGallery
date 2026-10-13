package fr.eva.cloudgallery.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AlbumVisibility
import fr.eva.cloudgallery.domain.repository.AlbumRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** « Masquer des albums » : tous les albums, masqués compris, et leur affichage. */
@HiltViewModel
class AlbumVisibilityViewModel @Inject constructor(
    private val albumRepository: AlbumRepository,
) : ViewModel() {

    /** Null pendant le premier chargement. */
    val albums: StateFlow<List<AlbumSummary>?> = albumRepository.observeAllAlbums()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun setVisibility(albumId: Long, visibility: AlbumVisibility) {
        viewModelScope.launch { albumRepository.setVisibility(albumId, visibility) }
    }
}
