package fr.eva.cloudgallery.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomMathTest {
    private val eps = 1e-3f

    @Test
    fun fitSize_landscapeInPortrait() {
        val (w, h) = ZoomMath.fitSize(4000f, 3000f, 1080f, 2340f)
        assertEquals(1080f, w, eps)
        assertEquals(810f, h, eps)
    }

    @Test
    fun fitSize_unknownContentFillsContainer() {
        val (w, h) = ZoomMath.fitSize(0f, 0f, 1080f, 2340f)
        assertEquals(1080f, w, eps)
        assertEquals(2340f, h, eps)
    }

    @Test
    fun clampOffset_noPanAtScaleOne() {
        assertEquals(0f, ZoomMath.clampOffset(300f, 1080f, 1080f, 1f), eps)
        // Zoom 2x : le contenu fait 2160 px, translation max +/-540.
        assertEquals(540f, ZoomMath.clampOffset(900f, 1080f, 1080f, 2f), eps)
        assertEquals(-540f, ZoomMath.clampOffset(-900f, 1080f, 1080f, 2f), eps)
    }

    @Test
    fun clampOffset_contentSmallerThanContainerStaysCentered() {
        // Hauteur ajustée 810 px dans 2340 px : à 2x (1620 px), pas de déplacement vertical.
        assertEquals(0f, ZoomMath.clampOffset(200f, 810f, 2340f, 2f), eps)
    }

    @Test
    fun zoomAround_keepsFocalPointFixed() {
        // Point focal à +200 px du centre, zoom 1x -> 2x : la translation doit valoir -200.
        assertEquals(-200f, ZoomMath.zoomAround(0f, 1f, 2f, 200f), eps)
        // Zoom au centre : pas de translation.
        assertEquals(0f, ZoomMath.zoomAround(0f, 1f, 3f, 0f), eps)
        // Aller-retour : on retrouve la translation initiale.
        val o = ZoomMath.zoomAround(50f, 1f, 2.5f, 120f)
        assertEquals(50f, ZoomMath.zoomAround(o, 2.5f, 1f, 120f), eps)
    }

    @Test
    fun clampScale() {
        assertEquals(1f, ZoomMath.clampScale(0.5f), eps)
        assertEquals(5f, ZoomMath.clampScale(9f), eps)
        assertEquals(2.2f, ZoomMath.clampScale(2.2f), eps)
    }

    @Test
    fun baseMaxSide_isTwiceScreenCapped() {
        assertEquals(4096, ZoomMath.baseMaxSide(2340))
        assertEquals(3200, ZoomMath.baseMaxSide(1600))
    }

    @Test
    fun hiResMaxSide() {
        // 12 MP (4000x3000) : pas plus grand que le décodage de base -> aucun rechargement.
        assertNull(ZoomMath.hiResMaxSide(4000, 3000, 4096))
        // 50 MP (8160x6120) : réduit sous 24 MP, mais toujours plus grand que le décodage de base.
        val side = ZoomMath.hiResMaxSide(8160, 6120, 4096)
        assertNotNull(side)
        assertTrue(side!! > 4096)
        assertTrue(side * (side * 6120.0 / 8160.0) <= ZoomMath.HI_RES_MAX_PIXELS * 1.001)
        // 200 MP (16320x12240).
        val big = ZoomMath.hiResMaxSide(16320, 12240, 4096)!!
        assertTrue(big * (big * 12240.0 / 16320.0) <= ZoomMath.HI_RES_MAX_PIXELS * 1.001)
        assertNull(ZoomMath.hiResMaxSide(0, 0, 4096))
    }

    @Test
    fun splitPagerDelta() {
        // Pager non entraîné : tout pour l'image.
        assertEquals(Triple(0f, 30f, 0f), ZoomMath.splitPagerDelta(30f, 0f))
        // Pager entraîné de -50 (vers la page suivante), le doigt continue : tout pour le pager.
        assertEquals(Triple(-20f, 0f, -70f), ZoomMath.splitPagerDelta(-20f, -50f))
        // Le doigt revient de +80 : 50 ramènent le pager à 0, 30 pour l'image.
        assertEquals(Triple(50f, 30f, 0f), ZoomMath.splitPagerDelta(80f, -50f))
    }

    @Test
    fun pagerSettleTarget() {
        assertEquals(3, ZoomMath.pagerSettleTarget(2.3f, 2, 0f, 1000f, 10))
        assertEquals(2, ZoomMath.pagerSettleTarget(2.1f, 2, 0f, 1000f, 10))
        assertEquals(3, ZoomMath.pagerSettleTarget(2.05f, 2, -1500f, 1000f, 10))
        assertEquals(1, ZoomMath.pagerSettleTarget(1.7f, 2, 0f, 1000f, 10))
        assertEquals(0, ZoomMath.pagerSettleTarget(-0.3f, 0, 0f, 1000f, 10))
        assertEquals(9, ZoomMath.pagerSettleTarget(9.4f, 9, 0f, 1000f, 10))
    }

    @Test
    fun imageDimensions_fallBackToTheFileWhenTheIndexHasNone() {
        assertEquals(4000 to 3000, ZoomMath.imageDimensions(4000, 3000, fileBounds = 8000 to 6000))
        // OneDrive a renvoyé 0 x 0 : dimensions lues dans l'original téléchargé.
        assertEquals(8160 to 6120, ZoomMath.imageDimensions(0, 0, fileBounds = 8160 to 6120))
        assertEquals(0 to 0, ZoomMath.imageDimensions(0, 0, fileBounds = null))
        assertEquals(0 to 0, ZoomMath.imageDimensions(0, 3000, fileBounds = 0 to 0))
        // Avec ces dimensions, le rechargement haute résolution redevient possible.
        val (w, h) = ZoomMath.imageDimensions(0, 0, fileBounds = 8160 to 6120)
        assertNotNull(ZoomMath.hiResMaxSide(w, h, baseMaxSide = 4096))
    }
}
