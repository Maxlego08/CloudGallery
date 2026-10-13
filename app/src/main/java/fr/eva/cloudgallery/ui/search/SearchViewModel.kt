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
 * Saisie de la recherche et suggestions (albums correspondants, recherches récentes). La
 * recherche lancée ([submitted]) affiche les résultats ; la saisie et la recherche lancée
 * survivent à la recréation de l'écran.
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val savedState: SavedStateHandle,
    private val history: SearchHistoryRepository,
    albumRepository: AlbumRepository,
) : ViewModel() {

    val input: StateFlow<String> = savedState.getStateFlow(KEY_INPUT, "")

    /** Recherche dont les résultats sont affichés, null pendant la saisie. */
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

    /** Lance la recherche [text] (sans effet sans aucun mot) et l'ajoute à l'historique. */
    fun submit(text: String = input.value) {
        if (!canSubmitSearch(text)) return
        val query = submittedQuery(text)
        savedState[KEY_INPUT] = query
        savedState[KEY_SUBMITTED] = query
        guarded { history.add(query) }
    }

    /** Retour à la saisie (toucher du champ pendant l'affichage des résultats). */
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
                // L'historique n'est qu'une commodité : la recherche fonctionne sans lui.
                Log.w(TAG, "Historique de recherche indisponible", e)
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
 * Résultats d'une recherche ([MediaContext.Search]) dans la grille commune : sélection, partage,
 * favoris, albums… comme les autres grilles. Zoom partagé avec Photos.
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
