package fr.eva.cloudgallery.ui.main

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.CloudSyncReason
import fr.eva.cloudgallery.domain.repository.CloudMediaSyncer
import fr.eva.cloudgallery.domain.repository.LocalMediaIndexer
import fr.eva.cloudgallery.ui.permissions.MediaAccess
import fr.eva.cloudgallery.ui.permissions.MediaPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Point unique de déclenchement de l'indexation locale : au passage au premier plan, à
 * l'obtention de l'autorisation, ou à la demande (Menu / Paramètres). Ne fait rien sans accès
 * aux médias. Les synchronisations ne se chevauchent pas ; une demande complète pendant une
 * synchro incrémentale est enchaînée après elle.
 *
 * La synchronisation OneDrive est demandée en parallèle (premier plan, « Synchroniser
 * maintenant »), indépendamment de l'accès aux médias locaux ; elle ne s'exécute que pour un
 * compte connecté et se limite elle-même au premier plan.
 */
@Singleton
class MediaSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val indexer: dagger.Lazy<LocalMediaIndexer>,
    private val cloudSync: dagger.Lazy<CloudMediaSyncer>,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private var syncJob: Job? = null

    fun hasMediaAccess(): Boolean = MediaPermissions.currentAccess(context) != MediaAccess.NONE

    /** Appli revenue au premier plan : synchro incrémentale (locale et OneDrive). */
    fun onForeground() {
        syncIfAllowed(full = false)
        cloudSync.get().requestSync(CloudSyncReason.FOREGROUND)
    }

    /** Autorisation accordée ou étendue (partielle -> complète, nouveaux éléments choisis). */
    fun onAccessChanged() = syncIfAllowed(full = false)

    /**
     * Synchro immédiate. La relecture complète ([full]) ne concerne que les médias locaux : la
     * synchro OneDrive est toujours incrémentale (énumération complète automatique si nécessaire).
     */
    fun syncNow(full: Boolean) {
        syncIfAllowed(full)
        if (!full) cloudSync.get().requestSync(CloudSyncReason.MANUAL)
    }

    @Synchronized
    private fun syncIfAllowed(full: Boolean) {
        if (!hasMediaAccess()) return
        val idx = indexer.get()
        idx.startObserving()
        val previous = syncJob
        if (previous?.isActive == true && !full) return
        syncJob = scope.launch(io) {
            previous?.join()
            try {
                idx.sync(full)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // L'indexeur publie lui-même l'erreur dans IndexState ; on se contente de journaliser.
                Log.w(TAG, "Echec de la synchronisation locale", e)
            }
        }
    }

    private companion object {
        const val TAG = "MediaSync"
    }
}
