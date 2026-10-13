package fr.eva.cloudgallery.ui.photos

import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.MediaContext
import fr.eva.cloudgallery.ui.grid.MediaGridDeps
import fr.eva.cloudgallery.ui.grid.MediaGridViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** Timeline of all media (Photos tab). */
@HiltViewModel
class PhotosViewModel @Inject constructor(
    deps: MediaGridDeps,
) : MediaGridViewModel(MediaContext.Timeline, deps) {

    /** MediaStore indexing in progress (shows "Loading…" rather than an empty screen). */
    val indexing: StateFlow<Boolean> = deps.localMediaIndexer.state
        .map { it.running }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    override fun zoomLevelOf(settings: AppSettings): Int = settings.photosZoomLevel

    override suspend fun persistZoomLevel(level: Int) {
        deps.settingsRepository.setPhotosZoomLevel(level)
    }
}
