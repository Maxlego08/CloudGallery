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

/** Outcome of a scheduling request. */
enum class ScheduleResult {
    /** Job scheduled (it starts as soon as the requested network is available). */
    SCHEDULED,

    /** Refused by the system (app not in the foreground, permission missing): resumed next time the app is in the foreground. */
    REFUSED,
}

/**
 * Scheduling of the offline copy download job: user-initiated data transfer job (JobScheduler,
 * `setUserInitiated`), with no quota or duration limit, and a mandatory progress notification.
 * WorkManager 2.12 cannot create this kind of job (checked in the bytecode).
 *
 * - Dedicated JobScheduler namespace ("offline"): no id clash with WorkManager, which uses its
 *   own.
 * - Network required through `setRequiredNetworkType` (unmetered Wi-Fi, or any network after
 *   "Download now"): this form keeps VPNs accepted, unlike a hand-built NetworkRequest.
 * - Can only be scheduled while the app is visible (otherwise the system refuses).
 * - Not persisted: after a reboot, the queue resumes the next time the app is launched.
 *
 * All methods are short Binder calls (JobScheduler): callable from the I/O dispatcher as well as
 * from the main thread.
 */
@Singleton
class OfflineJobScheduler @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {

    private val jobs: JobScheduler? by lazy {
        context.getSystemService(JobScheduler::class.java)?.forNamespace(NAMESPACE)
    }

    /**
     * Schedules (or reschedules) the job. A running job with the same id would be stopped by the
     * system: the caller does not reschedule during an engine pass.
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
            Log.w(TAG, "Scheduling refused", e)
            ScheduleResult.REFUSED
        } catch (e: IllegalArgumentException) {
            Log.w(TAG, "Scheduling refused", e)
            ScheduleResult.REFUSED
        }
    }

    /** Network required by the scheduled job, null if there is none. */
    fun pendingNetwork(): JobNetwork? {
        val info = try {
            jobs?.getPendingJob(JOB_ID)
        } catch (_: RuntimeException) {
            null
        } ?: return null
        val unmetered = info.requiredNetwork?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
        return if (unmetered) JobNetwork.UNMETERED else JobNetwork.ANY
    }

    /** Removes the scheduled or running job (stopped with the "cancelled by app" reason). */
    fun cancel() {
        try {
            jobs?.cancel(JOB_ID)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not cancel the job", e)
        }
    }

    private companion object {
        const val TAG = "OfflineJobs"
        const val NAMESPACE = "offline"
        const val JOB_ID = 4001
    }
}
