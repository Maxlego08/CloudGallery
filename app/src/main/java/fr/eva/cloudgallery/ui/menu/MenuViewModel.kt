package fr.eva.cloudgallery.ui.menu

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.eva.cloudgallery.domain.model.CloudAccountState
import fr.eva.cloudgallery.domain.model.CloudSyncState
import fr.eva.cloudgallery.domain.model.IndexState
import fr.eva.cloudgallery.domain.repository.CloudAccountRepository
import fr.eva.cloudgallery.domain.repository.LocalMediaIndexer
import fr.eva.cloudgallery.ui.main.MediaSyncCoordinator
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class MenuViewModel @Inject constructor(
    indexer: LocalMediaIndexer,
    private val syncCoordinator: MediaSyncCoordinator,
    cloudAccounts: CloudAccountRepository,
) : ViewModel() {

    val indexState: StateFlow<IndexState> = indexer.state

    /** Entrée OneDrive affichée uniquement si ce build embarque une configuration MSAL. */
    val cloudAvailable: Boolean = cloudAccounts.isAvailable

    val cloudState: StateFlow<CloudAccountState> = cloudAccounts.state

    /** Etat de la synchronisation OneDrive (sous-titre de l'entrée OneDrive, compte connecté). */
    val cloudSyncState: StateFlow<CloudSyncState> = cloudAccounts.syncState

    init {
        // Au démarrage du processus l'état est « chargement » : lecture du compte MSAL (E/S)
        // pour que le sous-titre ne reste pas figé.
        val current = cloudAccounts.state.value
        if (cloudAvailable && (current is CloudAccountState.Loading || current is CloudAccountState.Error)) {
            viewModelScope.launch { cloudAccounts.refresh() }
        }
    }

    /** Synchro incrémentale immédiate (portée applicative : survit à la sortie de l'écran). */
    fun syncNow() = syncCoordinator.syncNow(full = false)
}
