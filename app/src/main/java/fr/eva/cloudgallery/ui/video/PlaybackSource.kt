package fr.eva.cloudgallery.ui.video

import android.net.Uri
import androidx.media3.common.MediaItem
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.OfflineFile

/** Physical source chosen to play a media item. */
sealed interface PlaybackSource {
    /** File present on the device (MediaStore `content://` URI). */
    data class Local(val uri: Uri, val mimeType: String?) : PlaybackSource

    /**
     * Offline copy of a OneDrive item (app-owned file, played as is). [file] has been
     * checked (present, expected size) off the main thread.
     */
    data class Offline(val file: OfflineFile, val mimeType: String?) : PlaybackSource

    /**
     * OneDrive item played by streaming (temporary download URL obtained when opening,
     * never stored here). [cTag]: content version known to the index.
     */
    data class Remote(val itemId: String, val cTag: String?, val mimeType: String?) : PlaybackSource

    /** No playable source right now. */
    data class Unavailable(val reason: UnavailableReason) : PlaybackSource
}

/** Why a media item cannot be played. */
enum class UnavailableReason {
    /** OneDrive item, but access is impossible (signed out, other account, OneDrive not set up). */
    SIGNED_OUT,

    /** No local file, no offline copy and no visible OneDrive item. */
    NO_SOURCE,
}

/**
 * Picks the playback source of a media item, by priority: visible local file, intact offline
 * copy, then OneDrive item streamed if [remoteAllowed] (account signed in, see
 * CloudAccessGuard). Never falls back to the media item's primary URI.
 */
object PlaybackSourceResolver {

    /** Source choice, by priority (pure function). */
    internal enum class Choice { LOCAL, OFFLINE, REMOTE, SIGNED_OUT, NO_SOURCE }

    internal fun choose(
        hasLocalFile: Boolean,
        hasCloudItem: Boolean,
        remoteAllowed: Boolean,
        offlineFileOk: Boolean = false,
    ): Choice = when {
        hasLocalFile -> Choice.LOCAL
        offlineFileOk -> Choice.OFFLINE
        !hasCloudItem -> Choice.NO_SOURCE
        remoteAllowed -> Choice.REMOTE
        else -> Choice.SIGNED_OUT
    }

    /**
     * Source of [details]. [offlineFileOk]: the offline copy exists and has the expected size
     * (checked by the caller on the I/O dispatcher, see [isOfflineFileUsable]).
     */
    fun resolve(details: MediaDetails, remoteAllowed: Boolean, offlineFileOk: Boolean = false): PlaybackSource {
        val local = details.localSource?.uri
        val cloud = details.cloudSource
        val offline = details.offlineCopy
        return when (choose(local != null, cloud != null, remoteAllowed, offlineFileOk && offline != null)) {
            Choice.LOCAL -> PlaybackSource.Local(checkNotNull(local), details.mimeType)
            Choice.OFFLINE -> PlaybackSource.Offline(checkNotNull(offline), details.mimeType)
            Choice.REMOTE -> PlaybackSource.Remote(checkNotNull(cloud).externalId, cloud.cTag, details.mimeType)
            Choice.SIGNED_OUT -> PlaybackSource.Unavailable(UnavailableReason.SIGNED_OUT)
            Choice.NO_SOURCE -> PlaybackSource.Unavailable(UnavailableReason.NO_SOURCE)
        }
    }

    /**
     * True if playing [details] would go through OneDrive (access must then be checked).
     * [offlineFileOk]: usable offline copy.
     */
    fun needsRemote(details: MediaDetails, offlineFileOk: Boolean = false): Boolean =
        choose(details.localSource?.uri != null, details.cloudSource != null, remoteAllowed = true, offlineFileOk) == Choice.REMOTE

    /** Offline copy present with the recorded size (call on the I/O dispatcher). */
    fun isOfflineFileUsable(file: OfflineFile?): Boolean =
        file != null && file.file.isFile && (file.sizeBytes <= 0 || file.file.length() == file.sizeBytes)

    /** Local progressive file: ExoPlayer detects the container itself (no forced MIME type). */
    fun toMediaItem(source: PlaybackSource.Local): MediaItem = MediaItem.fromUri(source.uri)

    /** Offline copy: app-owned file played directly (`file://`). */
    fun toMediaItem(source: PlaybackSource.Offline): MediaItem = MediaItem.fromUri(Uri.fromFile(source.file.file))
}
