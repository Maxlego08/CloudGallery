package fr.eva.cloudgallery.ui.menu

import androidx.lifecycle.ViewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import fr.eva.cloudgallery.domain.model.IndexState
import fr.eva.cloudgallery.domain.repository.LocalMediaIndexer
import fr.eva.cloudgallery.ui.main.MediaSyncCoordinator
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class MenuViewModel @Inject constructor(
    indexer: LocalMediaIndexer,
    private val syncCoordinator: MediaSyncCoordinator,
) : ViewModel() {

    val indexState: StateFlow<IndexState> = indexer.state

    /** Synchro incrémentale immédiate (portée applicative : survit à la sortie de l'écran). */
    fun syncNow() = syncCoordinator.syncNow(full = false)
}
