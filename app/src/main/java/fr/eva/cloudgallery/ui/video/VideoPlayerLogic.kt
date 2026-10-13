package fr.eva.cloudgallery.ui.video

import androidx.media3.common.PlaybackException
import fr.eva.cloudgallery.domain.model.CloudStreamException
import fr.eva.cloudgallery.domain.model.CloudStreamFailure
import java.util.Locale

/** Playback error categories shown to the user. */
enum class PlaybackErrorKind {
    /** Container or codec not supported by ExoPlayer / the device's decoders. */
    UNSUPPORTED_FORMAT,

    /** Truncated or damaged file (malformed container, decoding failure). */
    CORRUPTED_FILE,

    /** File deleted, moved, or whose access has been revoked. */
    FILE_NOT_ACCESSIBLE,

    /** Other input/output errors (reading the file). */
    IO,

    /** No connection or timeout (streaming). */
    NETWORK,

    /** Failed HTTP response from the download host, even with a fresh URL. */
    SERVER_OR_LINK,

    /** OneDrive account must be signed in (signed out, expired session, or another account than the index). */
    AUTH_REQUIRED,

    /** Microsoft Graph asks us to wait (throttling). */
    CLOUD_THROTTLED,

    /** Item not found on OneDrive (deleted or moved). */
    CLOUD_NOT_FOUND,

    /** The media item no longer exists in the library. */
    MEDIA_NOT_FOUND,

    /** Media item only on OneDrive, and OneDrive is unavailable in this build. */
    CLOUD_ONLY,

    OTHER,
}

/** Pure player functions (unit tested). */
object VideoPlayerLogic {

    /** Offered speeds, like the Samsung Gallery player. */
    val PLAYBACK_SPEEDS: List<Float> = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    const val SEEK_INCREMENT_MS: Long = 10_000L
    const val SAVE_INTERVAL_MS: Long = 5_000L

    /** Below this threshold, there is no point resuming playback mid-way. */
    const val MIN_RESUME_POSITION_MS: Long = 2_000L

    /** Beyond (duration - threshold), the video is considered finished. */
    const val END_THRESHOLD_MS: Long = 2_000L

    /**
     * Maps a playback error to a displayable category: the OneDrive failure found in the cause
     * chain ([cloudFailure], see [cloudFailureOf]) first, then the
     * [PlaybackException.errorCode].
     */
    fun classifyError(errorCode: Int, cloudFailure: CloudStreamFailure? = null): PlaybackErrorKind = when (cloudFailure) {
        CloudStreamFailure.AUTH_REQUIRED -> PlaybackErrorKind.AUTH_REQUIRED
        CloudStreamFailure.THROTTLED -> PlaybackErrorKind.CLOUD_THROTTLED
        CloudStreamFailure.NOT_FOUND -> PlaybackErrorKind.CLOUD_NOT_FOUND
        CloudStreamFailure.NETWORK -> PlaybackErrorKind.NETWORK
        CloudStreamFailure.SERVER -> PlaybackErrorKind.SERVER_OR_LINK
        null -> classifyErrorCode(errorCode)
    }

    private fun classifyErrorCode(errorCode: Int): PlaybackErrorKind = when (errorCode) {
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,
        PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
        PlaybackException.ERROR_CODE_NOT_SUPPORTED,
        -> PlaybackErrorKind.UNSUPPORTED_FORMAT

        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        -> PlaybackErrorKind.CORRUPTED_FILE

        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        -> PlaybackErrorKind.FILE_NOT_ACCESSIBLE

        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> PlaybackErrorKind.NETWORK

        // After the retries with a renewed URL (failed HTTP status).
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PlaybackErrorKind.SERVER_OR_LINK

        in IO_RANGE -> PlaybackErrorKind.IO
        else -> PlaybackErrorKind.OTHER
    }

    /** First OneDrive failure ([CloudStreamException]) in an error's cause chain. */
    fun cloudFailureOf(causes: Sequence<Throwable>): CloudStreamFailure? =
        causes.take(MAX_CAUSES).firstNotNullOfOrNull { (it as? CloudStreamException)?.failure }

    /** Category shown when no source is playable. */
    fun unavailableError(reason: UnavailableReason, cloudConfigured: Boolean): PlaybackErrorKind = when (reason) {
        UnavailableReason.SIGNED_OUT -> if (cloudConfigured) PlaybackErrorKind.AUTH_REQUIRED else PlaybackErrorKind.CLOUD_ONLY
        UnavailableReason.NO_SOURCE -> PlaybackErrorKind.FILE_NOT_ACCESSIBLE
    }

    /** Format errors can be worked around by opening the video in another app. */
    fun canOpenExternally(kind: PlaybackErrorKind): Boolean =
        kind == PlaybackErrorKind.UNSUPPORTED_FORMAT || kind == PlaybackErrorKind.CORRUPTED_FILE ||
            kind == PlaybackErrorKind.OTHER

    /** "Retry" is pointless: no new attempt would change the outcome. */
    fun canRetry(kind: PlaybackErrorKind): Boolean =
        kind != PlaybackErrorKind.CLOUD_ONLY && kind != PlaybackErrorKind.AUTH_REQUIRED &&
            kind != PlaybackErrorKind.CLOUD_NOT_FOUND

    /** The user must (re)connect their OneDrive account: "Sign in" action. */
    fun needsSignIn(kind: PlaybackErrorKind): Boolean = kind == PlaybackErrorKind.AUTH_REQUIRED

    /** Error related to OneDrive or the network ("cloud" icon rather than a file error). */
    fun isCloudError(kind: PlaybackErrorKind): Boolean = when (kind) {
        PlaybackErrorKind.CLOUD_ONLY,
        PlaybackErrorKind.AUTH_REQUIRED,
        PlaybackErrorKind.CLOUD_THROTTLED,
        PlaybackErrorKind.CLOUD_NOT_FOUND,
        PlaybackErrorKind.SERVER_OR_LINK,
        -> true
        else -> false
    }

    /**
     * Resume position: 0 if the saved position is too close to the start or the end
     * (in the latter case the video had been watched to the end).
     */
    fun resumePosition(savedMs: Long, durationMs: Long): Long = when {
        savedMs < MIN_RESUME_POSITION_MS -> 0L
        durationMs > 0 && savedMs >= durationMs - END_THRESHOLD_MS -> 0L
        else -> savedMs
    }

    /** Target position of a relative seek, clamped to [0, duration]. */
    fun seekTarget(currentMs: Long, deltaMs: Long, durationMs: Long): Long {
        val target = (currentMs + deltaMs).coerceAtLeast(0L)
        return if (durationMs > 0) target.coerceAtMost(durationMs) else target
    }

    /** Zone of a double tap: -1 left third, +1 right third, 0 center. */
    fun doubleTapZone(x: Float, width: Float): Int = when {
        width <= 0f -> 0
        x < width / 3f -> -1
        x > width * 2f / 3f -> 1
        else -> 0
    }

    /** Speed label: "1x", "0.25x", "1.5x". */
    fun speedLabel(speed: Float): String {
        val text = if (speed == speed.toLong().toFloat()) {
            speed.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')
        }
        return "${text}x"
    }

    /** Display aspect ratio of a video (width / height), or null if unknown. */
    fun aspectRatio(width: Int, height: Int, pixelRatio: Float = 1f): Float? =
        if (width <= 0 || height <= 0) null else width * pixelRatio / height

    private val IO_RANGE = 2000..2999
    private const val MAX_CAUSES = 16
}
