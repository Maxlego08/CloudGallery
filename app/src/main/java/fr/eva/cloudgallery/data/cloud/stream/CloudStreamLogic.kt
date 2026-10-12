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
 * Logique pure de la lecture en streaming OneDrive (testée en JVM, sans Media3 ni android.net.Uri).
 */

/** Schéma de l'URI stable d'un élément OneDrive lu en streaming (jamais l'URL de téléchargement). */
internal const val STREAM_SCHEME = "onedrive"

/**
 * URI stable `onedrive://item/<id>` : URI du MediaItem et URI rapportée par Media3 dans ses
 * événements et erreurs, à la place de l'URL pré-authentifiée.
 */
internal fun streamUriString(itemId: String): String =
    "$STREAM_SCHEME://item/${URLEncoder.encode(itemId, Charsets.UTF_8)}"

/** Cause d'un échec d'obtention de l'URL de lecture, à partir de l'exception de Graph ou de MSAL. */
internal fun cloudStreamFailureFor(error: Throwable): CloudStreamFailure = when (error) {
    is CloudAuthRequiredException -> CloudStreamFailure.AUTH_REQUIRED
    is CloudThrottledException -> CloudStreamFailure.THROTTLED
    // 410 Gone sur un élément : il n'existe plus.
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

/** Décision de nouvelle tentative après une erreur de chargement en streaming. */
internal enum class StreamRetry {
    /** URL probablement expirée : l'oublier, puis politique par défaut (nouvelle URL à la tentative suivante). */
    INVALIDATE_URL_THEN_DEFAULT,

    /** Inutile de réessayer (reconnexion nécessaire, élément supprimé, limitation Graph). */
    FATAL,

    /** Politique par défaut de Media3 (quelques tentatives espacées). */
    DEFAULT,
}

/**
 * [httpStatus] : statut renvoyé par l'hôte de téléchargement (null si l'erreur n'est pas une
 * réponse HTTP) ; [cloudFailure] : échec d'obtention de l'URL auprès de Graph (null sinon).
 */
internal fun streamRetryDecision(httpStatus: Int?, cloudFailure: CloudStreamFailure?): StreamRetry = when {
    cloudFailure == CloudStreamFailure.AUTH_REQUIRED ||
        cloudFailure == CloudStreamFailure.NOT_FOUND ||
        cloudFailure == CloudStreamFailure.THROTTLED -> StreamRetry.FATAL
    cloudFailure != null -> StreamRetry.DEFAULT
    httpStatus != null && isExpiredUrlStatus(httpStatus) -> StreamRetry.INVALIDATE_URL_THEN_DEFAULT
    else -> StreamRetry.DEFAULT
}

/** Chaîne des causes d'une erreur, bornée (une chaîne cyclique ne bloque jamais). */
internal fun causeChain(error: Throwable?): Sequence<Throwable> =
    generateSequence(error) { it.cause?.takeIf { cause -> cause !== it } }.take(MAX_CAUSES)

private val URL_PATTERN = Regex("""(?i)\b(?:https?|wss?)://[^\s"'<>]+""")
private val TEMPAUTH_PATTERN = Regex("""(?i)tempauth=[^\s&"'<>]+""")

/**
 * Texte de journal sans URL : une URL de téléchargement OneDrive vaut un jeton (paramètre
 * `tempauth`), elle ne doit jamais apparaître dans logcat.
 */
internal fun redactUrls(text: String): String =
    TEMPAUTH_PATTERN.replace(URL_PATTERN.replace(text, "<url>"), "tempauth=<redacted>")

private const val HTTP_NOT_FOUND = 404
private const val HTTP_GONE = 410
private const val MAX_CAUSES = 16
