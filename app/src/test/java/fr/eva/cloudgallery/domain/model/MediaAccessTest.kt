package fr.eva.cloudgallery.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Accès aux médias d'après les autorisations, et OneDrive dans Photos sans accès local. */
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

    /** Décision utilisateur : en « OneDrive seul », Photos montre OneDrive quel que soit le réglage. */
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
