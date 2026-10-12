package fr.eva.cloudgallery.ui.viewer

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Calculs purs du zoom de la visionneuse (testables sans Android).
 *
 * Convention : le contenu ajusté (« fit ») est centré dans le conteneur ; le zoom s'applique
 * autour du centre du contenu, puis la translation `offset` (en px écran) est ajoutée.
 * Les points focaux sont exprimés relativement au centre du conteneur.
 */
object ZoomMath {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 5f
    const val DOUBLE_TAP_SCALE = 2.5f

    /** Au-delà de ce zoom, on recharge l'image avec une résolution plus grande. */
    const val HI_RES_THRESHOLD = 2.5f

    /** Plafond de taille du décodage de base. */
    const val BASE_MAX_SIDE = 4096

    /**
     * Plafond de pixels pour le rechargement haute résolution (~24 MP) : un bitmap ARGB_8888 de
     * 24 MP pèse ~96 Mo, sous la limite de 100 Mio au-delà de laquelle le Canvas refuse de le dessiner
     * (« trying to draw too large bitmap »), et évite un OOM.
     */
    const val HI_RES_MAX_PIXELS = 24_000_000L

    /** Taille du contenu ajusté (ContentScale.Fit) dans le conteneur, sur un axe donné. Renvoie (w, h). */
    fun fitSize(contentW: Float, contentH: Float, containerW: Float, containerH: Float): Pair<Float, Float> {
        if (contentW <= 0f || contentH <= 0f || containerW <= 0f || containerH <= 0f) {
            return containerW.coerceAtLeast(0f) to containerH.coerceAtLeast(0f)
        }
        val ratio = min(containerW / contentW, containerH / contentH)
        return contentW * ratio to contentH * ratio
    }

    /** Translation maximale (valeur absolue) sur un axe pour que le contenu reste dans le cadre. */
    fun maxOffset(fitted: Float, container: Float, scale: Float): Float =
        max(0f, (fitted * scale - container) / 2f)

    fun clampOffset(offset: Float, fitted: Float, container: Float, scale: Float): Float {
        val m = maxOffset(fitted, container, scale)
        return offset.coerceIn(-m, m)
    }

    /**
     * Nouvelle translation après un changement d'échelle [scale] -> [newScale] en gardant fixe le point
     * situé sous [focal] (relatif au centre du conteneur).
     */
    fun zoomAround(offset: Float, scale: Float, newScale: Float, focal: Float): Float {
        if (scale <= 0f) return offset
        val k = newScale / scale
        return focal * (1f - k) + offset * k
    }

    fun clampScale(scale: Float): Float = scale.coerceIn(MIN_SCALE, MAX_SCALE)

    /** Côté maximal du décodage de base : 2 x le grand côté de l'écran, plafonné à [BASE_MAX_SIDE]. */
    fun baseMaxSide(screenLongSidePx: Int): Int = min(BASE_MAX_SIDE, max(1, screenLongSidePx * 2))

    /**
     * Côté maximal pour le rechargement haute résolution : pleine résolution de l'image, réduite si
     * nécessaire pour ne pas dépasser [maxPixels]. Renvoie null si l'image n'est pas plus grande
     * que le décodage de base (aucun rechargement utile) ou si ses dimensions sont inconnues.
     */
    /**
     * Dimensions de l'image pour le rechargement haute résolution : celles de l'index si elles
     * sont connues, sinon celles lues dans le fichier ([fileBounds]), sinon 0 x 0 (inconnues).
     */
    fun imageDimensions(metaW: Int, metaH: Int, fileBounds: Pair<Int, Int>?): Pair<Int, Int> = when {
        metaW > 0 && metaH > 0 -> metaW to metaH
        fileBounds != null && fileBounds.first > 0 && fileBounds.second > 0 -> fileBounds
        else -> 0 to 0
    }

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
     * Page cible après un glissement transmis au pager depuis une image zoomée.
     * [position] = page courante + fraction de décalage ; [velocityX] = vitesse du doigt (px/s, > 0 vers la droite).
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
     * Répartit un déplacement horizontal [dx] entre la page voisine (pager) déjà entraînée de
     * [pagerAccum] px et l'image. Renvoie (part rendue au pager, part restant pour l'image, nouvel accumulé).
     * Tant que le pager est décalé, le doigt le ramène d'abord à zéro avant de déplacer l'image.
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
