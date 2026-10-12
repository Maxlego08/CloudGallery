package fr.eva.cloudgallery.ui.storage

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.StorageUsage
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import fr.eva.cloudgallery.domain.repository.StorageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Issue d'un vidage du cache, affichée en Toast. */
enum class StorageEvent { CLEARED, CLEAR_FAILED }

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val storage: StorageRepository,
    private val settingsRepository: SettingsRepository,
) : ViewModel() {

    /** null tant que les réglages ne sont pas chargés. */
    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _usage = MutableStateFlow<StorageUsage?>(null)

    /** Dernière mesure, null avant la première. */
    val usage: StateFlow<StorageUsage?> = _usage.asStateFlow()

    private val _clearing = MutableStateFlow(false)
    val clearing: StateFlow<Boolean> = _clearing.asStateFlow()

    private val eventChannel = Channel<StorageEvent>(Channel.BUFFERED)
    val events: Flow<StorageEvent> = eventChannel.receiveAsFlow()

    /** Nouvelle mesure (ouverture et retour sur la page, après une action). */
    fun refresh() {
        viewModelScope.launch { measure() }
    }

    fun setOriginalsCap(bytes: Long) {
        viewModelScope.launch {
            try {
                settingsRepository.setOriginalsCacheMaxBytes(bytes)
                storage.applyOriginalsCap()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Plafond appliqué au prochain nettoyage (démarrage ou téléchargement).
                Log.w(TAG, "Plafond des originaux non appliqué aussitôt", e)
            }
            measure()
        }
    }

    fun setThumbnailCap(bytes: Long) {
        viewModelScope.launch { settingsRepository.setThumbnailCacheMaxBytes(bytes) }
    }

    fun clearCache() {
        if (_clearing.value) return
        _clearing.value = true
        viewModelScope.launch {
            val event = try {
                storage.clearCache()
                StorageEvent.CLEARED
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Vidage du cache incomplet", e)
                StorageEvent.CLEAR_FAILED
            } finally {
                _clearing.value = false
            }
            eventChannel.trySend(event)
            measure()
        }
    }

    private suspend fun measure() {
        try {
            _usage.value = storage.usage()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Mesure de l'espace impossible", e)
        }
    }

    private companion object {
        const val TAG = "StorageViewModel"
    }
}
