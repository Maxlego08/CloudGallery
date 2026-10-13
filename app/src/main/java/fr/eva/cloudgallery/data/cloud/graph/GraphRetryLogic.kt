package fr.eva.cloudgallery.data.cloud.graph

import fr.eva.cloudgallery.domain.model.CloudErrorCode
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.min

/*
 * Pure logic of Microsoft Graph calls (tested on the JVM): HTTP status classification,
 * Retry-After, exponential backoff and resume URL after a 410.
 */

/** Microsoft Graph host: the token is never sent anywhere else. */
internal const val GRAPH_HOST = "graph.microsoft.com"

/** Exponential backoff without Retry-After: 2 s, 4 s, 8 s… capped at 60 s. */
internal const val BACKOFF_BASE_MS = 2_000L
internal const val BACKOFF_CAP_MS = 60_000L

/** Maximum number of retries for the same throttled request (429/503…). */
internal const val MAX_RETRIES = 5

/** Longer Retry-After: give up (the sync will be retried later). */
internal const val RETRY_AFTER_MAX_MS = 10 * 60_000L

/** Retries after a network error (connection dropped, timeout). */
internal const val IO_MAX_RETRIES = 2

internal enum class GraphStatusAction {
    SUCCESS,

    /** 401: token rejected, forced refresh, then a single retry. */
    REFRESH_TOKEN,

    /** Throttling or temporary unavailability: wait, then retry. */
    RETRY,

    /** 410 Gone: delta token expired, full enumeration to restart. */
    RESYNC,
    FAIL,
}

internal fun classifyGraphStatus(code: Int): GraphStatusAction = when (code) {
    in 200..299 -> GraphStatusAction.SUCCESS
    401 -> GraphStatusAction.REFRESH_TOKEN
    429, 502, 503, 504 -> GraphStatusAction.RETRY
    410 -> GraphStatusAction.RESYNC
    else -> GraphStatusAction.FAIL
}

/** Retry-After in seconds (the only form Graph returns); null if absent, negative or unreadable. */
internal fun parseRetryAfterMs(header: String?): Long? {
    val seconds = header?.trim()?.toLongOrNull() ?: return null
    if (seconds < 0) return null
    return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
}

/**
 * Wait before the next attempt ([attempt] = number of retries already made), or null to give
 * up: too many attempts, or Retry-After beyond [RETRY_AFTER_MAX_MS].
 * Retry-After is always honored; otherwise, capped exponential backoff.
 */
internal fun retryDelayMs(attempt: Int, retryAfterMs: Long?): Long? {
    if (attempt >= MAX_RETRIES) return null
    if (retryAfterMs != null) return if (retryAfterMs > RETRY_AFTER_MAX_MS) null else retryAfterMs
    return min(BACKOFF_CAP_MS, BACKOFF_BASE_MS shl attempt.coerceIn(0, 20))
}

/** After a 410: URL from the Location header (new enumeration) if it targets Graph, else initial delta. */
internal fun resyncStartUrl(location: String?, initialUrl: String): String =
    location?.trim()?.takeIf { it.isNotEmpty() && isTrustedGraphUrl(it) } ?: initialUrl

/** Only Microsoft Graph https URLs receive the token (nextLink, deltaLink, Location). */
internal fun isTrustedGraphUrl(url: HttpUrl): Boolean = url.isHttps && url.host == GRAPH_HOST

internal fun isTrustedGraphUrl(url: String): Boolean =
    url.toHttpUrlOrNull()?.let { isTrustedGraphUrl(it) } ?: false

/** Error shown to the user for a failed HTTP status. */
internal fun errorCodeForStatus(code: Int): CloudErrorCode = when (code) {
    401 -> CloudErrorCode.AUTH_REQUIRED
    403 -> CloudErrorCode.DECLINED
    429 -> CloudErrorCode.THROTTLED
    in 500..599 -> CloudErrorCode.SERVER
    else -> CloudErrorCode.UNKNOWN
}
