package fr.eva.cloudgallery.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ViewerZoomGeometryTest {

    private val screen = Size(1080f, 2340f)

    @Test
    fun `landscape photo fitted to width and centered`() {
        val f = ViewerZoomGeometry.fittedRect(screen, 4000, 3000)!!
        assertEquals(Rect(0f, 765f, 1080f, 1575f), f)
    }

    @Test
    fun `unknown dimensions, no rectangle`() {
        assertNull(ViewerZoomGeometry.fittedRect(screen, 0, 0))
        assertNull(ViewerZoomGeometry.fittedRect(Size.Zero, 4000, 3000))
    }

    @Test
    fun `at the start, the photo covers the square thumbnail, center-cropped`() {
        val fitted = ViewerZoomGeometry.fittedRect(screen, 4000, 3000)!!
        val cell = Rect(Offset(270f, 600f), Size(270f, 270f))
        val frame = ViewerZoomGeometry.frame(cell, fitted, 0f)
        // Height 810 → 270: scale 1/3; width 1080 / 3 = 360 ≥ 270 (overflow cropped).
        assertEquals(1f / 3f, frame.scale, 1e-4f)
        assertEquals(cell.center - fitted.center, frame.translation)
        assertEquals(cell, frame.clip)
    }

    @Test
    fun `at the end, full screen without transformation`() {
        val fitted = ViewerZoomGeometry.fittedRect(screen, 3000, 4000)!!
        val frame = ViewerZoomGeometry.frame(Rect(0f, 300f, 270f, 570f), fitted, 1f)
        assertEquals(1f, frame.scale, 1e-6f)
        assertEquals(Offset.Zero, frame.translation)
        assertEquals(fitted, frame.clip)
    }
}
