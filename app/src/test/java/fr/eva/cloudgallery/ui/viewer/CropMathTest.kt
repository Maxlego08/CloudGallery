package fr.eva.cloudgallery.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CropMathTest {
    private val eps = 1e-4f

    private fun assertRect(expected: CropRect, actual: CropRect) {
        assertEquals("left", expected.left, actual.left, eps)
        assertEquals("top", expected.top, actual.top, eps)
        assertEquals("right", expected.right, actual.right, eps)
        assertEquals("bottom", expected.bottom, actual.bottom, eps)
    }

    /** Pixel aspect ratio of a normalized rectangle on an image with aspect ratio [imageAspect]. */
    private fun pixelRatio(rect: CropRect, imageAspect: Float) = rect.width * imageAspect / rect.height

    @Test
    fun fitAspect_squareInLandscape() {
        // 4:3 image, square: normalized width 0.75, full height, centered.
        assertRect(CropRect(0.125f, 0f, 0.875f, 1f), CropMath.fitAspect(1f, 4f / 3f))
    }

    @Test
    fun fitAspect_wideInPortrait() {
        // 3:4 image, 16:9: full width, height = (3/4) / (16/9) = 0.421875.
        val r = CropMath.fitAspect(16f / 9f, 3f / 4f)
        assertEquals(1f, r.width, eps)
        assertEquals(0.421875f, r.height, eps)
        assertEquals(0.5f, r.centerY, eps)
        assertEquals(16f / 9f, pixelRatio(r, 3f / 4f), 1e-3f)
    }

    @Test
    fun fitAspect_freeIsFull() {
        assertRect(CropRect.Full, CropMath.fitAspect(null, 1.5f))
    }

    @Test
    fun move_isClamped() {
        val r = CropRect(0.2f, 0.2f, 0.6f, 0.6f)
        assertRect(CropRect(0.6f, 0.2f, 1f, 0.6f), CropMath.move(r, 0.9f, 0f))
        assertRect(CropRect(0.2f, 0f, 0.6f, 0.4f), CropMath.move(r, 0f, -0.5f))
    }

    @Test
    fun freeResize_clampsToBoundsAndMinSize() {
        val r = CropRect(0.2f, 0.2f, 0.8f, 0.8f)
        assertRect(CropRect(0f, 0f, 0.8f, 0.8f), CropMath.drag(r, CropHandle.TopLeft, -0.5f, -0.5f, null, 1f))
        val shrunk = CropMath.drag(r, CropHandle.Right, -0.9f, 0f, null, 1f)
        assertEquals(0.2f + CropMath.MIN_SIZE, shrunk.right, eps)
        assertRect(CropRect(0.2f, 0.2f, 0.8f, 1f), CropMath.drag(r, CropHandle.Bottom, 0f, 0.5f, null, 1f))
    }

    @Test
    fun lockedResize_cornerKeepsRatioAndAnchor() {
        val aspect = 4f / 3f
        val start = CropMath.fitAspect(1f, aspect)
        val r = CropMath.drag(start, CropHandle.BottomRight, -0.2f, -0.05f, 1f, aspect)
        assertEquals(start.left, r.left, eps)
        assertEquals(start.top, r.top, eps)
        assertEquals(1f, pixelRatio(r, aspect), 1e-3f)
        assertTrue(r.right <= 1f && r.bottom <= 1f)
    }

    @Test
    fun lockedResize_cornerCannotExceedImage() {
        val start = CropRect(0.5f, 0.5f, 0.7f, 0.7f)
        val r = CropMath.drag(start, CropHandle.BottomRight, 2f, 0.1f, 1f, 1f)
        assertRect(CropRect(0.5f, 0.5f, 1f, 1f), r)
    }

    @Test
    fun lockedResize_edgeKeepsCenterAndRatio() {
        val start = CropRect(0.3f, 0.3f, 0.5f, 0.5f)
        val r = CropMath.drag(start, CropHandle.Right, 0.1f, 0f, 1f, 1f)
        assertEquals(0.3f, r.left, eps)
        assertEquals(0.4f, r.centerY, eps)
        assertEquals(1f, pixelRatio(r, 1f), 1e-3f)
        assertEquals(0.3f, r.width, eps)
    }

    @Test
    fun rotateClockwise() {
        val r = CropRect(0.1f, 0.2f, 0.5f, 0.6f)
        assertRect(CropRect(0.4f, 0.1f, 0.8f, 0.5f), CropMath.rotateClockwise(r))
        // 4 rotations = identity.
        var x = r
        repeat(4) { x = CropMath.rotateClockwise(x) }
        assertRect(r, x)
    }

    @Test
    fun normalize() {
        // Swapped corners: reordered then normalized.
        assertRect(CropRect(0.25f, 0.5f, 0.75f, 1f), CropMath.normalize(300f, 400f, 100f, 200f, 400f, 400f))
        assertRect(CropRect.Full, CropMath.normalize(0f, 0f, 10f, 10f, 0f, 0f))
    }

    @Test
    fun hitTest() {
        // Rectangle 100..300 x 100..300, tolerance 20.
        fun hit(x: Float, y: Float) = CropMath.hitTest(x, y, 100f, 100f, 300f, 300f, 20f)
        assertEquals(CropHandle.TopLeft, hit(105f, 95f))
        assertEquals(CropHandle.BottomRight, hit(310f, 290f))
        assertEquals(CropHandle.Left, hit(90f, 200f))
        assertEquals(CropHandle.Bottom, hit(200f, 315f))
        assertEquals(CropHandle.Move, hit(200f, 200f))
        assertNull(hit(10f, 10f))
    }

    @Test
    fun isIdentity() {
        assertTrue(CropMath.isIdentity(CropRect.Full, 0))
        assertFalse(CropMath.isIdentity(CropRect.Full, 90))
        assertFalse(CropMath.isIdentity(CropRect(0.1f, 0f, 1f, 1f), 0))
    }
}
