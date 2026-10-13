package fr.eva.cloudgallery.data.cloud.stream

import fr.eva.cloudgallery.data.cloud.graph.GraphHttpException
import fr.eva.cloudgallery.data.cloud.graph.GraphResyncRequiredException
import fr.eva.cloudgallery.data.cloud.transfer.isExpiredUrlStatus
import fr.eva.cloudgallery.domain.model.CloudAuthRequiredException
import fr.eva.cloudgallery.domain.model.CloudErrorCode
import fr.eva.cloudgallery.domain.model.CloudException
import fr.eva.cloudgallery.domain.model.CloudStreamFailure
import fr.eva.cloudgallery.domain.model.CloudThrottledException
import java.io.IOException
import java.net.URLEncoder

/*
 * Pure logic for OneDrive streaming playback (tested on the JVM, without Media3 or android.net.Uri).
 */

/** Scheme of the stable URI of a streamed OneDrive item (never the download URL). */
internal const val STREAM_SCHEME = "onedrive"

/**
 * Stable URI `onedrive://item/<id>`: the MediaItem URI and the URI Media3 reports in its events
 * and errors, instead of the pre-authenticated URL.
 */
internal fun streamUriString(itemId: String): String =
    "$STREAM_SCHEME://item/${URLEncoder.encode(itemId, Charsets.UTF_8)}"

/** Cause of a failure to obtain the playback URL, from the Graph or MSAL exception. */
internal fun cloudStreamFailureFor(error: Throwable): CloudStreamFailure = when (error) {
    is CloudAuthRequiredException -> CloudStreamFailure.AUTH_REQUIRED
    is CloudThrottledException -> CloudStreamFailure.THROTTLED
    // 410 Gone on an item: it no longer exists.
    is GraphResyncRequiredException -> CloudStreamFailure.NOT_FOUND
    is GraphHttpException ->
        if (error.status == HTTP_NOT_FOUND || error.status == HTTP_GONE) CloudStreamFailure.NOT_FOUND else CloudStreamFailure.SERVER
    is CloudException -> when (error.code) {
        CloudErrorCode.NETWORK -> CloudStreamFailure.NETWORK
        CloudErrorCode.AUTH_REQUIRED -> CloudStreamFailure.AUTH_REQUIRED
        CloudErrorCode.THROTTLED -> CloudStreamFailure.THROTTLED
        else -> CloudStreamFailure.SERVER
    }
    is IOException -> CloudStreamFailure.NETWORK
    else -> CloudStreamFailure.SERVER
}

/** Retry decision after a streaming load error. */
internal enum class StreamRetry {
    /** URL probably expired: forget it, then default policy (new URL on the next attempt). */
    INVALIDATE_URL_THEN_DEFAULT,

    /** No point retrying (sign-in required, item deleted, Graph throttling). */
    FATAL,

    /** Media3's default policy (a few spaced-out attempts). */
    DEFAULT,
}

/**
 * [httpStatus]: status returned by the download host (null if the error is not an HTTP
 * response); [cloudFailure]: failure to obtain the URL from Graph (null otherwise).
 */
internal fun streamRetryDecision(httpStatus: Int?, cloudFailure: CloudStreamFailure?): StreamRetry = when {
    cloudFailure == CloudStreamFailure.AUTH_REQUIRED ||
        cloudFailure == CloudStreamFailure.NOT_FOUND ||
        cloudFailure == CloudStreamFailure.THROTTLED -> StreamRetry.FATAL
    cloudFailure != null -> StreamRetry.DEFAULT
    httpStatus != null && isExpiredUrlStatus(httpStatus) -> StreamRetry.INVALIDATE_URL_THEN_DEFAULT
    else -> StreamRetry.DEFAULT
}

/** Cause chain of an error, bounded (a cyclic chain never hangs). */
internal fun causeChain(error: Throwable?): Sequence<Throwable> =
    generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSES)

private val URL_PATTERN = Regex("""(?i)\b(?:https?|wss?)://[^\s"'<>]+""")
private val TEMPAUTH_PATTERN = Regex("""(?i)tempauth=[^\s&"'<>]+""")

/**
 * Log text without URLs: a OneDrive download URL is as good as a token (`tempauth`
 * parameter), it must never appear in logcat.
 */
internal fun redactUrls(text: String): String =
    TEMPAUTH_PATTERN.replace(URL_PATTERN.replace(text, "<url>"), "tempauth=<redacted>")

private const val HTTP_NOT_FOUND = 404
private const val HTTP_GONE = 410
private const val MAX_CAUSES = 16
