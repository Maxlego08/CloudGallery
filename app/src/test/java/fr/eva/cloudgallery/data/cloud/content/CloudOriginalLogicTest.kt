package fr.eva.cloudgallery.data.cloud.content

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Trimming of the OneDrive originals cache and replacement name for an original. */
class CloudOriginalLogicTest {

    private val day = 24L * 60 * 60 * 1000
    private val hour = 60L * 60 * 1000
    private val now = 100 * day

    private fun row(
        id: Long,
        path: String = "f$id.jpg",
        size: Long = 100,
        lastAccess: Long = now,
        exists: Boolean = true,
        pinned: Boolean = false,
    ) = TrimRow(id, path, size, lastAccess, exists, pinned)

    @Test
    fun trim_dropsMissingFilesAndOldOriginals() {
        val plan = planOriginalTrim(
            rows = listOf(row(1), row(2, exists = false), row(3, lastAccess = now - 8 * day), row(4, lastAccess = now - 6 * day)),
            busy = emptySet(),
            now = now,
            maxBytes = 1_000,
            maxAgeMs = 7 * day,
        )
        assertEquals(listOf(2L, 3L), plan.drop.map { it.id }.sorted())
        assertEquals(listOf(1L, 4L), plan.keep.map { it.id }.sorted())
    }

    @Test
    fun trim_evictsLeastRecentlyUsedAboveTheCap() {
        val plan = planOriginalTrim(
            rows = listOf(row(1, lastAccess = now - 3 * hour), row(2, lastAccess = now - 1 * hour), row(3, lastAccess = now - 2 * hour)),
            busy = emptySet(),
            now = now,
            maxBytes = 150,
            maxAgeMs = 7 * day,
        )
        // 300 bytes for 150 allowed: the two least recently used go.
        assertEquals(listOf(1L, 3L), plan.drop.map { it.id }.sorted())
        assertEquals(listOf(2L), plan.keep.map { it.id })
    }

    @Test
    fun trim_neverDropsADownloadInProgress() {
        val plan = planOriginalTrim(
            rows = listOf(row(1, path = "busy.jpg", lastAccess = 0, exists = false), row(2, lastAccess = now - hour)),
            busy = setOf("busy.jpg"),
            now = now,
            maxBytes = 0,
            maxAgeMs = 7 * day,
        )
        assertEquals(listOf(2L), plan.drop.map { it.id })
        assertEquals(listOf(1L), plan.keep.map { it.id })
    }

    @Test
    fun trim_neverTouchesPinnedRows_norCountsThemInTheCap() {
        val plan = planOriginalTrim(
            rows = listOf(
                row(1, size = 1_000, lastAccess = now - 30 * day, pinned = true),
                row(2, size = 1_000, lastAccess = 0, exists = false, pinned = true),
                row(3, lastAccess = now - hour),
            ),
            busy = emptySet(),
            now = now,
            maxBytes = 100,
            maxAgeMs = 7 * day,
        )
        // Pinned rows: never too old, never missing a file, never counted; the remaining 100 bytes fit.
        assertTrue(plan.drop.isEmpty())
        assertEquals(listOf(1L, 2L, 3L), plan.keep.map { it.id }.sorted())
    }

    @Test
    fun trim_keepsRecentlyUsedOriginals_evenAboveTheCap() {
        val plan = planOriginalTrim(
            rows = listOf(
                row(1, size = 500, lastAccess = now - 10 * 60 * 1000),
                row(2, size = 500, lastAccess = now - 2 * hour),
                row(3, size = 500, lastAccess = now),
            ),
            busy = emptySet(),
            now = now,
            maxBytes = 100,
            maxAgeMs = 7 * day,
        )
        // Only the original unused for more than 30 min goes; the cap stays exceeded for a while.
        assertEquals(listOf(2L), plan.drop.map { it.id })
        assertEquals(listOf(1L, 3L), plan.keep.map { it.id }.sorted())
    }

    @Test
    fun trim_graceDoesNotProtectMissingFiles() {
        val plan = planOriginalTrim(listOf(row(1, exists = false)), emptySet(), now, maxBytes = 1_000, maxAgeMs = 7 * day)
        assertEquals(listOf(1L), plan.drop.map { it.id })
    }

    @Test
    fun alternateName_keepsTheExtension_andDiffersPerNonce() {
        assertEquals("abc_def-1f.jpg", alternateFileName("abc_def.jpg", "1f"))
        assertEquals("abc-1f", alternateFileName("abc", "1f"))
        assertNotEquals(alternateFileName("a.jpg", "1"), alternateFileName("a.jpg", "2"))
        assertTrue(alternateFileName("a.heic", "9").endsWith(".heic"))
    }
}
