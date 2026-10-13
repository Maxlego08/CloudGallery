package fr.eva.cloudgallery.data.cloud.offline

import android.app.job.JobParameters
import fr.eva.cloudgallery.data.cloud.sync.CloudStamp
import fr.eva.cloudgallery.domain.model.DownloadError
import fr.eva.cloudgallery.domain.model.OfflineItemState
import fr.eva.cloudgallery.domain.model.OfflineStatus

/*
 * Pure logic for offline copies (tested on the JVM).
 */

/** Free space margin kept on the device on top of the requested copies (200 MiB). */
internal const val OFFLINE_SPACE_MARGIN_BYTES = 200L * 1024 * 1024

/** Beyond this number of items, "Keep offline" asks for confirmation. */
internal const val LARGE_BATCH_ITEMS = 200

/** Beyond this total size (1 GB), "Keep offline" asks for confirmation. */
internal const val LARGE_BATCH_BYTES = 1_000_000_000L

/** Attempts for a copy before marking it as failed (transient errors: network, throttling). */
internal const val MAX_DOWNLOAD_ATTEMPTS = 5

/** Total size to download: known sizes only (a size ≤ 0 counts as 0). */
internal fun estimateBytes(sizes: Iterable<Long>): Long = sizes.sumOf { it.coerceAtLeast(0L) }

/**
 * Bytes missing to copy [requested] bytes while keeping [margin] free, based on the allocatable
 * space [allocatable] (StorageManager.getAllocatableBytes). 0 if there is enough space.
 */
internal fun missingSpace(allocatable: Long, requested: Long, margin: Long = OFFLINE_SPACE_MARGIN_BYTES): Long =
    (requested.coerceAtLeast(0L) + margin - allocatable.coerceAtLeast(0L)).coerceAtLeast(0L)

/** What to do with a disk space request. */
internal enum class SpaceAction {
    /** The free space is already enough. */
    ENOUGH,

    /**
     * The free space is not enough, but the system can free other apps' cache:
     * `StorageManager.allocateBytes` must be called (an ordinary write does not free it).
     */
    ALLOCATE,

    /** Even after freeing other apps' cache, there is not enough space. */
    NO_SPACE,
}

/**
 * Space for [needed] bytes while keeping [margin] free: [usable] = free space on the volume
 * (File.getUsableSpace), [allocatable] = space obtainable with the cache the system can free
 * (StorageManager.getAllocatableBytes, usually larger). According to the getAllocatableBytes docs,
 * asking allocateBytes for more than this value fails.
 */
internal fun spaceAction(usable: Long, allocatable: Long, needed: Long, margin: Long): SpaceAction = when {
    missingSpace(usable, needed, margin) == 0L -> SpaceAction.ENOUGH
    missingSpace(allocatable, needed, margin) == 0L -> SpaceAction.ALLOCATE
    else -> SpaceAction.NO_SPACE
}

/** Batch to confirm before downloading (total size and free space shown). */
internal fun isLargeBatch(count: Int, bytes: Long): Boolean = count > LARGE_BATCH_ITEMS || bytes > LARGE_BATCH_BYTES

/**
 * Content of an item kept offline changed on OneDrive (sync): the copy is downloaded again. A
 * version unknown on one side counts as a change only if the other one is known (a copy without
 * a version must not stay frozen).
 */
internal fun requeueOnCTagChange(hasOfflineCopy: Boolean, oldCTag: String?, newCTag: String?): Boolean =
    hasOfflineCopy && newCTag != null && oldCTag != newCTag

/** Network type required by the download job. */
enum class JobNetwork { UNMETERED, ANY }

/**
 * Job network: Wi-Fi only according to the setting, unless a pending task belongs to a
 * "Download now" batch ([overrideNow]).
 */
internal fun jobNetworkFor(wifiOnly: Boolean, overrideNow: Boolean): JobNetwork =
    if (wifiOnly && !overrideNow) JobNetwork.UNMETERED else JobNetwork.ANY

/**
 * A running job that requires [active] also handles tasks that require [required]: a Wi-Fi-only
 * job suits everything; an "any network" job only downloads Wi-Fi-only tasks when it is on an
 * unmetered network (see [allowedOnNetwork]): so it is not enough for them.
 */
internal fun jobCovers(active: JobNetwork, required: JobNetwork): Boolean =
    active == JobNetwork.UNMETERED || required == JobNetwork.ANY

/**
 * Tasks a pass may download on the current network: all of them (null) without the "Wi-Fi only"
 * setting or on an unmetered network; otherwise only those from a "Download now" batch
 * ([overridden]). The others keep waiting for Wi-Fi.
 */
internal fun allowedOnNetwork(wifiOnly: Boolean, unmeteredNetwork: Boolean, overridden: Set<Long>): Set<Long>? =
    if (!wifiOnly || unmeteredNetwork) null else overridden

/**
 * A task outside a "Download now" batch ([overridden] false) waits for Wi-Fi: setting enabled
 * and current network missing or metered.
 */
internal fun waitsForUnmetered(wifiOnly: Boolean, overridden: Boolean, connected: Boolean, unmetered: Boolean): Boolean =
    wifiOnly && !overridden && (!connected || !unmetered)

/**
 * Task covered by "Download now": id and enqueue date (SQLite may reassign the id of a deleted
 * task to a new task).
 */
internal data class OverrideKey(val taskId: Long, val createdAt: Long)

/** State of a queue task, as read from the database. */
internal enum class TaskState { QUEUED, RUNNING, PAUSED, FAILED }

/** Offline state displayed for a media item: possible open task and copy present. */
internal fun offlineStatus(
    taskState: TaskState?,
    taskError: DownloadError?,
    bytesDone: Long,
    bytesTotal: Long,
    hasOffline: Boolean,
    waitingForUnmetered: Boolean,
): OfflineItemState = when (taskState) {
    null -> if (hasOffline) OfflineItemState(OfflineStatus.AVAILABLE) else OfflineItemState.NONE
    TaskState.QUEUED, TaskState.RUNNING -> OfflineItemState(
        status = when {
            hasOffline -> OfflineStatus.UPDATING
            taskState == TaskState.RUNNING -> OfflineStatus.DOWNLOADING
            else -> OfflineStatus.QUEUED
        },
        bytesDone = bytesDone,
        bytesTotal = bytesTotal,
        waitingForUnmetered = taskState == TaskState.QUEUED && waitingForUnmetered,
    )
    // Copy present: it keeps being served, but the stopped update is reported (resume or cancel
    // possible for this item).
    TaskState.PAUSED -> if (hasOffline) {
        OfflineItemState(OfflineStatus.UPDATE_STOPPED, bytesDone = bytesDone, bytesTotal = bytesTotal)
    } else {
        OfflineItemState(OfflineStatus.PAUSED, bytesDone = bytesDone, bytesTotal = bytesTotal)
    }
    TaskState.FAILED -> if (hasOffline) {
        OfflineItemState(OfflineStatus.UPDATE_STOPPED, error = taskError ?: DownloadError.SERVER)
    } else {
        OfflineItemState(OfflineStatus.FAILED, error = taskError ?: DownloadError.SERVER)
    }
}

/** What to do after a copy download fails. */
internal enum class FailureAction {
    /** Transient error: the task stays queued, the job is relaunched later (system backoff). */
    RETRY_LATER,

    /** Error specific to this item: the task is marked failed, the following ones continue. */
    FAIL,

    /** Error that will affect every task (account, space): the whole queue is marked failed. */
    FAIL_ALL,

    /**
     * Content newer on OneDrive than in the index: the task stays pending without counting an
     * attempt and lets the following ones through; it restarts once sync has updated the index
     * (see the downloadable tasks query).
     */
    WAIT_FOR_INDEX,
}

/** Decision after a failure with [error], [attempts] attempts counted (1 for the first one). */
internal fun failureAction(error: DownloadError, attempts: Int): FailureAction = when (error) {
    DownloadError.AUTH_REQUIRED, DownloadError.NO_SPACE -> FailureAction.FAIL_ALL
    DownloadError.NOT_FOUND, DownloadError.CANCELLED -> FailureAction.FAIL
    DownloadError.CONTENT_CHANGED -> FailureAction.WAIT_FOR_INDEX
    DownloadError.NETWORK, DownloadError.THROTTLED, DownloadError.SERVER, DownloadError.SIZE_MISMATCH ->
        if (attempts < MAX_DOWNLOAD_ATTEMPTS) FailureAction.RETRY_LATER else FailureAction.FAIL
}

/**
 * Does the failure count as an attempt? Not a changed content (index lagging behind, not a defect
 * of the item), nor a network drop after bytes were received during this attempt (Wi-Fi lost
 * midway: the partial file progresses, the copy is not at fault).
 */
internal fun countsAsAttempt(error: DownloadError, progressed: Boolean): Boolean = when (error) {
    DownloadError.CONTENT_CHANGED -> false
    DownloadError.NETWORK -> !progressed
    else -> true
}

/** Queue cleanup the first time a process comes back to the foreground. */
internal enum class StartupRecovery {
    /** "Running" tasks of a dead process put back in the queue. */
    REQUEUE,

    /** Process stopped by the user during a download: the whole queue is paused. */
    PAUSE,
}

/**
 * [userRequestedExit]: last process exit requested by the user (force stop, Task Manager, but
 * also a simple swipe from recent apps). Only an exit during a download ([hadRunningTasks]: tasks
 * left "running") pauses the queue (decision D3); swiping the app away with no active download
 * changes nothing.
 */
internal fun startupRecovery(firstStart: Boolean, userRequestedExit: Boolean, hadRunningTasks: Boolean): StartupRecovery =
    if (firstStart && userRequestedExit && hadRunningTasks) StartupRecovery.PAUSE else StartupRecovery.REQUEUE

/** Lenient parsing of a stored error code. */
internal fun downloadErrorOrNull(name: String?): DownloadError? =
    name?.let { n -> DownloadError.entries.firstOrNull { it.name == n } }

/** What to do when the system stops the job ([JobParameters.getStopReason]). */
internal enum class StopAction {
    /** The app cancelled the job ("Cancel", purge): nothing to resume. */
    NONE,

    /** The user stopped it (Task Manager): queue paused until resumed. */
    PAUSE,

    /** Constraint lost (network, Wi-Fi), timeout, system: automatic resume later. */
    RESCHEDULE,
}

internal fun stopActionFor(stopReason: Int): StopAction = when (stopReason) {
    JobParameters.STOP_REASON_CANCELLED_BY_APP -> StopAction.NONE
    JobParameters.STOP_REASON_USER -> StopAction.PAUSE
    else -> StopAction.RESCHEDULE
}

/**
 * End of the grace period for items deleted from OneDrive (decision D1): [purge] are removed as
 * before; [awaitingDecision] have an offline copy and are kept (hidden) until the user decides
 * ("Delete the copies" or "Keep them").
 */
internal data class GraceExpiryPlan(val purge: List<CloudStamp>, val awaitingDecision: List<CloudStamp>)

internal fun planGraceExpiry(expired: List<CloudStamp>, mediaWithOfflineCopy: Set<Long>): GraceExpiryPlan {
    val (awaiting, purge) = expired.partition { it.mediaId in mediaWithOfflineCopy }
    return GraceExpiryPlan(purge = purge, awaitingDecision = awaiting)
}

/**
 * The downloaded file ([gotCTag], [gotSize], [gotETag]) really is the item's version in the index
 * at save time. cTags known on both sides: they are authoritative. Otherwise (P1-11: two unknown
 * versions are never "identical" by default) the size must be equal, and so must the eTag if it
 * is known on both sides.
 */
internal fun contentMatches(
    indexCTag: String?,
    indexSize: Long,
    indexETag: String?,
    gotCTag: String?,
    gotSize: Long,
    gotETag: String?,
): Boolean = when {
    indexCTag != null && gotCTag != null -> indexCTag == gotCTag
    indexSize != gotSize -> false
    indexETag != null && gotETag != null -> indexETag == gotETag
    else -> true
}

/** An offline copy that is the item's last copy: "Free up" asks for confirmation. */
internal fun releaseNeedsConfirmation(lastCopies: Int): Boolean = lastCopies > 0
