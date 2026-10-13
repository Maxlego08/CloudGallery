package fr.eva.cloudgallery.data.cloud.offline

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.net.NetworkCapabilities
import android.util.Log
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Issue d'une demande de planification. */
enum class ScheduleResult {
    /** Travail planifié (il démarre dès que le réseau demandé est disponible). */
    SCHEDULED,

    /** Refusé par le système (appli pas au premier plan, autorisation absente) : reprise au prochain premier plan. */
    REFUSED,
}

/**
 * Planification du travail de téléchargement des copies hors ligne : tâche de transfert de
 * données lancée par l'utilisateur (JobScheduler, `setUserInitiated`), sans quota ni limite de
 * durée, avec notification de progression obligatoire. WorkManager 2.12 ne sait pas créer ce
 * type de tâche (vérifié dans le bytecode).
 *
 * - Espace de noms JobScheduler propre (« offline ») : aucun conflit d'identifiant avec
 *   WorkManager, qui utilise le sien.
 * - Réseau exigé par `setRequiredNetworkType` (Wi-Fi non facturé, ou tout réseau après
 *   « Télécharger maintenant ») : cette forme garde les VPN acceptés, contrairement à une
 *   NetworkRequest construite à la main.
 * - Ne peut être planifié que lorsque l'appli est visible (sinon refus du système).
 * - Non persistant : après un redémarrage, la file reprend au prochain lancement de l'appli.
 *
 * Toutes les méthodes sont des appels Binder courts (JobScheduler) : appelables depuis le
 * dispatcher d'E/S comme depuis le thread principal.
 */
@Singleton
class OfflineJobScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val jobs: JobScheduler? by lazy {
        context.getSystemService(JobScheduler::class.java)?.forNamespace(NAMESPACE)
    }

    /**
     * Planifie (ou replanifie) le travail. Un travail en cours avec le même identifiant serait
     * arrêté par le système : l'appelant ne replanifie pas pendant un passage du moteur.
     */
    fun schedule(estimatedBytes: Long, network: JobNetwork): ScheduleResult {
        val scheduler = jobs ?: return ScheduleResult.REFUSED
        return try {
            if (!scheduler.canRunUserInitiatedJobs()) return ScheduleResult.REFUSED
            val info = JobInfo.Builder(JOB_ID, ComponentName(context, OfflineTransferJobService::class.java))
                .setUserInitiated(true)
                .setRequiredNetworkType(
                    when (network) {
                        JobNetwork.UNMETERED -> JobInfo.NETWORK_TYPE_UNMETERED
                        JobNetwork.ANY -> JobInfo.NETWORK_TYPE_ANY
                    },
                )
                .setEstimatedNetworkBytes(
                    if (estimatedBytes > 0) estimatedBytes else JobInfo.NETWORK_BYTES_UNKNOWN.toLong(),
                    0L,
                )
                .build()
            if (scheduler.schedule(info) == JobScheduler.RESULT_SUCCESS) ScheduleResult.SCHEDULED else ScheduleResult.REFUSED
        } catch (e: SecurityException) {
            Log.w(TAG, "Planification refusée", e)
            ScheduleResult.REFUSED
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Planification refusée", e)
            ScheduleResult.REFUSED
        }
    }

    /** Réseau exigé par le travail planifié, null s'il n'y en a pas. */
    fun pendingNetwork(): JobNetwork? {
        val info = try {
            jobs?.getPendingJob(JOB_ID)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val unmetered = info.requiredNetwork?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        return if (unmetered) JobNetwork.UNMETERED else JobNetwork.ANY
    }

    /** Retire le travail planifié ou en cours (arrêt avec le motif « annulé par l'appli »). */
    fun cancel() {
        try {
            jobs?.cancel(JOB_ID)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Annulation du travail impossible", e)
        }
    }

    private companion object {
        const val TAG = "OfflineJobs"
        const val NAMESPACE = "offline"
        const val JOB_ID = 4001
    }
}
