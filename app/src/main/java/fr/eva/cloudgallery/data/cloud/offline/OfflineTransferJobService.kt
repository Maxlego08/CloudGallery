package fr.eva.cloudgallery.data.cloud.offline

import android.annotation.SuppressLint
import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import dagger.hilt.android.AndroidEntryPoint
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Travail JobScheduler (transfert de données lancé par l'utilisateur) qui vide la file des copies
 * hors ligne avec [OfflineDownloadEngine].
 *
 * - Notification fournie dès le démarrage (obligatoire dans les 10 s, à chaque démarrage).
 * - Connexions sur le réseau choisi par le système ([JobParameters.getNetwork]).
 * - `jobFinished` appelé sur tous les chemins, sauf après `onStopJob` (inutile alors).
 * - Avant de se terminer, le travail revérifie la file (sous le verrou de planification) : une
 *   tâche ajoutée pendant la fin d'un passage est prise par un nouveau passage, ou confiée à un
 *   nouveau travail ; des tâches restées en attente du Wi-Fi sont confiées à un nouveau travail
 *   (accepté seulement si l'appli est visible, sinon au prochain premier plan).
 * - Arrêt par l'utilisateur (Gestionnaire de tâches) : file en pause jusqu'à sa reprise ;
 *   annulation par l'appli : rien ; autre arrêt (réseau perdu, Wi-Fi quitté) : reprise par le
 *   système. Le fichier partiel est toujours gardé pour la reprise.
 *
 * L'avertissement « SpecifyJobSchedulerIdRange » ne s'applique pas : ce travail est planifié dans
 * son propre espace de noms JobScheduler (« offline »), WorkManager utilise le sien.
 */
@SuppressLint("SpecifyJobSchedulerIdRange")
@AndroidEntryPoint
class OfflineTransferJobService : JobService() {

    @Inject
    lateinit var engine: OfflineDownloadEngine

    @Inject
    lateinit var offline: OfflineRepositoryImpl

    @Inject
    @ApplicationScope
    lateinit var appScope: CoroutineScope

    @Inject
    @IoDispatcher
    lateinit var io: CoroutineDispatcher

    private val serviceJob = SupervisorJob()
    private var running: Job? = null

    /** Référence forte aux paramètres du travail en cours (Android 16 : travail « abandonné » sinon). */
    private var params: JobParameters? = null

    @Volatile
    private var stoppedBySystem = false

    /** Valeur renvoyée par `onStopJob` : le système replanifie le travail. */
    @Volatile
    private var stopRescheduled = false

    override fun onStartJob(params: JobParameters): Boolean {
        this.params = params
        stoppedBySystem = false
        stopRescheduled = false
        OfflineNotifications.ensureChannel(this)
        setNotification(
            params,
            OfflineNotifications.PROGRESS_ID,
            OfflineNotifications.progress(this, null),
            JOB_END_NOTIFICATION_POLICY_REMOVE,
        )
        val context = applicationContext
        val network = params.network
        running = CoroutineScope(serviceJob + io).launch {
            var reschedule = false
            var seq: Long? = null
            try {
                val jobSeq = offline.jobStarted().also { seq = it }
                var passes = 0
                while (true) {
                    val outcome = engine.drain(network, allowed = { offline.allowedTaskIds(network) }) { progress ->
                        updateTransferredNetworkBytes(params, progress.sessionBytes, 0L)
                        OfflineNotifications.update(context, progress)
                    }
                    if (outcome == DrainOutcome.RETRY_LATER) {
                        reschedule = true
                        break
                    }
                    // Tâches ajoutées pendant la fin du passage : nouveau passage (borné).
                    passes++
                    if (passes >= MAX_PASSES || !offline.continueOrEndJob(jobSeq, network)) break
                }
                if (!reschedule) OfflineNotifications.showSummary(context, engine.lastSummary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Echec du travail des copies hors ligne", e)
            } finally {
                // Fin normale (pas une annulation par la purge, « Annuler » ou le système).
                val completedNormally = isActive && !stoppedBySystem
                if (!stoppedBySystem) jobFinished(params, reschedule)
                withContext(NonCancellable) {
                    seq?.let { offline.jobEnded(it, rescheduled = reschedule || (stoppedBySystem && stopRescheduled)) }
                    if (completedNormally && !reschedule) offline.relaunchIfQueued()
                }
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val action = stopActionFor(params.stopReason)
        stopRescheduled = action == StopAction.RESCHEDULE
        stoppedBySystem = true
        val job = running
        job?.cancel()
        if (action == StopAction.PAUSE) {
            // Après l'arrêt effectif du passage (tâche remise en attente), toute la file passe en pause.
            appScope.launch(io) {
                job?.join()
                offline.pauseAfterUserStop()
            }
        }
        return action == StopAction.RESCHEDULE
    }

    override fun onDestroy() {
        serviceJob.cancel()
        super.onDestroy()
    }

    private companion object {
        const val TAG = "OfflineJob"

        /** Passages successifs au plus dans un même travail (tâches ajoutées en fin de passage). */
        const val MAX_PASSES = 20
    }
}
