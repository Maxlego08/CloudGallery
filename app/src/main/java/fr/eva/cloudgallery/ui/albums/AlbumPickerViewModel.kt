package fr.eva.cloudgallery.ui.albums

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.repository.AlbumRepository
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class AlbumPickerUiState(
    val loading: Boolean = true,
    /** Custom albums only: we only add references, never into a folder. */
    val albums: List<AlbumSummary> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class AlbumPickerViewModel @Inject constructor(
    private val albumRepository: AlbumRepository,
    settingsRepository: SettingsRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : ViewModel() {

    val uiState: StateFlow<AlbumPickerUiState> = settingsRepository.settings
        .map { it.albumSort }
        .distinctUntilChanged()
        .flatMapLatest { albumRepository.observeAlbums(it) }
        .map { albums -> AlbumPickerUiState(loading = false, albums = albums.filter { it.type == AlbumType.VIRTUAL }) }
        .flowOn(io)
        .catch { emit(AlbumPickerUiState(loading = false)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AlbumPickerUiState())

    /**
     * Creates a custom album then calls [onCreated] (on the main thread) with its id,
     * or [onError] on failure.
     */
    fun createAlbum(name: String, onCreated: (Long) -> Unit, onError: () -> Unit) {
        val clean = AlbumsLogic.normalizeName(name) ?: return
        viewModelScope.launch {
            val id = try {
                withContext(io) { albumRepository.createVirtualAlbum(clean) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                null
            }
            if (id != null) onCreated(id) else onError()
        }
    }
}
