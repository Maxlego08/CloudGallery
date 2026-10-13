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
        // Beyond the last tier: the largest one.
        assertEquals(1024, cloudThumbBucket(4000))
        // Zero or negative size: smallest tier.
        assertEquals(96, cloudThumbBucket(0))
    }

    @Test
    fun gridSizes_useCustomCropWithStandardFallback() {
        assertEquals(CloudThumbSize("smallSquare", null), cloudThumbSize(96, previewSide = null))
        assertEquals(CloudThumbSize("c192x192_crop", "mediumSquare"), cloudThumbSize(192, previewSide = null))
        assertEquals(CloudThumbSize("c256x256_crop", "mediumSquare"), cloudThumbSize(256, previewSide = null))
        assertEquals(CloudThumbSize("c384x384_crop", "largeSquare"), cloudThumbSize(384, previewSide = null))
        assertEquals(CloudThumbSize("c1024x1024_crop", "largeSquare"), cloudThumbSize(1024, previewSide = null))
    }

    @Test
    fun previewSize_keepsAspectRatio_withFallbackChain() {
        val preview = cloudThumbSize(384, previewSide = CLOUD_PREVIEW_PX)
        assertEquals("c${CLOUD_PREVIEW_PX}x$CLOUD_PREVIEW_PX", preview.path)
        assertFalse(preview.path.endsWith("_crop"))
        assertEquals("large", preview.fallback)
        // Large tier: falls back to the 1600 tier (then "large" on the next load).
        assertEquals(CloudThumbSize("c2048x2048", "c1600x1600"), cloudThumbSize(384, previewSide = 2048))
    }

    @Test
    fun effectivePath_switchesToStandardOnceCustomSizesAreRejected() {
        val size = cloudThumbSize(384, previewSide = null)
        assertEquals("c384x384_crop", effectiveThumbPath(size, customSizesRejected = false))
        assertEquals("largeSquare", effectiveThumbPath(size, customSizesRejected = true))
        // No fallback (96 px): always the requested standard size.
        val small = cloudThumbSize(96, previewSide = null)
        assertEquals("smallSquare", effectiveThumbPath(small, customSizesRejected = true))
        assertEquals("large", effectiveThumbPath(cloudThumbSize(384, CLOUD_PREVIEW_PX), customSizesRejected = true))
    }

    @Test
    fun key_dependsOnItemVersionAndBucket() {
        val key = cloudThumbKey(ref, 384, previewSide = null)
        assertEquals("odthumb:ITEM!1:ctag-1:384", key)
        assertTrue(key.startsWith(CLOUD_THUMB_KEY_PREFIX))
        assertNotEquals(key, cloudThumbKey(ref.copy(version = "ctag-2"), 384, previewSide = null))
        assertNotEquals(key, cloudThumbKey(ref, 192, previewSide = null))
        assertEquals("odthumb:ITEM!1:ctag-1:p1600", cloudThumbKey(ref, 384, previewSide = 1600))
        assertEquals("odthumb:ITEM!1:ctag-1:p2048", cloudThumbKey(ref, 384, previewSide = 2048))
        // The preview does not depend on the view size, only on its tier.
        assertEquals(cloudThumbKey(ref, 96, previewSide = 1600), cloudThumbKey(ref, 1024, previewSide = 1600))
        assertNotEquals(cloudThumbKey(ref, 96, previewSide = 1600), cloudThumbKey(ref, 96, previewSide = 2048))
    }

    @Test
    fun previewTier_coversViewport_boundedByImageAndNetwork() {
        // S22: 2340 px long side.
        assertEquals(2048, previewSideFor(viewportLongPx = 2340, costlyNetwork = false, imageLongPx = 4000))
        assertEquals(1600, previewSideFor(viewportLongPx = 1500, costlyNetwork = false, imageLongPx = 4000))
        assertEquals(1600, previewSideFor(viewportLongPx = 1600, costlyNetwork = false, imageLongPx = null))
        assertEquals(2048, previewSideFor(viewportLongPx = 1601, costlyNetwork = false, imageLongPx = null))
        // Metered network: at most 1600.
        assertEquals(1600, previewSideFor(viewportLongPx = 2340, costlyNetwork = true, imageLongPx = 4000))
        // Image smaller than the screen: never a tier beyond what it needs.
        assertEquals(1600, previewSideFor(viewportLongPx = 2340, costlyNetwork = false, imageLongPx = 1200))
        assertEquals(1600, previewSideFor(viewportLongPx = 2340, costlyNetwork = false, imageLongPx = 1600))
        assertEquals(2048, previewSideFor(viewportLongPx = 2340, costlyNetwork = false, imageLongPx = 1800))
        // Unknown size (0): ignored.
        assertEquals(2048, previewSideFor(viewportLongPx = 2340, costlyNetwork = false, imageLongPx = 0))
        // Beyond the last tier: the largest one.
        assertEquals(2048, previewSideFor(viewportLongPx = 3200, costlyNetwork = false, imageLongPx = null))
    }

    @Test
    fun previewTier_isCappedAfterRejection() {
        assertEquals(1600, cappedPreviewSide(2048, maxAcceptedSide = 1600))
        assertEquals(2048, cappedPreviewSide(2048, maxAcceptedSide = 2048))
        assertEquals(1600, cappedPreviewSide(1600, maxAcceptedSide = 2048))
    }

    private fun decide(
        deep: Boolean = true,
        requested: Int = 2340 * 3,
        preview: Int = 1600,
        image: Int? = 4000,
        costly: Boolean = false,
        allow: Boolean = false,
    ) = originalDecision(deep, requested, preview, image, costly, allow)

    @Test
    fun originalDecision_atDeepZoom_dependsOnNetworkAndSetting() {
        assertEquals(OriginalDecision.AUTO, decide())
        assertEquals(OriginalDecision.ASK, decide(costly = true))
        assertEquals(OriginalDecision.AUTO, decide(costly = true, allow = true))
        // No deep zoom: the preview is enough.
        assertEquals(OriginalDecision.NOT_NEEDED, decide(deep = false))
        // Display no larger than the preview (+20%): not needed.
        assertEquals(OriginalDecision.NOT_NEEDED, decide(requested = 1920))
        assertEquals(OriginalDecision.AUTO, decide(requested = 1921))
        // Image no larger than the preview: the original would add nothing.
        assertEquals(OriginalDecision.NOT_NEEDED, decide(image = 1600))
        assertEquals(OriginalDecision.NOT_NEEDED, decide(image = 1200, costly = true))
        // Unknown dimensions: the original may be larger.
        assertEquals(OriginalDecision.ASK, decide(image = null, costly = true))
        assertEquals(OriginalDecision.AUTO, decide(image = 0))
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
        // Lots of expired entries: they are purged before adding.
        set.add("new")
        assertTrue("new" in set)
        assertFalse("old0" in set)
    }
}
