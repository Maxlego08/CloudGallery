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

/** Outcome of a cache clear or an estimate, shown as a Toast. */
enum class StorageEvent { CLEARED, CLEAR_FAILED, ESTIMATE_FAILED }

/** Dialogs for caching all of OneDrive. */
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

    /** null until the settings are loaded. */
    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _usage = MutableStateFlow<StorageUsage?>(null)

    /** Latest measurement, null before the first one. */
    val usage: StateFlow<StorageUsage?> = _usage.asStateFlow()

    private val _clearing = MutableStateFlow(false)
    val clearing: StateFlow<Boolean> = _clearing.asStateFlow()

    private val eventChannel = Channel<StorageEvent>(Channel.BUFFERED)
    val events: Flow<StorageEvent> = eventChannel.receiveAsFlow()

    /** New measurement (on opening and returning to the page, after an action). */
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
                // Limit applied at the next cleanup (launch or download).
                Log.w(TAG, "Originals cache limit not applied immediately", e)
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
                Log.w(TAG, "Cache clear incomplete", e)
                StorageEvent.CLEAR_FAILED
            } finally {
                _clearing.value = false
            }
            eventChannel.trySend(event)
            measure()
        }
    }

    /** Caching all of OneDrive: pass in progress or latest summary. */
    val precacheState: StateFlow<PrecacheState> = precache.state
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), PrecacheState.Idle)

    private val _precacheDialog = MutableStateFlow<PrecacheDialog?>(null)

    /** Caching dialog shown (estimate, confirmation, stop), null otherwise. */
    val precacheDialog: StateFlow<PrecacheDialog?> = _precacheDialog.asStateFlow()

    private var estimateJob: Job? = null

    /** "Cache all of OneDrive": estimate then confirmation; pass in progress: offer to stop it. */
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
                Log.w(TAG, "Unable to estimate caching", e)
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
            // Enabled: one pass right away (it only downloads what is missing).
            if (enabled) precache.start()
        }
    }

    private suspend fun measure() {
        try {
            _usage.value = storage.usage()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Unable to measure space", e)
        }
    }

    private companion object {
        const val TAG = "StorageViewModel"
    }
}
