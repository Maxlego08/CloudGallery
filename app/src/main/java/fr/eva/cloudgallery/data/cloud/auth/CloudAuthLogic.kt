package fr.eva.cloudgallery.data.cloud.auth

import fr.eva.cloudgallery.domain.model.CloudErrorCode
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder

/**
 * Logique pure de l'authentification OneDrive (MSAL), testable sur la JVM.
 *
 * Portées Microsoft Graph demandées : lecture seule des fichiers et du profil. Ne jamais y
 * ajouter `openid`, `profile` ni `offline_access` : MSAL les ajoute lui-même à chaque requête.
 */
internal val GRAPH_SCOPES: List<String> = listOf("Files.Read", "User.Read")

/** Marge avant expiration en deçà de laquelle un jeton en mémoire est renouvelé. */
internal const val TOKEN_EXPIRY_SKEW_MS = 5 * 60_000L

private val CLIENT_ID_REGEX = Regex("^[0-9a-fA-F]{8}-([0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}$")

/** Empreinte SHA-1 du certificat de signature, en Base64 (28 caractères). */
private val SIGNATURE_HASH_REGEX = Regex("^[A-Za-z0-9+/]{27}=$")

/** Identifiant d'application Entra (GUID). */
internal fun isValidClientId(value: String): Boolean = CLIENT_ID_REGEX.matches(value)

internal fun isValidSignatureHash(value: String): Boolean = SIGNATURE_HASH_REGEX.matches(value)

/**
 * URI de redirection MSAL telle qu'enregistrée dans Entra : l'empreinte y est encodée en URL
 * (`+` -> `%2B`, `/` -> `%2F`, `=` -> `%3D`). Le manifeste, lui, utilise l'empreinte brute.
 */
internal fun msalRedirectUri(packageName: String, signatureHash: String): String =
    "msauth://$packageName/${URLEncoder.encode(signatureHash, Charsets.UTF_8)}"

/**
 * Configuration MSAL : compte unique, comptes Microsoft personnels uniquement (audience
 * `PersonalMicrosoftAccount`, locataire `consumers`), navigateur par défaut, sans broker.
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

/** Vrai si un jeton expirant à [expiresAtMs] reste utilisable à [nowMs] avec la marge [skewMs]. */
internal fun isTokenFresh(expiresAtMs: Long, nowMs: Long, skewMs: Long = TOKEN_EXPIRY_SKEW_MS): Boolean =
    expiresAtMs - skewMs > nowMs

/** Famille d'exception MSAL (le type Java, réduit à ce qui compte pour la décision). */
internal enum class MsalExceptionKind { USER_CANCEL, UI_REQUIRED, DECLINED_SCOPE, CLIENT, SERVICE, OTHER }

/** Conduite à tenir après un échec MSAL. */
internal enum class MsalFailure {
    /** L'utilisateur a fermé la page de connexion. */
    CANCELLED,

    /** Transitoire (réseau, E/S, commande en double, service momentanément indisponible) : la session reste valide. */
    TRANSIENT,

    /** Interaction nécessaire : la session reste connue, état « reconnexion requise ». */
    UI_REQUIRED,

    /** Plus de compte courant côté MSAL : relire l'état (déconnecté). */
    NO_ACCOUNT,

    DECLINED,
    CONFIG,
    NO_BROWSER,
    SERVER,
    UNKNOWN,
}

/**
 * Classe un échec MSAL d'après son type et son code d'erreur. Hors ligne avec un jeton expiré,
 * MSAL renvoie `device_network_not_available` ou `io_error` : surtout pas une reconnexion.
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

/** Code d'erreur présenté à l'UI ; null pour une annulation (aucun message). */
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
