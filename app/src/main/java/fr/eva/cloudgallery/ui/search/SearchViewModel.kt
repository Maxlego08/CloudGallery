package fr.eva.cloudgallery.ui.search

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.eva.cloudgallery.domain.model.AlbumSort
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.MediaContext
import fr.eva.cloudgallery.domain.repository.AlbumRepository
import fr.eva.cloudgallery.domain.repository.SearchHistoryRepository
import fr.eva.cloudgallery.ui.grid.MediaGridDeps
import fr.eva.cloudgallery.ui.grid.MediaGridViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Search input and suggestions (matching albums, recent searches). The submitted search
 * ([submitted]) shows the results; the input and the submitted search survive screen
 * recreation.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val history: SearchHistoryRepository,
    albumRepository: AlbumRepository,
) : ViewModel() {

    val input: StateFlow<String> = savedState.getStateFlow(KEY_INPUT, "")

    /** Search whose results are shown, null while typing. */
    val submitted: StateFlow<String?> = savedState.getStateFlow<String?>(KEY_SUBMITTED, null)

    val recent: StateFlow<List<String>> = combine(history.recent, input) { list, text -> recentSuggestions(list, text) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val albums: StateFlow<List<AlbumSummary>> =
        combine(albumRepository.observeAlbums(AlbumSort.NAME), input.debounce(INPUT_DEBOUNCE_MS)) { all, text ->
            albumSuggestions(all, text)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onInputChange(text: String) {
        savedState[KEY_INPUT] = text
    }

    /** Submits the search [text] (no effect without any word) and adds it to the history. */
    fun submit(text: String = input.value) {
        if (!canSubmitSearch(text)) return
        val query = submittedQuery(text)
        savedState[KEY_INPUT] = query
        savedState[KEY_SUBMITTED] = query
        guarded { history.add(query) }
    }

    /** Back to typing (field tapped while results are shown). */
    fun edit() {
        savedState[KEY_SUBMITTED] = null
    }

    fun removeRecent(query: String) = guarded { history.remove(query) }

    fun clearRecent() = guarded { history.clear() }

    private fun guarded(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The history is only a convenience: search works without it.
                Log.w(TAG, "Search history unavailable", e)
            }
        }
    }

    private companion object {
        const val TAG = "SearchViewModel"
        const val KEY_INPUT = "search_input"
        const val KEY_SUBMITTED = "search_submitted"
        const val INPUT_DEBOUNCE_MS = 150L
    }
}

/**
 * Results of a search ([MediaContext.Search]) in the shared grid: selection, sharing,
 * favorites, albums… like the other grids. Zoom shared with Photos.
 */
@HiltViewModel(assistedFactory = SearchResultsViewModel.Factory::class)
class SearchResultsViewModel @AssistedInject constructor(
    @Assisted context: MediaContext,
    deps: MediaGridDeps,
) : MediaGridViewModel(context, deps) {

    @AssistedFactory
    interface Factory {
        fun create(context: MediaContext): SearchResultsViewModel
    }

    override fun zoomLevelOf(settings: AppSettings): Int = settings.photosZoomLevel

    override suspend fun persistZoomLevel(level: Int) {
        deps.settingsRepository.setPhotosZoomLevel(level)
    }
}
