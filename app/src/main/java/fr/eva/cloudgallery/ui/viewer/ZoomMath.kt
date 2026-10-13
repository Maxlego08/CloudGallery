package fr.eva.cloudgallery.ui.viewer

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Pure zoom calculations for the viewer (testable without Android).
 *
 * Convention: the fitted ("fit") content is centered in the container; the zoom is applied
 * around the content's center, then the `offset` translation (in screen px) is added.
 * Focal points are expressed relative to the container's center.
 */
object ZoomMath {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 5f
    const val DOUBLE_TAP_SCALE = 2.5f

    /** Beyond this zoom level, the image is reloaded at a higher resolution. */
    const val HI_RES_THRESHOLD = 2.5f

    /** Size cap for the base decode. */
    const val BASE_MAX_SIDE = 4096

    /**
     * Pixel cap for the high-resolution reload (~24 MP): a 24 MP ARGB_8888 bitmap weighs ~96 MB,
     * below the 100 MiB limit beyond which Canvas refuses to draw it ("trying to draw too large
     * bitmap"), and avoids an OOM.
     */
    const val HI_RES_MAX_PIXELS = 24_000_000L

    /** Size of the fitted content (ContentScale.Fit) in the container, on a given axis. Returns (w, h). */
    fun fitSize(contentW: Float, contentH: Float, containerW: Float, containerH: Float): Pair<Float, Float> {
        if (contentW <= 0f || contentH <= 0f || containerW <= 0f || containerH <= 0f) {
            return containerW.coerceAtLeast(0f) to containerH.coerceAtLeast(0f)
        }
        val ratio = min(containerW / contentW, containerH / contentH)
        return contentW * ratio to contentH * ratio
    }

    /** Maximum translation (absolute value) on an axis so the content stays within the frame. */
    fun maxOffset(fitted: Float, container: Float, scale: Float): Float =
        max(0f, (fitted * scale - container) / 2f)

    fun clampOffset(offset: Float, fitted: Float, container: Float, scale: Float): Float {
        val m = maxOffset(fitted, container, scale)
        return offset.coerceIn(-m, m)
    }

    /**
     * New translation after a scale change [scale] -> [newScale], keeping the point under [focal]
     * (relative to the container's center) fixed.
     */
    fun zoomAround(offset: Float, scale: Float, newScale: Float, focal: Float): Float {
        if (scale <= 0f) return offset
        val k = newScale / scale
        return focal * (1f - k) + offset * k
    }

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    /** Maximum side of the base decode: 2 x the screen's long side, capped at [BASE_MAX_SIDE]. */
    fun baseMaxSide(screenLongSidePx: Int): Int = min(BASE_MAX_SIDE, max(1, screenLongSidePx * 2))

    /**
     * Image dimensions for the high-resolution reload: those from the index if known, otherwise
     * those read from the file ([fileBounds]), otherwise 0 x 0 (unknown).
     */
    fun imageDimensions(metaW: Int, metaH: Int, fileBounds: Pair<Int, Int>?): Pair<Int, Int> = when {
        metaW > 0 && metaH > 0 -> metaW to metaH
        fileBounds != null && fileBounds.first > 0 && fileBounds.second > 0 -> fileBounds
        else -> 0 to 0
    }

    /**
     * Maximum side for the high-resolution reload: the image's full resolution, reduced if needed
     * so as not to exceed [maxPixels]. Returns null if the image is not larger than the base
     * decode (no useful reload) or if its dimensions are unknown.
     */
    fun hiResMaxSide(imageW: Int, imageH: Int, baseMaxSide: Int, maxPixels: Long = HI_RES_MAX_PIXELS): Int? {
        if (imageW <= 0 || imageH <= 0) return null
        val longSide = max(imageW, imageH)
        val pixels = imageW.toLong() * imageH
        val side = if (pixels <= maxPixels) {
            longSide
        } else {
            (longSide * sqrt(maxPixels.toDouble() / pixels)).roundToInt()
        }
        return if (side > baseMaxSide) side else null
    }

    /**
     * Target page after a drag forwarded to the pager from a zoomed image.
     * [position] = current page + offset fraction; [velocityX] = finger velocity (px/s, > 0 to the right).
     */
    fun pagerSettleTarget(position: Float, fromPage: Int, velocityX: Float, velocityThreshold: Float, pageCount: Int): Int {
        val target = when {
            position > fromPage + 0.15f || (velocityX < -velocityThreshold && position > fromPage) -> fromPage + 1
            position < fromPage - 0.15f || (velocityX > velocityThreshold && position < fromPage) -> fromPage - 1
            else -> fromPage
        }
        return target.coerceIn(0, max(0, pageCount - 1))
    }

    /**
     * Splits a horizontal move [dx] between the neighbouring page (pager), already dragged by
     * [pagerAccum] px, and the image. Returns (share given to the pager, share left for the image, new accumulated).
     * While the pager is offset, the finger first brings it back to zero before moving the image.
     */
    fun splitPagerDelta(dx: Float, pagerAccum: Float): Triple<Float, Float, Float> {
        if (pagerAccum == 0f) return Triple(0f, dx, 0f)
        val next = pagerAccum + dx
        return if (next == 0f || (next > 0f) == (pagerAccum > 0f)) {
            Triple(dx, 0f, next)
        } else {
            Triple(-pagerAccum, next, 0f)
        }
    }
}
