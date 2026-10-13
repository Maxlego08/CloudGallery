package fr.eva.cloudgallery.data.cloud.thumb

import fr.eva.cloudgallery.domain.model.CloudThumbRef
import java.util.concurrent.ConcurrentHashMap

/*
 * Pure logic of OneDrive thumbnails (tested on the JVM): size buckets, requested Microsoft
 * Graph sizes and cache keys.
 */

/** Prefix of the cache keys (memory and disk) of OneDrive thumbnails. */
internal const val CLOUD_THUMB_KEY_PREFIX = "odthumb:"

/** Default size when the requested size is unknown / original (like local thumbnails). */
internal const val DEFAULT_CLOUD_THUMB_PX = 384

/**
 * Size buckets, identical to those of local thumbnails: small variations in cell size (grid
 * zoom) reuse the same thumbnail.
 */
internal val CLOUD_THUMB_BUCKETS = intArrayOf(96, 192, 256, 384, 512, 768, 1024)

/** Default side of the viewer preview (custom size, original aspect ratio). */
internal const val CLOUD_PREVIEW_PX = 1600

/**
 * Viewer preview buckets (side of the square the preview fits in). The 2048 bucket still has
 * to be validated on device: if refused by Graph (400/404) while 1600 is accepted, it is
 * dropped until restart (fallback `c1600x1600`).
 */
internal val CLOUD_PREVIEW_TIERS = intArrayOf(CLOUD_PREVIEW_PX, 2048)

/** Largest preview bucket on a metered network (mobile data, data saver). */
internal const val CLOUD_PREVIEW_COSTLY_MAX_PX = CLOUD_PREVIEW_PX

/**
 * Preview bucket for a display whose long side is [viewportLongPx]: the smallest bucket that
 * covers it (otherwise the largest), never beyond what the image itself needs ([imageLongPx],
 * long side of the original if known), and at most [CLOUD_PREVIEW_COSTLY_MAX_PX] on a metered
 * network ([costlyNetwork]).
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

/** Bucket actually requested: [side] capped at the largest bucket accepted by Graph ([maxAcceptedSide]). */
internal fun cappedPreviewSide(side: Int, maxAcceptedSide: Int): Int = minOf(side, maxAcceptedSide)

/** Bucket matching the longest requested side (px). */
internal fun cloudThumbBucket(sidePx: Int): Int {
    val side = sidePx.coerceAtLeast(1)
    return CLOUD_THUMB_BUCKETS.firstOrNull { it >= side } ?: CLOUD_THUMB_BUCKETS.last()
}

/**
 * Size requested from Graph ([path], `{size}` segment of `/thumbnails/0/{size}/content`) and
 * standard fallback size ([fallback]) if the custom size is refused.
 */
internal data class CloudThumbSize(val path: String, val fallback: String?)

/**
 * Square thumbnails: custom size `c{b}x{b}_crop` rounded to the bucket (documented by
 * Microsoft Graph), otherwise `smallSquare` (96 px). Fallback: `mediumSquare` (176 px) up to
 * 256 px, `largeSquare` (800 px) beyond. Preview ([previewSide]): `c{side}x{side}` (original
 * aspect ratio), fallback to the lower bucket (`c1600x1600`) then `large` (800 px) for 1600
 * and below. Graph thumbnails are already oriented (EXIF rotation applied).
 */
internal fun cloudThumbSize(bucketPx: Int, previewSide: Int?): CloudThumbSize = when {
    previewSide != null && previewSide > CLOUD_PREVIEW_PX ->
        CloudThumbSize("c${previewSide}x$previewSide", "c${CLOUD_PREVIEW_PX}x$CLOUD_PREVIEW_PX")
    previewSide != null -> CloudThumbSize("c${previewSide}x$previewSide", "large")
    bucketPx <= 96 -> CloudThumbSize("smallSquare", null)
    else -> CloudThumbSize("c${bucketPx}x${bucketPx}_crop", if (bucketPx <= 256) "mediumSquare" else "largeSquare")
}

/**
 * Path actually requested: the standard size as soon as Graph has refused a custom size
 * (only if the fallback is itself a standard size; otherwise, the requested size).
 */
internal fun effectiveThumbPath(size: CloudThumbSize, customSizesRejected: Boolean): String =
    if (customSizesRejected && size.fallback != null && !size.fallback.startsWith("c")) size.fallback else size.path

/**
 * Cache key: item + content version (cTag) + thumbnail or preview bucket (`p<side>`); a
 * rename does not invalidate it.
 */
internal fun cloudThumbKey(ref: CloudThumbRef, bucketPx: Int, previewSide: Int?): String =
    "$CLOUD_THUMB_KEY_PREFIX${ref.itemId}:${ref.version}:${previewSide?.let { "p$it" } ?: bucketPx.toString()}"

/** 400 (size refused) or 404 (size not generated): the standard fallback size is tried. */
internal fun shouldTryFallbackSize(status: Int): Boolean = status == 400 || status == 404

/** Is the OneDrive original needed for an image displayed as a preview? */
enum class OriginalDecision {
    /** The preview is enough (low zoom, or image not larger than the preview). */
    NOT_NEEDED,

    /** Download the original without asking (unmetered network, or allowed by the setting). */
    AUTO,

    /** Offer "Load original (size)" (metered network). */
    ASK,
}

/**
 * Original useful when deep zoom is requested ([deepZoom]) and the displayed image
 * ([requestedLongPx] = zoom x displayed long side, in px) clearly exceeds the preview
 * ([previewSide], 20 % margin), unless the image itself is not larger than the preview
 * ([imageLongPx], null if unknown). Automatic download on an unmetered network, or on a
 * metered network if [allowOnCostly] (setting), otherwise offered to the user.
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
 * Set of keys that expire after [ttlMs] (thumbnails not found: no new request on every
 * display during that delay). Thread-safe.
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
        // Still full: start from scratch rather than grow without limit.
        if (expiries.size >= MAX_KEYS) expiries.clear()
    }

    private companion object {
        const val MAX_KEYS = 2_000
    }
}
