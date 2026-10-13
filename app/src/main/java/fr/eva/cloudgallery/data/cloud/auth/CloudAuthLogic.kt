package fr.eva.cloudgallery.data.cloud.auth

import fr.eva.cloudgallery.domain.model.CloudErrorCode
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder

/**
 * Pure logic of OneDrive authentication (MSAL), testable on the JVM.
 *
 * Requested Microsoft Graph scopes: read-only access to files and profile. Never add `openid`,
 * `profile` or `offline_access` here: MSAL adds them itself to every request.
 */
internal val GRAPH_SCOPES: List<String> = listOf("Files.Read", "User.Read")

/** Margin before expiry below which an in-memory token is renewed. */
internal const val TOKEN_EXPIRY_SKEW_MS = 5 * 60_000L

private val CLIENT_ID_REGEX = Regex("^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$")

/** SHA-1 hash of the signing certificate, in Base64 (28 characters). */
private val SIGNATURE_HASH_REGEX = Regex("^[A-Za-z0-9+/]{27}=$")

/** Entra application id (GUID). */
internal fun isValidClientId(value: String): Boolean = CLIENT_ID_REGEX.matches(value)

internal fun isValidSignatureHash(value: String): Boolean = SIGNATURE_HASH_REGEX.matches(value)

/**
 * MSAL redirect URI as registered in Entra: the hash is URL-encoded in it
 * (`+` -> `%2B`, `/` -> `%2F`, `=` -> `%3D`). The manifest, on the other hand, uses the raw hash.
 */
internal fun msalRedirectUri(packageName: String, signatureHash: String): String =
    "msauth://$packageName/${URLEncoder.encode(signatureHash, Charsets.UTF_8)}"

/**
 * MSAL configuration: single account, personal Microsoft accounts only (audience
 * `PersonalMicrosoftAccount`, tenant `consumers`), default browser, no broker.
 */
internal fun buildMsalConfigJson(clientId: String, redirectUri: String): String =
    buildJsonObject {
        put("client_id", clientId)
        put("authorization_user_agent", "DEFAULT")
        put("redirect_uri", redirectUri)
        put("account_mode", "SINGLE")
        put("broker_redirect_uri_registered", false)
        putJsonArray("authorities") {
            addJsonObject {
                put("type", "AAD")
                putJsonObject("audience") {
                    put("type", "PersonalMicrosoftAccount")
                    put("tenant_id", "consumers")
                }
                put("default", true)
            }
        }
    }.toString()

/** True if a token expiring at [expiresAtMs] is still usable at [nowMs] with the margin [skewMs]. */
internal fun isTokenFresh(expiresAtMs: Long, nowMs: Long, skewMs: Long = TOKEN_EXPIRY_SKEW_MS): Boolean =
    expiresAtMs - skewMs > nowMs

/** MSAL exception family (the Java type, reduced to what matters for the decision). */
internal enum class MsalExceptionKind { USER_CANCEL, UI_REQUIRED, DECLINED_SCOPE, CLIENT, SERVICE, OTHER }

/** What to do after an MSAL failure. */
internal enum class MsalFailure {
    /** The user closed the sign-in page. */
    CANCELLED,

    /** Transient (network, I/O, duplicate command, service temporarily unavailable): the session stays valid. */
    TRANSIENT,

    /** Interaction needed: the session stays known, "sign-in required again" state. */
    UI_REQUIRED,

    /** No current account left on the MSAL side: re-read the state (signed out). */
    NO_ACCOUNT,

    DECLINED,
    CONFIG,
    NO_BROWSER,
    SERVER,
    UNKNOWN,
}

/**
 * Classifies an MSAL failure from its type and error code. Offline with an expired token, MSAL
 * returns `device_network_not_available` or `io_error`: definitely not a re-authentication.
 */
internal fun classifyMsalError(kind: MsalExceptionKind, errorCode: String?): MsalFailure = when (kind) {
    MsalExceptionKind.USER_CANCEL -> MsalFailure.CANCELLED
    MsalExceptionKind.UI_REQUIRED -> MsalFailure.UI_REQUIRED
    MsalExceptionKind.DECLINED_SCOPE -> MsalFailure.DECLINED
    MsalExceptionKind.CLIENT -> when (errorCode) {
        "device_network_not_available", "io_error", "duplicate_command" -> MsalFailure.TRANSIENT
        "no_current_account", "current_account_mismatch" -> MsalFailure.NO_ACCOUNT
        "chrome_not_installed" -> MsalFailure.NO_BROWSER
        "redirect_uri_validation_error", "app_manifest_validation_error",
        "multiple_apps_listening_url_scheme", "unknown_authority", "json_parse_failure",
        -> MsalFailure.CONFIG
        else -> MsalFailure.UNKNOWN
    }
    MsalExceptionKind.SERVICE -> when (errorCode) {
        "service_not_available", "request_timeout" -> MsalFailure.TRANSIENT
        "access_denied" -> MsalFailure.DECLINED
        "unauthorized_client", "invalid_scope", "invalid_instance", "invalid_request" -> MsalFailure.CONFIG
        else -> MsalFailure.SERVER
    }
    MsalExceptionKind.OTHER -> MsalFailure.UNKNOWN
}

/** Error code shown to the UI; null for a cancellation (no message). */
internal fun MsalFailure.toErrorCode(): CloudErrorCode? = when (this) {
    MsalFailure.CANCELLED -> null
    MsalFailure.TRANSIENT -> CloudErrorCode.NETWORK
    MsalFailure.UI_REQUIRED, MsalFailure.NO_ACCOUNT -> CloudErrorCode.AUTH_REQUIRED
    MsalFailure.DECLINED -> CloudErrorCode.DECLINED
    MsalFailure.CONFIG -> CloudErrorCode.CONFIG
    MsalFailure.NO_BROWSER -> CloudErrorCode.NO_BROWSER
    MsalFailure.SERVER -> CloudErrorCode.SERVER
    MsalFailure.UNKNOWN -> CloudErrorCode.UNKNOWN
}
