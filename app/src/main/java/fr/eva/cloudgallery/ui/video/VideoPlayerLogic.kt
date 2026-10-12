package fr.eva.cloudgallery.ui.video

import androidx.media3.common.PlaybackException
import fr.eva.cloudgallery.domain.model.CloudStreamException
import fr.eva.cloudgallery.domain.model.CloudStreamFailure
import java.util.Locale

/** Catégories d'erreurs de lecture présentées à l'utilisateur. */
enum class PlaybackErrorKind {
    /** Conteneur ou codec non pris en charge par ExoPlayer / les décodeurs de l'appareil. */
    UNSUPPORTED_FORMAT,

    /** Fichier tronqué ou endommagé (conteneur mal formé, échec de décodage). */
    CORRUPTED_FILE,

    /** Fichier supprimé, déplacé ou dont l'accès a été retiré. */
    FILE_NOT_ACCESSIBLE,

    /** Autres erreurs d'entrée/sortie (lecture du fichier). */
    IO,

    /** Pas de connexion ou délai dépassé (streaming). */
    NETWORK,

    /** Réponse HTTP en échec de l'hôte de téléchargement, même avec une nouvelle URL. */
    SERVER_OR_LINK,

    /** Compte OneDrive à connecter (déconnecté, session expirée ou autre compte que l'index). */
    AUTH_REQUIRED,

    /** Microsoft Graph demande de patienter. */
    CLOUD_THROTTLED,

    /** Elément introuvable sur OneDrive (supprimé ou déplacé). */
    CLOUD_NOT_FOUND,

    /** Le média n'existe plus dans la bibliothèque. */
    MEDIA_NOT_FOUND,

    /** Média présent uniquement sur OneDrive, et OneDrive indisponible dans ce build. */
    CLOUD_ONLY,

    OTHER,
}

/** Fonctions pures du lecteur (testées unitairement). */
object VideoPlayerLogic {

    /** Vitesses proposées, comme le lecteur de Samsung Galerie. */
    val PLAYBACK_SPEEDS: List<Float> = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    const val SEEK_INCREMENT_MS: Long = 10_000L
    const val SAVE_INTERVAL_MS: Long = 5_000L

    /** En dessous de ce seuil, inutile de reprendre la lecture en cours de route. */
    const val MIN_RESUME_POSITION_MS: Long = 2_000L

    /** Au-delà de (durée - seuil), la vidéo est considérée comme terminée. */
    const val END_THRESHOLD_MS: Long = 2_000L

    /**
     * Associe une erreur de lecture à une catégorie affichable : l'échec OneDrive trouvé dans la
     * chaîne des causes ([cloudFailure], cf. [cloudFailureOf]) d'abord, puis le
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

        // Après les nouvelles tentatives avec une URL renouvelée (statut HTTP en échec).
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> PlaybackErrorKind.SERVER_OR_LINK

        in IO_RANGE -> PlaybackErrorKind.IO
        else -> PlaybackErrorKind.OTHER
    }

    /** Premier échec OneDrive ([CloudStreamException]) de la chaîne des causes d'une erreur. */
    fun cloudFailureOf(causes: Sequence<Throwable>): CloudStreamFailure? =
        causes.take(MAX_CAUSES).firstNotNullOfOrNull { (it as? CloudStreamException)?.failure }

    /** Catégorie affichée quand aucune source n'est lisible. */
    fun unavailableError(reason: UnavailableReason, cloudConfigured: Boolean): PlaybackErrorKind = when (reason) {
        UnavailableReason.SIGNED_OUT -> if (cloudConfigured) PlaybackErrorKind.AUTH_REQUIRED else PlaybackErrorKind.CLOUD_ONLY
        UnavailableReason.NO_SOURCE -> PlaybackErrorKind.FILE_NOT_ACCESSIBLE
    }

    /** Les erreurs de format peuvent être contournées en ouvrant la vidéo dans une autre appli. */
    fun canOpenExternally(kind: PlaybackErrorKind): Boolean =
        kind == PlaybackErrorKind.UNSUPPORTED_FORMAT || kind == PlaybackErrorKind.CORRUPTED_FILE ||
            kind == PlaybackErrorKind.OTHER

    /** « Réessayer » inutile : aucune nouvelle tentative ne changerait le résultat. */
    fun canRetry(kind: PlaybackErrorKind): Boolean =
        kind != PlaybackErrorKind.CLOUD_ONLY && kind != PlaybackErrorKind.AUTH_REQUIRED &&
            kind != PlaybackErrorKind.CLOUD_NOT_FOUND

    /** L'utilisateur doit (re)connecter son compte OneDrive : action « Se connecter ». */
    fun needsSignIn(kind: PlaybackErrorKind): Boolean = kind == PlaybackErrorKind.AUTH_REQUIRED

    /** Erreur liée à OneDrive ou au réseau (icône « cloud » plutôt qu'erreur de fichier). */
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
     * Position de reprise : 0 si la position mémorisée est trop proche du début ou de la fin
     * (dans ce dernier cas la vidéo avait été vue jusqu'au bout).
     */
    fun resumePosition(savedMs: Long, durationMs: Long): Long = when {
        savedMs < MIN_RESUME_POSITION_MS -> 0L
        durationMs > 0 && savedMs >= durationMs - END_THRESHOLD_MS -> 0L
        else -> savedMs
    }

    /** Position cible d'un saut relatif, bornée à [0, durée]. */
    fun seekTarget(currentMs: Long, deltaMs: Long, durationMs: Long): Long {
        val target = (currentMs + deltaMs).coerceAtLeast(0L)
        return if (durationMs > 0) target.coerceAtMost(durationMs) else target
    }

    /** Zone d'un double appui : -1 tiers gauche, +1 tiers droit, 0 centre. */
    fun doubleTapZone(x: Float, width: Float): Int = when {
        width <= 0f -> 0
        x < width / 3f -> -1
        x > width * 2f / 3f -> 1
        else -> 0
    }

    /** Libellé de vitesse : « 1x », « 0.25x », « 1.5x ». */
    fun speedLabel(speed: Float): String {
        val text = if (speed == speed.toLong().toFloat()) {
            speed.toLong().toString()
        } else {
            String.format(Locale.ROOT, "%.2f", speed).trimEnd('0').trimEnd('.')
        }
        return "${text}x"
    }

    /** Ratio d'affichage d'une vidéo (largeur / hauteur), ou null si inconnu. */
    fun aspectRatio(width: Int, height: Int, pixelRatio: Float = 1f): Float? =
        if (width <= 0 || height <= 0) null else width * pixelRatio / height

    private val IO_RANGE = 2000..2999
    private const val MAX_CAUSES = 16
}
