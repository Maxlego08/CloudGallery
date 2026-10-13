package fr.eva.cloudgallery.ui.storage

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.PrecacheEstimate
import fr.eva.cloudgallery.domain.model.PrecacheState
import fr.eva.cloudgallery.domain.model.StorageUsage
import fr.eva.cloudgallery.domain.repository.OneDrivePrecacheRepository
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import fr.eva.cloudgallery.domain.repository.StorageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
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

/** Issue d'un vidage du cache ou d'une estimation, affichée en Toast. */
enum class StorageEvent { CLEARED, CLEAR_FAILED, ESTIMATE_FAILED }

/** Boîtes de la mise en cache de tout OneDrive. */
sealed interface PrecacheDialog {
    data object Estimating : PrecacheDialog
    data class Confirm(val estimate: PrecacheEstimate) : PrecacheDialog
    data object ConfirmCancel : PrecacheDialog
}

@HiltViewModel
class StorageViewModel @Inject constructor(
    private val storage: StorageRepository,
    private val settingsRepository: SettingsRepository,
    private val precache: OneDrivePrecacheRepository,
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

    /** Mise en cache de tout OneDrive : passage en cours ou dernier bilan. */
    val precacheState: StateFlow<PrecacheState> = precache.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrecacheState.Idle)

    private val _precacheDialog = MutableStateFlow<PrecacheDialog?>(null)

    /** Boîte de la mise en cache affichée (estimation, confirmation, arrêt), null sinon. */
    val precacheDialog: StateFlow<PrecacheDialog?> = _precacheDialog.asStateFlow()

    private var estimateJob: Job? = null

    /** « Mettre en cache tout OneDrive » : estimation puis confirmation ; passage en cours : proposer l'arrêt. */
    fun onPrecacheClick() {
        if (_precacheDialog.value != null) return
        when (precacheState.value) {
            is PrecacheState.Running, PrecacheState.Waiting -> _precacheDialog.value = PrecacheDialog.ConfirmCancel
            else -> estimate()
        }
    }

    private fun estimate() {
        _precacheDialog.value = PrecacheDialog.Estimating
        estimateJob = viewModelScope.launch {
            _precacheDialog.value = try {
                PrecacheDialog.Confirm(precache.estimate())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Estimation de la mise en cache impossible", e)
                eventChannel.trySend(StorageEvent.ESTIMATE_FAILED)
                null
            }
        }
    }

    fun dismissPrecacheDialog() {
        estimateJob?.cancel()
        estimateJob = null
        _precacheDialog.value = null
    }

    fun confirmPrecache() {
        _precacheDialog.value = null
        precache.start()
    }

    fun confirmCancelPrecache() {
        _precacheDialog.value = null
        precache.cancel()
    }

    fun setPrecacheKeepUpdated(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setPrecacheKeepUpdated(enabled)
            // Activé : un passage tout de suite (il ne télécharge que ce qui manque).
            if (enabled) precache.start()
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
