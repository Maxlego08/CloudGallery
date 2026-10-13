package fr.eva.cloudgallery.data.local

import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RemovalPlanTest {

    private val now = 1_800_000_000_000L

    private fun candidate(id: Long, verdict: MissingVerdict, missingSince: Long? = null) =
        MissingCandidate(sourceId = id, mediaId = id * 10, verdict = verdict, missingSince = missingSince)

    @Test
    fun partialAccess_neverDeletes() {
        val candidates = (1L..5_000L).map { candidate(it, MissingVerdict.GONE) }
        val plan = planRemovals(candidates, knownCount = 100_000, fullAccess = false, now = now)
        assertTrue(plan.delete.isEmpty())
        assertEquals(candidates, plan.hide)
        assertFalse(plan.massRemovalBlocked)
    }

    @Test
    fun partialAccess_keepsEvenExpiredHiddenSources() {
        val old = candidate(1, MissingVerdict.GONE, missingSince = now - MISSING_GRACE_MS * 2)
        val plan = planRemovals(listOf(old), knownCount = 10, fullAccess = false, now = now)
        assertTrue(plan.delete.isEmpty())
        assertEquals(listOf(old), plan.hide)
    }

    @Test
    fun fullAccess_confirmedRemovalIsDeleted() {
        val gone = candidate(1, MissingVerdict.GONE)
        val plan = planRemovals(listOf(gone), knownCount = 1_000, fullAccess = true, now = now)
        assertEquals(listOf(gone), plan.delete)
        assertTrue(plan.hide.isEmpty())
    }

    @Test
    fun fullAccess_hiddenItemsAreNeverDeleted() {
        // Trashed, pending, unmounted volume: favorites and albums kept.
        val trashed = candidate(1, MissingVerdict.HIDDEN)
        val longTrashed = candidate(2, MissingVerdict.HIDDEN, missingSince = now - MISSING_GRACE_MS * 3)
        val plan = planRemovals(listOf(trashed, longTrashed), knownCount = 1_000, fullAccess = true, now = now)
        assertTrue(plan.delete.isEmpty())
        assertEquals(listOf(trashed, longTrashed), plan.hide)
    }

    @Test
    fun fullAccess_alreadyHiddenSourceWaitsForGracePeriod() {
        val recent = candidate(1, MissingVerdict.GONE, missingSince = now - 1_000)
        val expired = candidate(2, MissingVerdict.GONE, missingSince = now - MISSING_GRACE_MS)
        val plan = planRemovals(listOf(recent, expired), knownCount = 1_000, fullAccess = true, now = now)
        assertEquals(listOf(expired), plan.delete)
        assertEquals(listOf(recent), plan.hide)
    }

    @Test
    fun massRemoval_isBrakedIntoHiding() {
        val known = 1_000
        val candidates = (1L..300L).map { candidate(it, MissingVerdict.GONE) }
        val plan = planRemovals(candidates, knownCount = known, fullAccess = true, now = now)
        assertTrue(plan.massRemovalBlocked)
        assertTrue(plan.delete.isEmpty())
        assertEquals(candidates, plan.hide)
    }

    @Test
    fun massRemoval_needsBothFractionAndAbsoluteCount() {
        // 300 out of 100,000: not mass removal (low fraction).
        val few = (1L..300L).map { candidate(it, MissingVerdict.GONE) }
        assertFalse(planRemovals(few, knownCount = 100_000, fullAccess = true, now = now).massRemovalBlocked)
        // 150 out of 200: high fraction but low absolute count (small library, emptied folder).
        val small = (1L..150L).map { candidate(it, MissingVerdict.GONE) }
        val plan = planRemovals(small, knownCount = 200, fullAccess = true, now = now)
        assertFalse(plan.massRemovalBlocked)
        assertEquals(small, plan.delete)
    }

    @Test
    fun versionChange_detection() {
        assertTrue(mediaStoreVersionChanged("a", "b"))
        assertFalse(mediaStoreVersionChanged("a", "a"))
        // Unknown on one side: full rescan, but no re-identification.
        assertFalse(mediaStoreVersionChanged(null, "b"))
        assertFalse(mediaStoreVersionChanged("a", null))
    }

    @Test
    fun fingerprint_ignoresSlashesAndDistinguishesFiles() {
        assertEquals(
            localFingerprint("DCIM/Camera/", "IMG_1.jpg", 42),
            localFingerprint("/DCIM/Camera", "IMG_1.jpg", 42),
        )
        assertNotEquals(localFingerprint("DCIM/Camera/", "IMG_1.jpg", 42), localFingerprint("DCIM/Camera/", "IMG_1.jpg", 43))
        assertNotEquals(localFingerprint("DCIM/Camera/", "IMG_1.jpg", 42), localFingerprint("Pictures/", "IMG_1.jpg", 42))
        assertNotEquals(localFingerprint(null, "IMG_1.jpg", 42), localFingerprint(null, "IMG_2.jpg", 42))
    }

    private fun summary(id: Long, dateTaken: Long) = MediaSummary(
        id = id,
        type = MediaType.IMAGE,
        mimeType = "image/jpeg",
        dateTaken = dateTaken,
        durationMs = 0,
        width = 1,
        height = 1,
        orientation = 0,
        uri = null,
        dateModified = 0,
        isFavorite = false,
        hasLocal = true,
        hasCloud = false,
        hasOffline = false,
    )

    @Test
    fun orderedByListPosition_followsGridOrder() {
        val newer = summary(1, 2_000)
        val older = summary(2, 1_000)
        assertEquals(newer to older, orderedByListPosition(newer, older))
        assertEquals(newer to older, orderedByListPosition(older, newer))
        // Same date: descending id.
        val a = summary(5, 1_000)
        val b = summary(9, 1_000)
        assertEquals(b to a, orderedByListPosition(a, b))
        assertEquals(b to a, orderedByListPosition(b, a))
    }
}
