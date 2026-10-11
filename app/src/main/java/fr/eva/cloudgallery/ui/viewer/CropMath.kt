package fr.eva.cloudgallery.ui.viewer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Rectangle de recadrage normalisé (0..1) dans le repère de l'image TELLE QU'AFFICHÉE dans l'éditeur,
 * c'est-à-dire après orientation EXIF et après la rotation choisie dans l'éditeur.
 * C'est ce repère qui est transmis à LocalMediaWriter.saveCroppedCopy (décoder, pivoter, puis rogner).
 */
data class CropRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    companion object {
        val Full = CropRect(0f, 0f, 1f, 1f)
    }
}

enum class CropHandle { TopLeft, Top, TopRight, Right, BottomRight, Bottom, BottomLeft, Left, Move }

/** Préréglages de proportions (largeur / hauteur en pixels), null = libre. */
enum class CropAspect(val ratio: Float?) {
    Free(null),
    Square(1f),
    FourThree(4f / 3f),
    SixteenNine(16f / 9f),
}

/** Calculs purs du recadrage (aucune dépendance Android). */
object CropMath {
    /** Taille minimale d'un côté, en fraction de l'image. */
    const val MIN_SIZE = 0.05f

    /**
     * Proportion à respecter dans le repère normalisé : w_n / h_n = ratio * imageH / imageW.
     * [imageAspect] = largeur / hauteur de l'image affichée.
     */
    fun normalizedRatio(pixelRatio: Float, imageAspect: Float): Float = pixelRatio / imageAspect

    /**
     * Plus grand rectangle de proportion [pixelRatio] centré dans l'image. Si l'image est en portrait et
     * le ratio en paysage (ou inversement), on garde l'orientation du préréglage telle quelle.
     */
    fun fitAspect(pixelRatio: Float?, imageAspect: Float): CropRect {
        if (pixelRatio == null || imageAspect <= 0f) return CropRect.Full
        val r = normalizedRatio(pixelRatio, imageAspect)
        var w = 1f
        var h = w / r
        if (h > 1f) {
            h = 1f
            w = h * r
        }
        val l = (1f - w) / 2f
        val t = (1f - h) / 2f
        return CropRect(l, t, l + w, t + h)
    }

    /** Déplace le rectangle sans le redimensionner, borné à l'image. */
    fun move(rect: CropRect, dx: Float, dy: Float): CropRect {
        val ndx = dx.coerceIn(-rect.left, 1f - rect.right)
        val ndy = dy.coerceIn(-rect.top, 1f - rect.bottom)
        return CropRect(rect.left + ndx, rect.top + ndy, rect.right + ndx, rect.bottom + ndy)
    }

    /**
     * Applique un glissement ([dx], [dy] normalisés) sur une poignée. Avec [pixelRatio] non nul, la
     * proportion est conservée (coin opposé fixe pour un coin, centre de l'autre axe fixe pour un bord).
     */
    fun drag(rect: CropRect, handle: CropHandle, dx: Float, dy: Float, pixelRatio: Float?, imageAspect: Float): CropRect {
        if (handle == CropHandle.Move) return move(rect, dx, dy)
        return if (pixelRatio == null || imageAspect <= 0f) {
            resizeFree(rect, handle, dx, dy)
        } else {
            resizeLocked(rect, handle, dx, dy, normalizedRatio(pixelRatio, imageAspect))
        }
    }

    private fun resizeFree(rect: CropRect, handle: CropHandle, dx: Float, dy: Float): CropRect {
        var l = rect.left
        var t = rect.top
        var r = rect.right
        var b = rect.bottom
        if (handle.movesLeft) l = (l + dx).coerceIn(0f, r - MIN_SIZE)
        if (handle.movesRight) r = (r + dx).coerceIn(l + MIN_SIZE, 1f)
        if (handle.movesTop) t = (t + dy).coerceIn(0f, b - MIN_SIZE)
        if (handle.movesBottom) b = (b + dy).coerceIn(t + MIN_SIZE, 1f)
        return CropRect(l, t, r, b)
    }

    private fun resizeLocked(rect: CropRect, handle: CropHandle, dx: Float, dy: Float, ratio: Float): CropRect {
        val minW = max(MIN_SIZE, MIN_SIZE * ratio)
        return when (handle) {
            CropHandle.TopLeft, CropHandle.TopRight, CropHandle.BottomLeft, CropHandle.BottomRight -> {
                // Coin opposé fixe.
                val anchorX = if (handle.movesLeft) rect.right else rect.left
                val anchorY = if (handle.movesTop) rect.bottom else rect.top
                val movingX = (if (handle.movesLeft) rect.left else rect.right) + dx
                val movingY = (if (handle.movesTop) rect.top else rect.bottom) + dy
                var w = abs(anchorX - movingX)
                var h = abs(anchorY - movingY)
                // Axe dominant.
                if (w / ratio >= h) h = w / ratio else w = h * ratio
                val maxW = if (handle.movesLeft) anchorX else 1f - anchorX
                val maxH = if (handle.movesTop) anchorY else 1f - anchorY
                if (w > maxW) { w = maxW; h = w / ratio }
                if (h > maxH) { h = maxH; w = h * ratio }
                if (w < minW) { w = min(minW, maxW); h = w / ratio }
                val l = if (handle.movesLeft) anchorX - w else anchorX
                val t = if (handle.movesTop) anchorY - h else anchorY
                clampInside(CropRect(l, t, l + w, t + h))
            }
            CropHandle.Left, CropHandle.Right -> {
                val anchorX = if (handle == CropHandle.Left) rect.right else rect.left
                val movingX = (if (handle == CropHandle.Left) rect.left else rect.right) + dx
                var w = max(abs(anchorX - movingX), minW)
                val maxW = if (handle == CropHandle.Left) anchorX else 1f - anchorX
                val cy = rect.centerY
                val maxH = 2f * min(cy, 1f - cy)
                w = min(w, min(maxW, maxH * ratio))
                val h = w / ratio
                val l = if (handle == CropHandle.Left) anchorX - w else anchorX
                clampInside(CropRect(l, cy - h / 2f, l + w, cy + h / 2f))
            }
            CropHandle.Top, CropHandle.Bottom -> {
                val anchorY = if (handle == CropHandle.Top) rect.bottom else rect.top
                val movingY = (if (handle == CropHandle.Top) rect.top else rect.bottom) + dy
                var h = max(abs(anchorY - movingY), minW / ratio)
                val maxH = if (handle == CropHandle.Top) anchorY else 1f - anchorY
                val cx = rect.centerX
                val maxW = 2f * min(cx, 1f - cx)
                h = min(h, min(maxH, maxW / ratio))
                val w = h * ratio
                val t = if (handle == CropHandle.Top) anchorY - h else anchorY
                clampInside(CropRect(cx - w / 2f, t, cx + w / 2f, t + h))
            }
            CropHandle.Move -> rect
        }
    }

    /** Garantit 0 <= left < right <= 1 (idem verticalement) en conservant la taille si possible. */
    fun clampInside(rect: CropRect): CropRect {
        val w = rect.width.coerceIn(0f, 1f)
        val h = rect.height.coerceIn(0f, 1f)
        val l = rect.left.coerceIn(0f, 1f - w)
        val t = rect.top.coerceIn(0f, 1f - h)
        return CropRect(l, t, l + w, t + h)
    }

    /** Rotation de 90° dans le sens horaire du rectangle normalisé (l'image pivote avec lui). */
    fun rotateClockwise(rect: CropRect): CropRect =
        CropRect(left = 1f - rect.bottom, top = rect.left, right = 1f - rect.top, bottom = rect.right)

    /** Normalise un rectangle en pixels (repère de l'image affichée de taille [imageW] x [imageH]). */
    fun normalize(left: Float, top: Float, right: Float, bottom: Float, imageW: Float, imageH: Float): CropRect {
        if (imageW <= 0f || imageH <= 0f) return CropRect.Full
        return clampInside(
            CropRect(
                min(left, right) / imageW,
                min(top, bottom) / imageH,
                max(left, right) / imageW,
                max(top, bottom) / imageH,
            ),
        )
    }

    /**
     * Poignée touchée au point ([x], [y]) pour un rectangle en pixels ; [radius] = tolérance tactile.
     * Priorité : coins, puis bords, puis intérieur (déplacement). Null si hors de toute zone.
     */
    fun hitTest(x: Float, y: Float, left: Float, top: Float, right: Float, bottom: Float, radius: Float): CropHandle? {
        fun near(a: Float, b: Float) = abs(a - b) <= radius
        val withinX = x >= left - radius && x <= right + radius
        val withinY = y >= top - radius && y <= bottom + radius
        if (!withinX || !withinY) return null
        return when {
            near(x, left) && near(y, top) -> CropHandle.TopLeft
            near(x, right) && near(y, top) -> CropHandle.TopRight
            near(x, left) && near(y, bottom) -> CropHandle.BottomLeft
            near(x, right) && near(y, bottom) -> CropHandle.BottomRight
            near(x, left) -> CropHandle.Left
            near(x, right) -> CropHandle.Right
            near(y, top) -> CropHandle.Top
            near(y, bottom) -> CropHandle.Bottom
            x > left && x < right && y > top && y < bottom -> CropHandle.Move
            else -> null
        }
    }

    /** Vrai si le rectangle couvre toute l'image (à 0,5 % près) et qu'il n'y a pas de rotation. */
    fun isIdentity(rect: CropRect, rotationDegrees: Int): Boolean {
        val eps = 0.005f
        return rotationDegrees % 360 == 0 &&
            rect.left <= eps && rect.top <= eps && rect.right >= 1f - eps && rect.bottom >= 1f - eps
    }

    private val CropHandle.movesLeft get() = this == CropHandle.TopLeft || this == CropHandle.Left || this == CropHandle.BottomLeft
    private val CropHandle.movesRight get() = this == CropHandle.TopRight || this == CropHandle.Right || this == CropHandle.BottomRight
    private val CropHandle.movesTop get() = this == CropHandle.TopLeft || this == CropHandle.Top || this == CropHandle.TopRight
    private val CropHandle.movesBottom get() = this == CropHandle.BottomLeft || this == CropHandle.Bottom || this == CropHandle.BottomRight
}
