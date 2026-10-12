package fr.eva.cloudgallery.data.cloud.thumb

import fr.eva.cloudgallery.domain.model.CloudThumbRef
import java.util.concurrent.ConcurrentHashMap

/*
 * Logique pure des miniatures OneDrive (testée en JVM) : paliers de taille, tailles Microsoft
 * Graph demandées et clés de cache.
 */

/** Préfixe des clés de cache (mémoire et disque) des miniatures OneDrive. */
internal const val CLOUD_THUMB_KEY_PREFIX = "odthumb:"

/** Taille par défaut quand la taille demandée est inconnue / originale (comme les miniatures locales). */
internal const val DEFAULT_CLOUD_THUMB_PX = 384

/**
 * Paliers de taille, identiques à ceux des miniatures locales : de petites variations de taille
 * de cellule (zoom de la grille) réutilisent la même miniature.
 */
internal val CLOUD_THUMB_BUCKETS = intArrayOf(96, 192, 256, 384, 512, 768, 1024)

/** Côté de l'aperçu de la visionneuse par défaut (taille personnalisée, proportions d'origine). */
internal const val CLOUD_PREVIEW_PX = 1600

/**
 * Paliers d'aperçu de la visionneuse (côté du carré dans lequel l'aperçu tient). Le palier 2048
 * reste à valider sur l'appareil : refusé par Graph (400/404) alors que 1600 est accepté, il est
 * abandonné jusqu'au redémarrage (repli `c1600x1600`).
 */
internal val CLOUD_PREVIEW_TIERS = intArrayOf(CLOUD_PREVIEW_PX, 2048)

/** Plus grand palier d'aperçu sur un réseau facturé (données mobiles, économiseur de données). */
internal const val CLOUD_PREVIEW_COSTLY_MAX_PX = CLOUD_PREVIEW_PX

/**
 * Palier d'aperçu pour un affichage dont le grand côté fait [viewportLongPx] : le plus petit
 * palier qui le couvre (sinon le plus grand), jamais au-delà de ce qu'il faut pour l'image
 * elle-même ([imageLongPx], grand côté de l'original s'il est connu), et au plus
 * [CLOUD_PREVIEW_COSTLY_MAX_PX] sur un réseau facturé ([costlyNetwork]).
 */
internal fun previewSideFor(
    viewportLongPx: Int,
    costlyNetwork: Boolean,
    imageLongPx: Int?,
    tiers: IntArray = CLOUD_PREVIEW_TIERS,
): Int {
    val wanted = minOf(viewportLongPx.coerceAtLeast(1), imageLongPx?.takeIf { it > 0 } ?: Int.MAX_VALUE)
    val side = tiers.firstOrNull { it >= wanted } ?: tiers.last()
    return if (costlyNetwork) minOf(side, CLOUD_PREVIEW_COSTLY_MAX_PX) else side
}

/** Palier effectivement demandé : [side] borné au plus grand palier accepté par Graph ([maxAcceptedSide]). */
internal fun cappedPreviewSide(side: Int, maxAcceptedSide: Int): Int = minOf(side, maxAcceptedSide)

/** Palier correspondant au plus grand côté demandé (px). */
internal fun cloudThumbBucket(sidePx: Int): Int {
    val side = sidePx.coerceAtLeast(1)
    return CLOUD_THUMB_BUCKETS.firstOrNull { it >= side } ?: CLOUD_THUMB_BUCKETS.last()
}

/**
 * Taille demandée à Graph ([path], segment `{size}` de `/thumbnails/0/{size}/content`) et taille
 * standard de repli ([fallback]) si la taille personnalisée est refusée.
 */
internal data class CloudThumbSize(val path: String, val fallback: String?)

/**
 * Vignettes carrées : taille personnalisée `c{b}x{b}_crop` au palier près (documentée par
 * Microsoft Graph), sinon `smallSquare` (96 px). Repli : `mediumSquare` (176 px) jusqu'à 256 px,
 * `largeSquare` (800 px) au-delà. Aperçu ([previewSide]) : `c{side}x{side}` (proportions
 * d'origine), repli sur le palier inférieur (`c1600x1600`) puis `large` (800 px) pour 1600 et
 * moins. Les miniatures Graph sont déjà orientées (rotation EXIF appliquée).
 */
internal fun cloudThumbSize(bucketPx: Int, previewSide: Int?): CloudThumbSize = when {
    previewSide != null && previewSide > CLOUD_PREVIEW_PX ->
        CloudThumbSize("c${previewSide}x$previewSide", "c${CLOUD_PREVIEW_PX}x$CLOUD_PREVIEW_PX")
    previewSide != null -> CloudThumbSize("c${previewSide}x$previewSide", "large")
    bucketPx <= 96 -> CloudThumbSize("smallSquare", null)
    else -> CloudThumbSize("c${bucketPx}x${bucketPx}_crop", if (bucketPx <= 256) "mediumSquare" else "largeSquare")
}

/**
 * Chemin réellement demandé : la taille standard dès que Graph a refusé une taille personnalisée
 * (seulement si le repli est lui-même une taille standard ; sinon, taille demandée).
 */
internal fun effectiveThumbPath(size: CloudThumbSize, customSizesRejected: Boolean): String =
    if (customSizesRejected && size.fallback != null && !size.fallback.startsWith("c")) size.fallback else size.path

/**
 * Clé de cache : élément + version du contenu (cTag) + palier de vignette ou d'aperçu
 * (`p<côté>`) ; un renommage ne l'invalide pas.
 */
internal fun cloudThumbKey(ref: CloudThumbRef, bucketPx: Int, previewSide: Int?): String =
    "$CLOUD_THUMB_KEY_PREFIX${ref.itemId}:${ref.version}:${previewSide?.let { "p$it" } ?: bucketPx.toString()}"

/** 400 (taille refusée) ou 404 (taille non générée) : la taille standard de repli est essayée. */
internal fun shouldTryFallbackSize(status: Int): Boolean = status == 400 || status == 404

/** Faut-il l'original OneDrive d'une image affichée en aperçu ? */
enum class OriginalDecision {
    /** L'aperçu suffit (zoom faible, ou image pas plus grande que l'aperçu). */
    NOT_NEEDED,

    /** Télécharger l'original sans demander (réseau non facturé, ou autorisé par le réglage). */
    AUTO,

    /** Proposer « Charger l'original (taille) » (réseau facturé). */
    ASK,
}

/**
 * Original utile quand le zoom profond est demandé ([deepZoom]) et que l'image affichée
 * ([requestedLongPx] = zoom x grand côté affiché, en px) dépasse nettement l'aperçu
 * ([previewSide], marge de 20 %), sauf si l'image elle-même n'est pas plus grande que l'aperçu
 * ([imageLongPx], null si inconnu). Téléchargement automatique sur réseau non facturé, ou sur
 * réseau facturé si [allowOnCostly] (réglage), sinon proposé à l'utilisateur.
 */
internal fun originalDecision(
    deepZoom: Boolean,
    requestedLongPx: Int,
    previewSide: Int,
    imageLongPx: Int?,
    costlyNetwork: Boolean,
    allowOnCostly: Boolean,
): OriginalDecision {
    if (!deepZoom) return OriginalDecision.NOT_NEEDED
    if (requestedLongPx.toLong() * 10 <= previewSide.toLong() * 12) return OriginalDecision.NOT_NEEDED
    if (imageLongPx != null && imageLongPx > 0 && imageLongPx <= previewSide) return OriginalDecision.NOT_NEEDED
    return if (!costlyNetwork || allowOnCostly) OriginalDecision.AUTO else OriginalDecision.ASK
}

/**
 * Ensemble de clés qui expirent après [ttlMs] (miniatures introuvables : pas de nouvelle requête
 * à chaque affichage pendant ce délai). Sûr entre threads.
 */
internal class ExpiringKeySet(private val ttlMs: Long, private val clock: () -> Long) {
    private val expiries = ConcurrentHashMap<String, Long>()

    fun add(key: String) {
        if (expiries.size >= MAX_KEYS) purgeExpired()
        expiries[key] = clock() + ttlMs
    }

    operator fun contains(key: String): Boolean {
        val until = expiries[key] ?: return false
        if (clock() < until) return true
        expiries.remove(key, until)
        return false
    }

    fun clear() = expiries.clear()

    private fun purgeExpired() {
        val now = clock()
        expiries.entries.removeIf { it.value <= now }
        // Toujours plein : on repart de zéro plutôt que de grossir sans limite.
        if (expiries.size >= MAX_KEYS) expiries.clear()
    }

    private companion object {
        const val MAX_KEYS = 2_000
    }
}
