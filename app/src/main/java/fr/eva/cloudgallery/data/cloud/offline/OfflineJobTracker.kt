package fr.eva.cloudgallery.data.cloud.offline

import kotlinx.coroutines.sync.Mutex
import javax.inject.Inject
import javax.inject.Singleton

/** What to do with a job scheduling request. */
internal enum class ScheduleDecision {
    /** A running or scheduled job will pick up the tasks. */
    KEEP,

    /** Schedule (or reschedule, which replaces the existing job). */
    SCHEDULE,
}

/**
 * [required]: network required by the pending tasks. [active]: network of the job running in this
 * process (null if there is none, or if it has decided to finish). [pendingReliable]: the
 * scheduled job known to JobScheduler is still valid (not a job this process has seen finish:
 * JobScheduler may still return it right after `jobFinished`).
 * [pending]: network of the scheduled job according to JobScheduler.
 */
internal fun scheduleDecision(
    required: JobNetwork,
    active: JobNetwork?,
    pendingReliable: Boolean,
    pending: JobNetwork?,
): ScheduleDecision = when {
    active != null -> if (jobCovers(active, required)) ScheduleDecision.KEEP else ScheduleDecision.SCHEDULE
    pendingReliable && pending == required -> ScheduleDecision.KEEP
    else -> ScheduleDecision.SCHEDULE
}

/**
 * Tracks, within this process, the offline copy download job: schedulings, start and end.
 * JobScheduler keeps returning a job that has just called `jobFinished` (processed later on the
 * main thread): without this tracking, a task added at that moment would be left without a job.
 *
 * [lock] serializes compound decisions: scheduling ([scheduleDecision]) on one side, "is there
 * any work left, otherwise finish" of the running job on the other. Scheduling sequence numbers
 * tell an old (replaced) job apart from the most recent one.
 */
@Singleton
class OfflineJobTracker @Inject constructor() {

    /** Lock for the "schedule" and "finish the job" decisions. */
    internal val lock = Mutex()

    private var scheduleSeq = 0L
    private var endedSeq = -1L
    private var activeSeq: Long? = null
    private var activeNetwork: JobNetwork? = null

    /** Network of the job running in this process (null: none, or it is finishing). */
    internal val active: JobNetwork?
        @Synchronized get() = activeNetwork

    /** The scheduled job returned by JobScheduler is not an already finished job. */
    internal val pendingReliable: Boolean
        @Synchronized get() = endedSeq < scheduleSeq

    /** A job has just been scheduled (it replaces the previous one). */
    @Synchronized
    internal fun onScheduled() {
        scheduleSeq++
    }

    /** The job starts with the [network] constraint; returns its sequence number. */
    @Synchronized
    internal fun onJobStarted(network: JobNetwork): Long {
        activeSeq = scheduleSeq
        activeNetwork = network
        return scheduleSeq
    }

    /** Job [seq] is finishing; [rescheduled]: it stays scheduled (retry, stopped by the system). */
    @Synchronized
    internal fun onJobEnded(seq: Long, rescheduled: Boolean) {
        if (activeSeq == seq) {
            activeSeq = null
            activeNetwork = null
        }
        if (!rescheduled && seq > endedSeq) endedSeq = seq
    }
}
