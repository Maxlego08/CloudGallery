package fr.eva.cloudgallery.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ShareLogicTest {

    private fun local(id: Long) = ShareCandidate(id, hasLocalFile = true, offlinePath = null, cloudItemId = "C$id", sizeBytes = 5)
    private fun cloud(id: Long, size: Long = 1_000_000) =
        ShareCandidate(id, hasLocalFile = false, offlinePath = null, cloudItemId = "C$id", sizeBytes = size)

    @Test
    fun localFileFirst_thenOfflineCopy_thenDownload() {
        val offline = ShareCandidate(3, hasLocalFile = false, offlinePath = "offline/x.jpg", cloudItemId = "C3", sizeBytes = 9)
        val orphan = ShareCandidate(4, hasLocalFile = false, offlinePath = null, cloudItemId = null, sizeBytes = 0)
        val plan = planShare(listOf(local(1), cloud(2), offline, orphan), cloudAllowed = true)
        assertEquals(listOf(1L), plan.local)
        assertEquals(listOf(3L), plan.offline)
        assertEquals(listOf(2L), plan.toDownload)
        assertEquals(listOf(4L), plan.unavailable)
        // Only downloads count toward the total (the local file is not downloaded again).
        assertEquals(1_000_000L, plan.downloadBytes)
        assertNull(plan.refusal)
    }

    @Test
    fun signedOut_cloudItemsAreUnavailable() {
        val plan = planShare(listOf(local(1), cloud(2)), cloudAllowed = false)
        assertEquals(listOf(1L), plan.local)
        assertEquals(emptyList<Long>(), plan.toDownload)
        assertEquals(listOf(2L), plan.unavailable)
        assertNull(plan.refusal)
    }

    @Test
    fun caps_twentyItems_and500MB() {
        val twenty = (1L..20L).map { cloud(it, size = 25_000_000) }
        assertNull(planShare(twenty, cloudAllowed = true).refusal)
        val tooMany = (1L..21L).map { cloud(it, size = 1) }
        assertEquals(ShareRefusal.TOO_MANY, planShare(tooMany, cloudAllowed = true).refusal)
        assertEquals(MAX_SHARE_CLOUD_ITEMS, 20)
        assertEquals(MAX_SHARE_CLOUD_BYTES, 500_000_000L)
        val exactly = listOf(cloud(1, size = 500_000_000))
        assertNull(planShare(exactly, cloudAllowed = true).refusal)
        val tooLarge = listOf(cloud(1, size = 400_000_000), cloud(2, size = 100_000_001))
        assertEquals(ShareRefusal.TOO_LARGE, planShare(tooLarge, cloudAllowed = true).refusal)
        // Local files do not count toward the caps.
        val manyLocal = (1L..200L).map { local(it) } + cloud(500)
        assertNull(planShare(manyLocal, cloudAllowed = true).refusal)
    }

    @Test
    fun unknownSize_doesNotCount() {
        val plan = planShare(listOf(cloud(1, size = -1), cloud(2, size = 0)), cloudAllowed = true)
        assertEquals(0L, plan.downloadBytes)
        assertNull(plan.refusal)
    }

    @Test
    fun progressPercent_isBounded() {
        assertEquals(-1, progressPercent(5, 0))
        assertEquals(0, progressPercent(0, 10))
        assertEquals(50, progressPercent(5, 10))
        assertEquals(100, progressPercent(15, 10))
        assertEquals(0, progressPercent(-3, 10))
        assertEquals(33, progressPercent(1_000_000_000L, 3_000_000_000L))
    }
}
