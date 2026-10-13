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
 * JobScheduler job (user-initiated data transfer) that drains the offline copy queue with
 * [OfflineDownloadEngine].
 *
 * - Notification provided right at start (mandatory within 10 s, on every start).
 * - Connections on the network chosen by the system ([JobParameters.getNetwork]).
 * - `jobFinished` called on every path, except after `onStopJob` (pointless then).
 * - Before finishing, the job rechecks the queue (under the scheduling lock): a task added while
 *   a pass is ending is picked up by a new pass, or handed to a new job; tasks still waiting for
 *   Wi-Fi are handed to a new job (accepted only if the app is visible, otherwise the next time
 *   it is in the foreground).
 * - Stopped by the user (Task Manager): queue paused until resumed; cancelled by the app:
 *   nothing; any other stop (network lost, Wi-Fi left): resumed by the system. The partial file
 *   is always kept for resuming.
 *
 * The "SpecifyJobSchedulerIdRange" warning does not apply: this job is scheduled in its own
 * JobScheduler namespace ("offline"), WorkManager uses its own.
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

    /** Strong reference to the running job's parameters (Android 16: otherwise the job is "abandoned"). */
    private var params: JobParameters? = null

    @Volatile
    private var stoppedBySystem = false

    /** Value returned by `onStopJob`: the system reschedules the job. */
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
                    // Tasks added while the pass was ending: new pass (bounded).
                    passes++
                    if (passes >= MAX_PASSES || !offline.continueOrEndJob(jobSeq, network)) break
                }
                if (!reschedule) OfflineNotifications.showSummary(context, engine.lastSummary)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Offline copy job failed", e)
            } finally {
                // Normal end (not a cancellation by the purge, "Cancel" or the system).
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
            // Once the pass has actually stopped (task put back in the queue), the whole queue is paused.
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

        /** Maximum number of successive passes in a single job (tasks added at the end of a pass). */
        const val MAX_PASSES = 20
    }
}
