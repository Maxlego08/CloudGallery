package fr.eva.cloudgallery.data.cloud.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.cloud.CloudFeature
import fr.eva.cloudgallery.data.cloud.auth.MsalAuthService
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.CloudSyncReason
import fr.eva.cloudgallery.domain.repository.CloudMediaSyncer
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Passage de synchronisation OneDrive en arrière-plan (WorkManager), regroupé avec les autres
 * demandes et ignoré si la dernière synchro date de moins de 5 min. Les échecs (réseau,
 * limitation Graph) sont enregistrés par la synchro elle-même : le travail réussit toujours et
 * reprend à la période suivante. Les nouvelles versions de copies hors ligne trouvées ici sont
 * téléchargées au prochain retour au premier plan (tâche « lancée par l'utilisateur »,
 * planifiable seulement appli visible).
 */
@HiltWorker
class OneDriveSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val syncer: CloudMediaSyncer,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        try {
            syncer.syncAndWait(CloudSyncReason.BACKGROUND)
        } catch (e: CancellationException) {
            // Travail arrêté par WorkManager : annulation propagée. Sinon, passage interrompu par
            // une déconnexion ou une purge : rien à refaire.
            currentCoroutineContext().ensureActive()
        } catch (e: Exception) {
            Log.w(TAG, "Synchronisation OneDrive en arrière-plan impossible", e)
        }
        return Result.success()
    }

    private companion object {
        const val TAG = "OneDriveSyncWorker"
    }
}

/**
 * Travail périodique de synchronisation OneDrive, tenu à jour d'après le réglage (fréquence,
 * Wi-Fi uniquement) et l'état du compte ([backgroundSyncPlan]) : planifié pour un compte
 * connecté, annulé à la déconnexion ou réglage désactivé. Premier passage après une période
 * complète (le retour au premier plan synchronise déjà). Batterie faible : attente.
 */
@Singleton
class BackgroundSyncScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val feature: CloudFeature,
    private val settings: SettingsRepository,
    private val auth: dagger.Lazy<MsalAuthService>,
    @param:ApplicationScope private val scope: CoroutineScope,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    private val started = AtomicBoolean(false)

    /** Suivi du réglage et du compte pour la durée du processus (Application.onCreate). */
    fun start() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(io) {
            val prefs = settings.settings
                .map { it.backgroundSyncInterval to it.backgroundSyncWifiOnly }
                .distinctUntilChanged()
            combine(prefs, auth.get().state) { (interval, wifiOnly), account ->
                backgroundSyncPlan(feature.enabled, interval, wifiOnly, account)
            }
                .distinctUntilChanged()
                .collect { plan ->
                    try {
                        apply(plan)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Planification de la synchronisation en arrière-plan impossible", e)
                    }
                }
        }
    }

    private fun apply(plan: BackgroundSyncPlan) {
        val work = WorkManager.getInstance(context)
        when (plan) {
            BackgroundSyncPlan.Unchanged -> Unit
            BackgroundSyncPlan.Cancel -> work.cancelUniqueWork(WORK_NAME)
            is BackgroundSyncPlan.Schedule -> {
                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(if (plan.unmeteredOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()
                val request = PeriodicWorkRequestBuilder<OneDriveSyncWorker>(plan.intervalHours, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setInitialDelay(plan.intervalHours, TimeUnit.HOURS)
                    .build()
                // UPDATE : fréquence ou réseau modifiés pris en compte, passage en cours non interrompu.
                work.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
            }
        }
    }

    private companion object {
        const val TAG = "BackgroundSync"
        const val WORK_NAME = "onedrive_background_sync"
    }
}
