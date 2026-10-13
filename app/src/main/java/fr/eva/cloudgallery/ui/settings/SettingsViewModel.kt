package fr.eva.cloudgallery.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.eva.cloudgallery.domain.model.AppSettings
import fr.eva.cloudgallery.domain.model.BackgroundSyncInterval
import fr.eva.cloudgallery.domain.model.CloudAccountState
import fr.eva.cloudgallery.domain.model.CloudSyncState
import fr.eva.cloudgallery.domain.model.IndexState
import fr.eva.cloudgallery.domain.model.ThemeMode
import fr.eva.cloudgallery.domain.repository.CloudAccountRepository
import fr.eva.cloudgallery.domain.repository.LocalMediaIndexer
import fr.eva.cloudgallery.domain.repository.OfflineRepository
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import fr.eva.cloudgallery.ui.main.MediaSyncCoordinator
import kotlinx.coroutines.CancellationException
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
    private val cloudAccounts: CloudAccountRepository,
    private val offline: dagger.Lazy<OfflineRepository>,
) : ViewModel() {

    /** null until the settings are loaded (avoids showing misleading default values). */
    val settings: StateFlow<AppSettings?> = settingsRepository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val indexState: StateFlow<IndexState> = indexer.state

    /** "Account" category shown only if this build ships an MSAL configuration. */
    val cloudAvailable: Boolean = cloudAccounts.isAvailable

    val cloudState: StateFlow<CloudAccountState> = cloudAccounts.state

    /** OneDrive sync status (status row of the "Account" category). */
    val cloudSyncState: StateFlow<CloudSyncState> = cloudAccounts.syncState

    init {
        val current = cloudAccounts.state.value
        if (cloudAvailable && (current is CloudAccountState.Loading || current is CloudAccountState.Error)) {
            viewModelScope.launch { cloudAccounts.refresh() }
        }
    }

    fun setThemeMode(mode: ThemeMode) = update { settingsRepository.setThemeMode(mode) }
    fun setShowVideoDuration(show: Boolean) = update { settingsRepository.setShowVideoDuration(show) }
    fun setAutoPlayVideos(enabled: Boolean) = update { settingsRepository.setAutoPlayVideos(enabled) }
    fun setUseTrash(enabled: Boolean) = update { settingsRepository.setUseTrash(enabled) }
    fun setConfirmBeforeDelete(enabled: Boolean) = update { settingsRepository.setConfirmBeforeDelete(enabled) }
    fun setShowCloudInPhotos(enabled: Boolean) = update { settingsRepository.setShowCloudInPhotos(enabled) }
    fun setLoadOriginalsOnMobileData(enabled: Boolean) = update { settingsRepository.setLoadOriginalsOnMobileData(enabled) }

    /** Background sync: the periodic work follows the setting (BackgroundSyncScheduler). */
    fun setBackgroundSyncInterval(interval: BackgroundSyncInterval) = update { settingsRepository.setBackgroundSyncInterval(interval) }
    fun setBackgroundSyncWifiOnly(enabled: Boolean) = update { settingsRepository.setBackgroundSyncWifiOnly(enabled) }
    /**
     * "Wi-Fi only": the running or scheduled download work is adjusted right away
     * (app visible: rescheduling accepted by the system).
     */
    fun setOfflineWifiOnly(enabled: Boolean) = update {
        settingsRepository.setOfflineWifiOnly(enabled)
        try {
            offline.get().onNetworkPolicyChanged()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Resumed on the next return to the foreground or the next offline action.
        }
    }

    /** Full MediaStore reload (application scope). */
    fun reindexAll() = syncCoordinator.syncNow(full = true)

    private fun update(block: suspend () -> Unit) {
        viewModelScope.launch { block() }
    }
}
