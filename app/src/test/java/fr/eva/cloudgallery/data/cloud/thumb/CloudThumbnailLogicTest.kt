package fr.eva.cloudgallery.data.cloud.thumb

import fr.eva.cloudgallery.domain.model.CloudThumbRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudThumbnailLogicTest {

    private val ref = CloudThumbRef("ITEM!1", "ctag-1")

    @Test
    fun bucket_roundsUpToTheNextTier() {
        assertEquals(96, cloudThumbBucket(1))
        assertEquals(96, cloudThumbBucket(96))
        assertEquals(192, cloudThumbBucket(97))
        assertEquals(256, cloudThumbBucket(256))
        assertEquals(384, cloudThumbBucket(300))
        assertEquals(1024, cloudThumbBucket(1024))
        // Au-delà du dernier palier : le plus grand.
        assertEquals(1024, cloudThumbBucket(4000))
        // Taille nulle ou négative : plus petit palier.
        assertEquals(96, cloudThumbBucket(0))
    }

    @Test
    fun gridSizes_useCustomCropWithStandardFallback() {
        assertEquals(CloudThumbSize("smallSquare", null), cloudThumbSize(96, preview = false))
        assertEquals(CloudThumbSize("c192x192_crop", "mediumSquare"), cloudThumbSize(192, preview = false))
        assertEquals(CloudThumbSize("c256x256_crop", "mediumSquare"), cloudThumbSize(256, preview = false))
        assertEquals(CloudThumbSize("c384x384_crop", "largeSquare"), cloudThumbSize(384, preview = false))
        assertEquals(CloudThumbSize("c1024x1024_crop", "largeSquare"), cloudThumbSize(1024, preview = false))
    }

    @Test
    fun previewSize_keepsAspectRatio_withLargeFallback() {
        val preview = cloudThumbSize(384, preview = true)
        assertEquals("c${CLOUD_PREVIEW_PX}x$CLOUD_PREVIEW_PX", preview.path)
        assertFalse(preview.path.endsWith("_crop"))
        assertEquals("large", preview.fallback)
    }

    @Test
    fun effectivePath_switchesToStandardOnceCustomSizesAreRejected() {
        val size = cloudThumbSize(384, preview = false)
        assertEquals("c384x384_crop", effectiveThumbPath(size, customSizesRejected = false))
        assertEquals("largeSquare", effectiveThumbPath(size, customSizesRejected = true))
        // Sans repli (96 px) : toujours la taille standard demandée.
        val small = cloudThumbSize(96, preview = false)
        assertEquals("smallSquare", effectiveThumbPath(small, customSizesRejected = true))
    }

    @Test
    fun key_dependsOnItemVersionAndBucket() {
        val key = cloudThumbKey(ref, 384, preview = false)
        assertEquals("odthumb:ITEM!1:ctag-1:384", key)
        assertTrue(key.startsWith(CLOUD_THUMB_KEY_PREFIX))
        assertNotEquals(key, cloudThumbKey(ref.copy(version = "ctag-2"), 384, preview = false))
        assertNotEquals(key, cloudThumbKey(ref, 192, preview = false))
        assertEquals("odthumb:ITEM!1:ctag-1:preview", cloudThumbKey(ref, 384, preview = true))
        // L'aperçu ne dépend pas de la taille de la vue.
        assertEquals(cloudThumbKey(ref, 96, preview = true), cloudThumbKey(ref, 1024, preview = true))
    }

    @Test
    fun fallback_onlyForBadRequestOrNotFound() {
        assertTrue(shouldTryFallbackSize(400))
        assertTrue(shouldTryFallbackSize(404))
        assertFalse(shouldTryFallbackSize(403))
        assertFalse(shouldTryFallbackSize(500))
    }

    @Test
    fun expiringKeySet_forgetsKeysAfterTtl() {
        var now = 1_000L
        val set = ExpiringKeySet(ttlMs = 600_000L) { now }
        assertFalse("a" in set)
        set.add("a")
        assertTrue("a" in set)
        now += 599_999L
        assertTrue("a" in set)
        now += 1L
        assertFalse("a" in set)
        set.add("b")
        set.clear()
        assertFalse("b" in set)
    }

    @Test
    fun expiringKeySet_staysBounded() {
        var now = 0L
        val set = ExpiringKeySet(ttlMs = 10L) { now }
        repeat(2_000) { set.add("old$it") }
        now = 100L
        // Plein d'entrées expirées : elles sont purgées avant l'ajout.
        set.add("new")
        assertTrue("new" in set)
        assertFalse("old0" in set)
    }
}
