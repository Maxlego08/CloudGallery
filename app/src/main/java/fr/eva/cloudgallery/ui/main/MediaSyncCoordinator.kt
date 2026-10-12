package fr.eva.cloudgallery.ui.main

import android.content.Context
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.cloud.content.CloudOriginalStore
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.CloudSyncReason
import fr.eva.cloudgallery.domain.repository.CloudMediaSyncer
import fr.eva.cloudgallery.domain.repository.LocalMediaIndexer
import fr.eva.cloudgallery.domain.repository.OfflineRepository
import fr.eva.cloudgallery.ui.permissions.MediaAccess
import fr.eva.cloudgallery.ui.permissions.MediaPermissions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
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
 *
 * Au premier passage au premier plan du processus, le cache temporaire des originaux OneDrive
 * est nettoyé (plus de 7 jours, plafond 1 Go, fichiers partiels abandonnés).
 *
 * Copies hors ligne : à chaque passage au premier plan et à la fin de chaque synchronisation
 * OneDrive (nouvelles versions à télécharger), la file est relancée si besoin. Le travail de
 * téléchargement ne peut être planifié que lorsque l'appli est visible.
 */
@Singleton
class MediaSyncCoordinator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val indexer: dagger.Lazy<LocalMediaIndexer>,
    private val cloudSync: dagger.Lazy<CloudMediaSyncer>,
    private val originals: dagger.Lazy<CloudOriginalStore>,
    private val offline: dagger.Lazy<OfflineRepository>,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private var syncJob: Job? = null
    private val startupCleanupDone = AtomicBoolean(false)

    fun hasMediaAccess(): Boolean = MediaPermissions.currentAccess(context) != MediaAccess.NONE

    /** Appli revenue au premier plan : synchro incrémentale (locale et OneDrive). */
    fun onForeground() {
        syncIfAllowed(full = false)
        cloudSync.get().requestSync(CloudSyncReason.FOREGROUND)
        if (startupCleanupDone.compareAndSet(false, true)) {
            scope.launch(io) {
                try {
                    originals.get().trim()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Nettoyage des originaux OneDrive impossible", e)
                }
            }
            // Fin d'une synchronisation OneDrive : versions modifiées à retélécharger.
            scope.launch(io) {
                cloudSync.get().state
                    .map { it.running }
                    .distinctUntilChanged()
                    .drop(1)
                    .filter { running -> !running }
                    .collect { resumeOffline() }
            }
        }
        scope.launch(io) { resumeOffline() }
    }

    private suspend fun resumeOffline() {
        try {
            offline.get().resumeIfNeeded()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reprise des copies hors ligne impossible", e)
        }
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
