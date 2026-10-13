package fr.eva.cloudgallery.domain.model

import java.io.IOException

/** Signed-in Microsoft account (single-account mode). The username is never logged. */
/**
 * Signed-in Microsoft account. [id]: opaque MSAL identifier (never displayed or logged:
 * [toString] does not include it), used to check that the index belongs to this account.
 */
data class CloudAccount(val username: String?, val id: String? = null) {
    override fun toString(): String = "CloudAccount"
}

/**
 * Cause of a cloud failure, language-independent: the displayed text is resolved by the UI
 * (so a persisted error stays correct right after a language change).
 */
enum class CloudErrorCode {
    /** No network or timeout: retry later, the session remains valid. */
    NETWORK,

    /** Session expired or revoked: an interactive sign-in is required. */
    AUTH_REQUIRED,

    /** Too many requests: Microsoft Graph asks to wait. */
    THROTTLED,

    /** Error on the Microsoft service side. */
    SERVER,

    /** Invalid MSAL configuration (client ID, redirect URI, manifest). */
    CONFIG,

    /** Permissions declined by the user. */
    DECLINED,

    /** No compatible browser for the Microsoft sign-in page. */
    NO_BROWSER,

    UNKNOWN,
}

/** State of the OneDrive account. */
sealed interface CloudAccountState {
    /** Build without an MSAL client ID: the feature is hidden. */
    data object NotConfigured : CloudAccountState

    /** State not read yet (at process start). */
    data object Loading : CloudAccountState

    /**
     * No account. [hasLeftoverData]: OneDrive data from a previous session remains in the
     * local index (never deleted without explicit confirmation from the user).
     */
    data class SignedOut(val hasLeftoverData: Boolean) : CloudAccountState

    data class SignedIn(val account: CloudAccount) : CloudAccountState

    /** Known account but expired session: cached data is kept. */
    data class NeedsReauth(val account: CloudAccount) : CloudAccountState

    /** Sign-out in progress (no sync may start in the meantime). */
    data object SigningOut : CloudAccountState

    data class Error(val code: CloudErrorCode) : CloudAccountState
}

/** Outcome of an interactive sign-in. */
sealed interface SignInOutcome {
    data object Success : SignInOutcome
    data object Cancelled : SignInOutcome
    data class Failed(val code: CloudErrorCode) : SignInOutcome
}

/** Typed cloud failure (the displayed message is resolved by the UI from [code]). */
open class CloudException(
    val code: CloudErrorCode,
    cause: Throwable? = null,
) : Exception("OneDrive failure: $code", cause)

/** No token usable without interaction: sign-in required (or no account). */
class CloudAuthRequiredException(cause: Throwable? = null) : CloudException(CloudErrorCode.AUTH_REQUIRED, cause)

/** Microsoft Graph requests throttled: do not retry before [retryAfterMs] (ms, 0 if unknown). */
class CloudThrottledException(val retryAfterMs: Long, cause: Throwable? = null) :
    CloudException(CloudErrorCode.THROTTLED, cause)

/** Microsoft Graph profile of the signed-in account (loaded on demand, never logged). */
data class CloudProfile(
    val displayName: String?,
    val email: String?,
    /** Used / total space in bytes (unified Microsoft account quota), null if unknown. */
    val quotaUsed: Long?,
    val quotaTotal: Long?,
)

/** Origin of a OneDrive sync request. */
enum class CloudSyncReason {
    /** App back in the foreground: skipped if the last sync is recent. */
    FOREGROUND,

    /** "Sync now". */
    MANUAL,

    /** Successful sign-in (or re-sign-in). */
    SIGN_IN,

    /** Periodic background sync (WorkManager): skipped if the last one is recent. */
    BACKGROUND,
}

/** State of the OneDrive -> local index sync. */
data class CloudSyncState(
    val running: Boolean = false,
    /** Items received from Microsoft Graph during the current sync. */
    val processedItems: Int = 0,
    /** OneDrive items visible in the index. */
    val itemCount: Int = 0,
    val lastSyncAt: Long = 0L,
    val lastFullSyncAt: Long = 0L,
    /** Cause of the last failure (text resolved by the UI), null if the last sync succeeded. */
    val lastError: CloudErrorCode? = null,
    /** Graph throttling: no request before this instant (ms). */
    val retryNotBefore: Long = 0L,
    /**
     * The signed-in OneDrive is not the one the index data belongs to: sync is suspended
     * until the user confirms the replacement (never automatic).
     */
    val accountMismatch: Boolean = false,
)

/**
 * Reference to the Microsoft Graph thumbnail of a OneDrive item: item ID and content version
 * (cTag, which does not change on a mere rename). Used as a cache key.
 */
data class CloudThumbRef(val itemId: String, val version: String)

/**
 * Image model (Coil) for a OneDrive thumbnail. [previewSide]: large preview with the original
 * proportions (viewer) fitting in a square of this side (px), instead of a square thumbnail
 * sized to the cell (null).
 */
data class CloudThumbnail(val ref: CloudThumbRef, val previewSide: Int? = null)

/**
 * Temporary access to the content of a OneDrive item (`@microsoft.graph.downloadUrl`). [url] is
 * a pre-authenticated URL that is as good as a token: never persisted, never logged, never
 * sent with an Authorization header. Plain class (not a data class): [toString] does not
 * include it.
 */
class CloudDownloadInfo(
    val itemId: String,
    val sizeBytes: Long,
    /** Content version (cTag), null if Graph did not return it. */
    val cTag: String?,
    val eTag: String?,
    /** OneDrive drive of the item (compared with the index drive before any persistent download). */
    val driveId: String?,
    val url: String,
) {
    override fun toString(): String = "CloudDownloadInfo(itemId=$itemId, sizeBytes=$sizeBytes)"
}

/** Cause of a streaming playback failure for a OneDrive item, language-independent. */
enum class CloudStreamFailure {
    /** Account signed out, session expired, or a OneDrive different from the index one. */
    AUTH_REQUIRED,

    /** Microsoft Graph asks to wait. */
    THROTTLED,

    /** Item not found on OneDrive (deleted or moved out of the drive). */
    NOT_FOUND,

    /** No network or timeout. */
    NETWORK,

    /** Other failure on the Microsoft side. */
    SERVER,
}

/**
 * Failure to obtain the playback URL of a OneDrive item, reported to Media3 as an
 * [IOException]. The message never contains a URL or token (only [failure]).
 */
class CloudStreamException(val failure: CloudStreamFailure, cause: Throwable? = null) :
    IOException("OneDrive playback failed: $failure", cause)

/**
 * Cause of a OneDrive file download failure (original, share, offline copy),
 * language-independent. Persistable by its name (never free text or URL).
 */
enum class DownloadError {
    /** No network, timeout or connection dropped. */
    NETWORK,

    /** Account signed out, session expired, another account, or OneDrive data cleared meanwhile. */
    AUTH_REQUIRED,

    /** Microsoft Graph or the download host asks to wait. */
    THROTTLED,

    /** Item not found on OneDrive. */
    NOT_FOUND,

    /** The content changed on OneDrive during the download. */
    CONTENT_CHANGED,

    /** Not enough storage space on the device. */
    NO_SPACE,

    /** Received size differs from the announced size. */
    SIZE_MISMATCH,

    /** Other service-side failure (unexpected response). */
    SERVER,

    /** Cancelled by the user. */
    CANCELLED,
}

/** Typed OneDrive download failure. The message never contains a URL or token. */
class CloudDownloadException(val error: DownloadError, cause: Throwable? = null) :
    IOException("OneDrive download failed: $error", cause)
