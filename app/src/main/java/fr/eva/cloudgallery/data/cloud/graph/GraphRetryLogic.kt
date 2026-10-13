package fr.eva.cloudgallery.data.cloud.graph

import fr.eva.cloudgallery.domain.model.CloudErrorCode
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.min

/*
 * Logique pure des appels Microsoft Graph (testée en JVM) : classement des statuts HTTP,
 * Retry-After, attente exponentielle et URL de reprise après un 410.
 */

/** Hôte de Microsoft Graph : le jeton n'est jamais envoyé ailleurs. */
internal const val GRAPH_HOST = "graph.microsoft.com"

/** Attente exponentielle sans Retry-After : 2 s, 4 s, 8 s… plafonnée à 60 s. */
internal const val BACKOFF_BASE_MS = 2_000L
internal const val BACKOFF_CAP_MS = 60_000L

/** Nombre maximal de nouvelles tentatives pour une même requête limitée (429/503…). */
internal const val MAX_RETRIES = 5

/** Retry-After plus long : on abandonne (la synchro sera retentée plus tard). */
internal const val RETRY_AFTER_MAX_MS = 10 * 60_000L

/** Nouvelles tentatives après une erreur réseau (connexion coupée, délai dépassé). */
internal const val IO_MAX_RETRIES = 2

internal enum class GraphStatusAction {
    SUCCESS,

    /** 401 : jeton refusé, renouvellement forcé puis une seule nouvelle tentative. */
    REFRESH_TOKEN,

    /** Limitation ou indisponibilité passagère : attendre puis réessayer. */
    RETRY,

    /** 410 Gone : jeton delta expiré, énumération complète à reprendre. */
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

/** Retry-After en secondes (seule forme renvoyée par Graph) ; null si absent, négatif ou illisible. */
internal fun parseRetryAfterMs(header: String?): Long? {
    val seconds = header?.trim()?.toLongOrNull() ?: return null
    if (seconds < 0) return null
    return if (seconds > Long.MAX_VALUE / 1000) Long.MAX_VALUE else seconds * 1000
}

/**
 * Attente avant la tentative suivante ([attempt] = nombre de tentatives déjà refaites), ou null
 * pour abandonner : trop de tentatives, ou Retry-After au-delà de [RETRY_AFTER_MAX_MS].
 * Retry-After est toujours respecté ; à défaut, attente exponentielle plafonnée.
 */
internal fun retryDelayMs(attempt: Int, retryAfterMs: Long?): Long? {
    if (attempt >= MAX_RETRIES) return null
    if (retryAfterMs != null) return if (retryAfterMs > RETRY_AFTER_MAX_MS) null else retryAfterMs
    return min(BACKOFF_CAP_MS, BACKOFF_BASE_MS shl attempt.coerceIn(0, 20))
}

/** Après un 410 : URL du header Location (nouvelle énumération) si elle vise Graph, sinon delta initial. */
internal fun resyncStartUrl(location: String?, initialUrl: String): String =
    location?.trim()?.takeIf { it.isNotEmpty() && isTrustedGraphUrl(it) } ?: initialUrl

/** Seules les URL https de Microsoft Graph reçoivent le jeton (nextLink, deltaLink, Location). */
internal fun isTrustedGraphUrl(url: HttpUrl): Boolean = url.isHttps && url.host == GRAPH_HOST

internal fun isTrustedGraphUrl(url: String): Boolean =
    url.toHttpUrlOrNull()?.let { isTrustedGraphUrl(it) } ?: false

/** Erreur présentée à l'utilisateur pour un statut HTTP en échec. */
internal fun errorCodeForStatus(code: Int): CloudErrorCode = when (code) {
    401 -> CloudErrorCode.AUTH_REQUIRED
    403 -> CloudErrorCode.DECLINED
    429 -> CloudErrorCode.THROTTLED
    in 500..599 -> CloudErrorCode.SERVER
    else -> CloudErrorCode.UNKNOWN
}
