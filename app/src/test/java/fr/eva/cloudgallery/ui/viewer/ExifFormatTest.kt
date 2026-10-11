package fr.eva.cloudgallery.ui.viewer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ExifFormatTest {

    @Test
    fun exposure_shortIsFraction() {
        assertEquals("1/250 s", ExifReader.formatExposure(0.004))
        assertEquals("1/60 s", ExifReader.formatExposure(1.0 / 60.0))
        assertEquals("1/2 s", ExifReader.formatExposure(0.5))
    }

    @Test
    fun exposure_longIsSeconds() {
        assertEquals("2 s", ExifReader.formatExposure(2.0))
        assertEquals("1.5 s", ExifReader.formatExposure(1.5))
        assertEquals("1 s", ExifReader.formatExposure(1.0))
    }

    @Test
    fun exposure_invalidIsEmpty() {
        assertEquals("", ExifReader.formatExposure(0.0))
    }

    @Test
    fun aperture() {
        assertEquals("f/1.8", ExifReader.formatAperture(1.8))
        assertEquals("f/2.0", ExifReader.formatAperture(2.0))
        assertEquals("f/2.4", ExifReader.formatAperture(2.4))
    }

    @Test
    fun focalLength() {
        assertEquals("6 mm", ExifReader.formatFocalLength(6.0))
        assertEquals("5.4 mm", ExifReader.formatFocalLength(5.4))
        assertEquals("24 mm", ExifReader.formatFocalLength(23.98))
    }

    @Test
    fun coordinates() {
        assertEquals("48.85837, 2.29448", ExifReader.formatCoordinates(48.858370, 2.294481))
        assertEquals("-33.86882, 151.20930", ExifReader.formatCoordinates(-33.868820, 151.209296))
    }

    @Test
    fun cameraName() {
        fun info(make: String?, model: String?) = ExifInfo(make, model, null, null, null, null, null, null, null)
        assertEquals("samsung SM-S901B", info("samsung", "SM-S901B").camera)
        assertEquals("Google Pixel 8", info("Google", "Google Pixel 8").camera)
        assertEquals("Canon", info("Canon", null).camera)
        assertNull(info(null, null).camera)
    }
}
