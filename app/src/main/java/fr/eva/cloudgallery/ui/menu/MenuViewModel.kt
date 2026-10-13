package fr.eva.cloudgallery.ui.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.eva.cloudgallery.domain.model.CloudAccountState
import fr.eva.cloudgallery.domain.model.CloudSyncState
import fr.eva.cloudgallery.domain.model.IndexState
import fr.eva.cloudgallery.domain.repository.CloudAccountRepository
import fr.eva.cloudgallery.domain.repository.LocalMediaIndexer
import fr.eva.cloudgallery.domain.repository.OfflineRepository
import fr.eva.cloudgallery.ui.main.MediaSyncCoordinator
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MenuViewModel @Inject constructor(
    indexer: LocalMediaIndexer,
    private val syncCoordinator: MediaSyncCoordinator,
    cloudAccounts: CloudAccountRepository,
    offline: OfflineRepository,
) : ViewModel() {

    val indexState: StateFlow<IndexState> = indexer.state

    /** OneDrive entry shown only if this build ships an MSAL configuration. */
    val cloudAvailable: Boolean = cloudAccounts.isAvailable

    val cloudState: StateFlow<CloudAccountState> = cloudAccounts.state

    /** OneDrive sync status (subtitle of the OneDrive entry, signed-in account). */
    val cloudSyncState: StateFlow<CloudSyncState> = cloudAccounts.syncState

    /**
     * Offline copies of items deleted from OneDrive awaiting a decision: flagged on
     * the OneDrive entry (the decision is made in the Account screen).
     */
    val deletedOfflineCopies: StateFlow<Int> = offline.deletedOnOneDrive
        .map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        // At process start the state is "loading": the MSAL account is read (I/O)
        // so that the subtitle does not stay frozen.
        val current = cloudAccounts.state.value
        if (cloudAvailable && (current is CloudAccountState.Loading || current is CloudAccountState.Error)) {
            viewModelScope.launch { cloudAccounts.refresh() }
        }
    }

    /** Immediate incremental sync (application scope: survives leaving the screen). */
    fun syncNow() = syncCoordinator.syncNow(full = false)
}
