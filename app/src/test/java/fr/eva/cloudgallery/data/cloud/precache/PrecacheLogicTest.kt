package fr.eva.cloudgallery.data.cloud.precache

import fr.eva.cloudgallery.data.cloud.thumb.CLOUD_PREVIEW_PX
import fr.eva.cloudgallery.domain.model.CloudThumbRef
import fr.eva.cloudgallery.domain.model.PrecacheOutcome
import fr.eva.cloudgallery.domain.model.PrecacheState
import fr.eva.cloudgallery.ui.grid.GridZoomLevels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrecacheLogicTest {

    private val ref = CloudThumbRef("ITEM!1", "\"{ABC},3\"")

    @Test
    fun gridColumns_matchDefaultZoomLevelInPortrait() {
        assertEquals(GridZoomLevels.columns(GridZoomLevels.DEFAULT_LEVEL, landscape = false), PRECACHE_GRID_COLUMNS)
    }

    @Test
    fun hash_isStableHexAndDependsOnVersion() {
        val hash = precacheHash(ref)
        assertEquals(40, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
        assertEquals(hash, precacheHash(ref.copy()))
        assertNotEquals(hash, precacheHash(ref.copy(version = "\"{ABC},4\"")))
        assertNotEquals(hash, precacheHash(ref.copy(itemId = "ITEM!2")))
    }

    @Test
    fun relativePath_shardsByHashPrefix() {
        val hash = precacheHash(ref)
        assertEquals("${hash.take(2)}/$hash.g384", precacheRelativePath(hash, PrecacheKind.GRID, 384))
        assertEquals("${hash.take(2)}/$hash.p1600", precacheRelativePath(hash, PrecacheKind.PREVIEW, 1600))
    }

    @Test
    fun hashOf_readsOnlyPrecacheFileNames() {
        val hash = precacheHash(ref)
        assertEquals(hash, precacheHashOf("$hash.g384"))
        assertEquals(hash, precacheHashOf("$hash.p2048"))
        assertNull(precacheHashOf("$hash.x384"))
        assertNull(precacheHashOf("$hash.g"))
        assertNull(precacheHashOf("$hash.gabc"))
        assertNull(precacheHashOf("abc.g384"))
        assertNull(precacheHashOf("0123456789-abcdef"))
        assertNull(precacheHashOf("${hash.uppercase()}.g384"))
    }

    @Test
    fun lookupSides_largerFirstThenSmallerOnlyWhenAllowed() {
        assertEquals(listOf(384, 512, 768, 1024), precacheLookupSides(300, PrecacheKind.GRID, allowLower = false))
        assertEquals(
            listOf(384, 512, 768, 1024, 256, 192, 96),
            precacheLookupSides(384, PrecacheKind.GRID, allowLower = true),
        )
        assertEquals(listOf(2048), precacheLookupSides(2048, PrecacheKind.PREVIEW, allowLower = false))
        assertEquals(listOf(2048, 1600), precacheLookupSides(2048, PrecacheKind.PREVIEW, allowLower = true))
        assertEquals(listOf(1600, 2048), precacheLookupSides(1600, PrecacheKind.PREVIEW, allowLower = false))
    }

    @Test
    fun gridBucket_followsCellWidthAtDefaultZoom() {
        // 1080 px wide, 3 columns: 360 px cells, 384 bucket.
        assertEquals(384, precacheGridBucket(1080))
        assertEquals(512, precacheGridBucket(1440))
        assertEquals(256, precacheGridBucket(720))
    }

    @Test
    fun previewSide_matchesViewerOnUnmeteredNetwork() {
        // 2340 px screen: 2048 bucket, unless the image is small or Graph refuses that bucket.
        assertEquals(2048, precachePreviewSide(2340, imageLongPx = 4000, isVideo = false, maxAcceptedSide = 2048))
        assertEquals(CLOUD_PREVIEW_PX, precachePreviewSide(2340, imageLongPx = 1200, isVideo = false, maxAcceptedSide = 2048))
        assertEquals(CLOUD_PREVIEW_PX, precachePreviewSide(2340, imageLongPx = 4000, isVideo = false, maxAcceptedSide = CLOUD_PREVIEW_PX))
        assertEquals(2048, precachePreviewSide(2340, imageLongPx = null, isVideo = false, maxAcceptedSide = 2048))
        // Video: the viewer's fixed preview.
        assertEquals(PRECACHE_VIDEO_PREVIEW_PX, precachePreviewSide(2340, imageLongPx = 3840, isVideo = true, maxAcceptedSide = 2048))
    }

    @Test
    fun estimatedBytes_countsThumbnailAndPreview() {
        assertEquals(0L, precacheEstimatedBytes(0))
        assertEquals(2 * (ESTIMATED_GRID_BYTES + ESTIMATED_PREVIEW_BYTES), precacheEstimatedBytes(2))
        // No overflow for a large library.
        assertEquals(100_000L * (ESTIMATED_GRID_BYTES + ESTIMATED_PREVIEW_BYTES), precacheEstimatedBytes(100_000))
    }

    @Test
    fun hasRoom_keepsTwoGigabytesOrFivePercent() {
        // 20 GB volume: 5% = 1 GB, below the 2 GB minimum.
        val small = 20_000_000_000L
        assertTrue(precacheHasRoom(MIN_FREE_BYTES + 1, small))
        assertFalse(precacheHasRoom(MIN_FREE_BYTES, small))
        val large = 1_000_000_000_000L
        // 5% of 1 TB = 50 GB.
        assertFalse(precacheHasRoom(40_000_000_000L, large))
        assertTrue(precacheHasRoom(50_000_000_001L, large))
    }

    @Test
    fun percent_isBoundedAndSafeWithoutTotal() {
        assertEquals(0, precachePercent(5, 0))
        assertEquals(50, precachePercent(50, 100))
        assertEquals(100, precachePercent(150, 100))
    }

    @Test
    fun shouldPause_afterTwentyConsecutiveFailures() {
        assertFalse(precacheShouldPause(19))
        assertTrue(precacheShouldPause(20))
    }

    @Test
    fun state_runningWinsThenWaitingThenLastOutcome() {
        val running = PrecacheWorkSnapshot(PrecacheWorkStatus.RUNNING, done = 3, total = 10, bytes = 100, failed = 1)
        val waiting = PrecacheWorkSnapshot(PrecacheWorkStatus.WAITING)
        val done = PrecacheWorkSnapshot(PrecacheWorkStatus.SUCCEEDED, done = 10, total = 10, outcome = PrecacheOutcome.COMPLETED)
        assertEquals(PrecacheState.Running(3, 10, 100, 1), precacheStateOf(listOf(done, waiting, running)))
        assertEquals(PrecacheState.Waiting, precacheStateOf(listOf(done, waiting)))
        assertEquals(PrecacheState.Finished(PrecacheOutcome.COMPLETED, 10, 10, 0), precacheStateOf(listOf(done)))
    }

    @Test
    fun state_idleWhenNothingOrCancelledOrClearedWithoutOutcome() {
        assertEquals(PrecacheState.Idle, precacheStateOf(emptyList()))
        assertEquals(PrecacheState.Idle, precacheStateOf(listOf(PrecacheWorkSnapshot(PrecacheWorkStatus.STOPPED))))
        assertEquals(PrecacheState.Idle, precacheStateOf(listOf(PrecacheWorkSnapshot(PrecacheWorkStatus.SUCCEEDED))))
    }
}
