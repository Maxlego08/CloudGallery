package fr.eva.cloudgallery.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Media access based on permissions, and OneDrive in Photos without local access. */
class MediaAccessTest {

    @Test
    fun mediaAccess_fullOnlyWithImagesAndVideos() {
        assertEquals(MediaAccess.FULL, mediaAccessOf(images = true, videos = true, selected = false))
        assertEquals(MediaAccess.FULL, mediaAccessOf(images = true, videos = true, selected = true))
    }

    @Test
    fun mediaAccess_partialWithOneTypeOrSelectedPhotos() {
        assertEquals(MediaAccess.PARTIAL, mediaAccessOf(images = true, videos = false, selected = false))
        assertEquals(MediaAccess.PARTIAL, mediaAccessOf(images = false, videos = true, selected = false))
        assertEquals(MediaAccess.PARTIAL, mediaAccessOf(images = false, videos = false, selected = true))
    }

    @Test
    fun mediaAccess_noneWithoutAnyPermission() {
        assertEquals(MediaAccess.NONE, mediaAccessOf(images = false, videos = false, selected = false))
    }

    /** User decision: in "OneDrive only" mode, Photos shows OneDrive regardless of the setting. */
    @Test
    fun library_withoutLocalAccess_alwaysIncludesCloudOnlyItems() {
        assertTrue(includeCloudOnlyInLibrary(showCloudInPhotos = false, access = MediaAccess.NONE))
        assertTrue(includeCloudOnlyInLibrary(showCloudInPhotos = true, access = MediaAccess.NONE))
    }

    @Test
    fun library_withLocalAccess_followsTheSetting() {
        for (access in listOf(MediaAccess.FULL, MediaAccess.PARTIAL)) {
            assertFalse(includeCloudOnlyInLibrary(showCloudInPhotos = false, access = access))
            assertTrue(includeCloudOnlyInLibrary(showCloudInPhotos = true, access = access))
        }
    }
}
