package fr.eva.cloudgallery.domain.model

import java.io.File
import java.util.UUID

/** "Offline" state of a OneDrive item (viewer, Details panel). */
enum class OfflineStatus {
    /** No offline copy and nothing in progress. */
    NONE,

    /** Waiting to be downloaded. */
    QUEUED,

    /** Download in progress. */
    DOWNLOADING,

    /** Stopped by the user (Task manager): resumed on request. */
    PAUSED,

    /** Failed ([OfflineItemState.error]): retried on request. */
    FAILED,

    /** Copy available on the device. */
    AVAILABLE,

    /** Copy available; new version waiting or being downloaded. */
    UPDATING,

    /**
     * Copy available (old version); download of the new version paused or failed
     * ([OfflineItemState.error]): resumed or cancelled on request.
     */
    UPDATE_STOPPED,
}

/** Offline state of a media item, including progress. */
data class OfflineItemState(
    val status: OfflineStatus,
    val error: DownloadError? = null,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    /** Download waiting for a Wi-Fi network ("Wi-Fi only" setting). */
    val waitingForUnmetered: Boolean = false,
) {
    /** Task waiting or in progress (cancellable). */
    val active: Boolean get() = status == OfflineStatus.QUEUED || status == OfflineStatus.DOWNLOADING ||
        (status == OfflineStatus.UPDATING && error == null)

    /** Task stopped, to be resumed (first copy or update of an existing copy). */
    val stopped: Boolean get() = status == OfflineStatus.PAUSED || status == OfflineStatus.FAILED ||
        status == OfflineStatus.UPDATE_STOPPED

    companion object {
        val NONE = OfflineItemState(OfflineStatus.NONE)
    }
}

/** Queue of offline copies (Account screen, notification). */
data class OfflineQueueState(
    val queued: Int = 0,
    val running: Int = 0,
    val paused: Int = 0,
    val failed: Int = 0,
    /** Bytes left to download (tasks waiting or in progress, known sizes). */
    val remainingBytes: Long = 0L,
    /** Queue waiting for a Wi-Fi network ("Wi-Fi only" setting, without "Download now"). */
    val waitingForUnmetered: Boolean = false,
    /** Offline copies present on the device. */
    val availableCount: Int = 0,
    /** Total size of the offline copies present. */
    val availableBytes: Long = 0L,
) {
    val pending: Int get() = queued + running
    val isEmpty: Boolean get() = pending == 0 && paused == 0 && failed == 0
}

/** Outcome of "Keep offline". */
sealed interface KeepOfflineResult {
    /** No usable OneDrive account. */
    data object NotSignedIn : KeepOfflineResult

    /** Nothing to download (already offline, or no OneDrive item in the selection). */
    data object NothingToDo : KeepOfflineResult

    /** Not enough space: [missingBytes] are missing on volume [storageUuid]. */
    data class NoSpace(val missingBytes: Long, val storageUuid: UUID?) : KeepOfflineResult

    /** Large batch: confirmation requested with the total size and the free space. */
    data class NeedsConfirmation(val count: Int, val bytes: Long, val freeBytes: Long) : KeepOfflineResult

    /** [count] items queued ([bytes] bytes); [waitingForUnmetered]: waiting for Wi-Fi. */
    data class Enqueued(val count: Int, val bytes: Long, val waitingForUnmetered: Boolean) : KeepOfflineResult

    /**
     * Queued, but the system refuses to start the work now (app in the background, permission
     * revoked): it will resume the next time the app comes back to the foreground.
     */
    data class SchedulingRefused(val count: Int) : KeepOfflineResult
}

/**
 * Offline copy of an item deleted from OneDrive whose grace period has elapsed, waiting for
 * the user's decision (decision D1).
 */
data class DeletedOnOneDriveCopy(
    val mediaId: Long,
    val displayName: String,
    val type: MediaType,
    val mimeType: String?,
    val sizeBytes: Long,
)

/**
 * Image model (Coil): thumbnail decoded from the offline copy of a media item (app file), when
 * neither a local file nor a OneDrive thumbnail is available. [version] invalidates the memory
 * cache when the copy changes.
 */
data class OfflineThumbnail(val mediaId: Long, val version: String, val mimeType: String?)

/** Offline copy of a media item: an app file (never a user file). */
data class OfflineFile(
    val file: File,
    val sizeBytes: Long,
    /** Version of the copied content (OneDrive cTag), null if unknown. */
    val version: String?,
)
