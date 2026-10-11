package fr.eva.cloudgallery.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.IndexState
import fr.eva.cloudgallery.domain.model.ThemeMode
import fr.eva.cloudgallery.domain.repository.LocalMediaIndexer
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import fr.eva.cloudgallery.ui.main.MediaSyncCoordinator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    indexer: LocalMediaIndexer,
    private val syncCoordinator: MediaSyncCoordinator,
) : ViewModel() {

    /** null tant que les réglages ne sont pas chargés (évite d'afficher des valeurs par défaut erronées). */
    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val indexState: StateFlow<IndexState> = indexer.state

    fun setThemeMode(mode: ThemeMode) = update { settingsRepository.setThemeMode(mode) }
    fun setShowVideoDuration(show: Boolean) = update { settingsRepository.setShowVideoDuration(show) }
    fun setAutoPlayVideos(enabled: Boolean) = update { settingsRepository.setAutoPlayVideos(enabled) }
    fun setUseTrash(enabled: Boolean) = update { settingsRepository.setUseTrash(enabled) }
    fun setConfirmBeforeDelete(enabled: Boolean) = update { settingsRepository.setConfirmBeforeDelete(enabled) }

    /** Relecture complète de MediaStore (portée applicative). */
    fun reindexAll() = syncCoordinator.syncNow(full = true)

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
