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
private val CLOUD_THUMB_BUCKETS = intArrayOf(96, 192, 256, 384, 512, 768, 1024)

/** Côté de l'aperçu de la visionneuse (taille personnalisée, proportions d'origine). */
internal const val CLOUD_PREVIEW_PX = 1600

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
 * `largeSquare` (800 px) au-delà. Aperçu : `c1600x1600` (proportions d'origine), repli `large`
 * (800 px). Les miniatures Graph sont déjà orientées (rotation EXIF appliquée).
 */
internal fun cloudThumbSize(bucketPx: Int, preview: Boolean): CloudThumbSize = when {
    preview -> CloudThumbSize("c${CLOUD_PREVIEW_PX}x$CLOUD_PREVIEW_PX", "large")
    bucketPx <= 96 -> CloudThumbSize("smallSquare", null)
    else -> CloudThumbSize("c${bucketPx}x${bucketPx}_crop", if (bucketPx <= 256) "mediumSquare" else "largeSquare")
}

/** Chemin réellement demandé : la taille standard dès que Graph a refusé une taille personnalisée. */
internal fun effectiveThumbPath(size: CloudThumbSize, customSizesRejected: Boolean): String =
    if (customSizesRejected && size.fallback != null) size.fallback else size.path

/** Clé de cache : élément + version du contenu (cTag) + palier ; un renommage ne l'invalide pas. */
internal fun cloudThumbKey(ref: CloudThumbRef, bucketPx: Int, preview: Boolean): String =
    "$CLOUD_THUMB_KEY_PREFIX${ref.itemId}:${ref.version}:${if (preview) "preview" else bucketPx.toString()}"

/** 400 (taille refusée) ou 404 (taille non générée) : la taille standard de repli est essayée. */
internal fun shouldTryFallbackSize(status: Int): Boolean = status == 400 || status == 404

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
