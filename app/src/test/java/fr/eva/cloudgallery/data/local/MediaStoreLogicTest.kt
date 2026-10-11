package fr.eva.cloudgallery.data.local

import fr.eva.cloudgallery.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaStoreLogicTest {

    @Test
    fun externalId_roundTrip() {
        assertEquals("image:123", buildExternalId(MediaType.IMAGE, 123))
        assertEquals("video:45", buildExternalId(MediaType.VIDEO, 45))
        assertEquals(MediaType.IMAGE to 123L, parseExternalId("image:123"))
        assertEquals(MediaType.VIDEO to 45L, parseExternalId("video:45"))
    }

    @Test
    fun parseExternalId_rejectsMalformed() {
        assertNull(parseExternalId("123"))
        assertNull(parseExternalId("audio:1"))
        assertNull(parseExternalId("image:"))
        assertNull(parseExternalId("image:abc"))
        assertNull(parseExternalId(":12"))
        assertNull(parseExternalId("image:-3"))
    }

    @Test
    fun dateTaken_fallbacks() {
        assertEquals(1_700_000_000_123L, resolveDateTaken(1_700_000_000_123L, 10, 20))
        assertEquals(10_000L, resolveDateTaken(null, 10, 20))
        assertEquals(10_000L, resolveDateTaken(0, 10, 20))
        assertEquals(20_000L, resolveDateTaken(0, 0, 20))
        assertEquals(20_000L, resolveDateTaken(null, null, 20))
        assertEquals(0L, resolveDateTaken(null, null, null))
    }

    @Test
    fun fullSyncDecision() {
        assertTrue(needsFullSync(true, "10", "v1", "v1"))
        assertTrue(needsFullSync(false, null, "v1", "v1"))
        assertTrue(needsFullSync(false, "abc", "v1", "v1"))
        assertTrue(needsFullSync(false, "10", null, "v1"))
        assertTrue(needsFullSync(false, "10", "v1", null))
        assertTrue(needsFullSync(false, "10", "v1", "v2"))
        assertFalse(needsFullSync(false, "10", "v1", "v1"))
    }

    @Test
    fun mediaStorePathCandidates() {
        assertEquals(listOf("image:12"), externalIdCandidatesFromMediaStorePath(listOf("external", "images", "media", "12")))
        assertEquals(listOf("video:7"), externalIdCandidatesFromMediaStorePath(listOf("external_primary", "video", "media", "7")))
        assertEquals(
            listOf("image:3", "video:3"),
            externalIdCandidatesFromMediaStorePath(listOf("external", "file", "3")),
        )
        assertEquals(emptyList<String>(), externalIdCandidatesFromMediaStorePath(listOf("external", "audio", "media", "3")))
        assertEquals(emptyList<String>(), externalIdCandidatesFromMediaStorePath(listOf("external", "images", "media")))
        assertEquals(emptyList<String>(), externalIdCandidatesFromMediaStorePath(listOf("picker", "0", "x", "media", "5")))
    }

    @Test
    fun mediaDocumentId() {
        assertEquals("image:42", externalIdFromMediaDocumentId("image:42"))
        assertEquals("video:9", externalIdFromMediaDocumentId("video:9"))
        assertNull(externalIdFromMediaDocumentId("audio:1"))
    }

    @Test
    fun rightAngles() {
        assertEquals(0, normalizeRightAngle(0))
        assertEquals(90, normalizeRightAngle(90))
        assertEquals(270, normalizeRightAngle(-90))
        assertEquals(0, normalizeRightAngle(360))
        assertEquals(180, normalizeRightAngle(-180))
        assertEquals(90, normalizeRightAngle(450))
        assertNull(normalizeRightAngle(45))
    }

    @Test
    fun cropRect_mapsThroughRotation() {
        val r = NormRect(0.1f, 0.2f, 0.5f, 0.6f)
        assertRect(r, cropRectInSource(r, 0))
        // 90° horaire : x = y', y = 1 - x'
        assertRect(NormRect(0.2f, 0.5f, 0.6f, 0.9f), cropRectInSource(r, 90))
        assertRect(NormRect(0.5f, 0.4f, 0.9f, 0.8f), cropRectInSource(r, 180))
        // 270° horaire : x = 1 - y', y = x'
        assertRect(NormRect(0.4f, 0.1f, 0.8f, 0.5f), cropRectInSource(r, 270))
        assertRect(NormRect(0.4f, 0.1f, 0.8f, 0.5f), cropRectInSource(r, -90))
    }

    @Test
    fun cropRect_fullRectStaysFull() {
        val full = NormRect(0f, 0f, 1f, 1f)
        for (angle in listOf(0, 90, 180, 270)) assertRect(full, cropRectInSource(full, angle))
    }

    @Test
    fun cropRect_isClamped() {
        assertRect(NormRect(0f, 0f, 1f, 1f), cropRectInSource(NormRect(-0.5f, -1f, 2f, 1.5f), 0))
    }

    @Test
    fun sampleSize() {
        assertEquals(1, sampleSizeFor(4000, 3000, 8192))
        assertEquals(1, sampleSizeFor(8192, 6144, 8192))
        assertEquals(2, sampleSizeFor(8193, 100, 8192))
        assertEquals(2, sampleSizeFor(12000, 9000, 8192))
        assertEquals(3, sampleSizeFor(100, 20000, 8192))
    }

    @Test
    fun relativePathNormalization() {
        assertEquals("Pictures/Vacances/", normalizeRelativePath("Pictures/Vacances"))
        assertEquals("Pictures/Vacances/", normalizeRelativePath("/Pictures/Vacances/"))
        assertEquals("DCIM/", normalizeRelativePath(" DCIM "))
        assertEquals("", normalizeRelativePath("/"))
    }

    @Test
    fun croppedName() {
        assertEquals("IMG_001_crop_5.jpg", croppedCopyName("IMG_001.heic", 5))
        assertEquals("photo.v2_crop_5.jpg", croppedCopyName("photo.v2.png", 5))
        assertEquals("IMG_crop_5.jpg", croppedCopyName(null, 5))
        assertEquals("noext_crop_5.jpg", croppedCopyName("noext", 5))
    }

    @Test
    fun playbackPositionMargins() {
        assertFalse(shouldKeepPlaybackPosition(2_999, 60_000))
        assertTrue(shouldKeepPlaybackPosition(3_000, 60_000))
        assertTrue(shouldKeepPlaybackPosition(57_000, 60_000))
        assertFalse(shouldKeepPlaybackPosition(57_001, 60_000))
        assertTrue(shouldKeepPlaybackPosition(100_000, 0)) // durée inconnue
    }

    @Test
    fun bucketKey() {
        assertEquals("local:-1739773001", localBucketKey(-1_739_773_001))
    }

    private fun assertRect(expected: NormRect, actual: NormRect) {
        val eps = 1e-5f
        assertEquals(expected.left, actual.left, eps)
        assertEquals(expected.top, actual.top, eps)
        assertEquals(expected.right, actual.right, eps)
        assertEquals(expected.bottom, actual.bottom, eps)
    }
}
