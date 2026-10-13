package fr.eva.cloudgallery.data.cloud.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferLogicTest {

    @Test
    fun urlFreshness_withinTtlOnly() {
        assertTrue(isUrlFresh(fetchedAtMs = 1_000, nowMs = 1_000))
        assertTrue(isUrlFresh(fetchedAtMs = 1_000, nowMs = 1_000 + DOWNLOAD_URL_TTL_MS - 1))
        assertFalse(isUrlFresh(fetchedAtMs = 1_000, nowMs = 1_000 + DOWNLOAD_URL_TTL_MS))
        assertFalse(isUrlFresh(fetchedAtMs = 1_000, nowMs = 1_000 + 3_600_000))
        // Clock went backwards: URL considered stale.
        assertFalse(isUrlFresh(fetchedAtMs = 5_000, nowMs = 4_000))
        assertTrue(isUrlFresh(fetchedAtMs = 0, nowMs = 10, ttlMs = 11))
    }

    @Test
    fun ttl_isShorterThanDocumentedMinutes() {
        // "might expire within minutes": never more than a few minutes.
        assertEquals(120_000L, DOWNLOAD_URL_TTL_MS)
    }

    @Test
    fun expiredUrlStatuses() {
        listOf(401, 403, 404, 410).forEach { assertTrue("$it", isExpiredUrlStatus(it)) }
        listOf(200, 206, 400, 416, 429, 500, 503).forEach { assertFalse("$it", isExpiredUrlStatus(it)) }
    }

    @Test
    fun contentRange_parsing() {
        assertEquals(ContentRange(0, 1023, 2048), parseContentRange("bytes 0-1023/2048"))
        assertEquals(ContentRange(4, 9, null), parseContentRange("bytes 4-9/*"))
        assertEquals(ContentRange(-1, -1, 2048), parseContentRange("bytes */2048"))
        assertEquals(ContentRange(5, 9, 10), parseContentRange("  BYTES 5-9/10 "))
        assertNull(parseContentRange(null))
        assertNull(parseContentRange(""))
        assertNull(parseContentRange("items 0-1/2"))
        assertNull(parseContentRange("bytes 9-4/10"))
        assertNull(parseContentRange("bytes 0-10/10"))
        assertNull(parseContentRange("bytes */*"))
        assertNull(parseContentRange("bytes a-b/c"))
        assertNull(parseContentRange("bytes 0-1"))
    }

    @Test
    fun rangeResponse_classification() {
        assertEquals(RangeOutcome.RestartFromZero, classifyRangeResponse(200, null, offset = 4, expectedSize = 10))
        assertEquals(RangeOutcome.Append, classifyRangeResponse(206, ContentRange(4, 9, 10), offset = 4, expectedSize = 10))
        // Unknown total size ("*"): accepted.
        assertEquals(RangeOutcome.Append, classifyRangeResponse(206, ContentRange(4, 9, null), offset = 4, expectedSize = 10))
        assertTrue(classifyRangeResponse(206, null, offset = 4, expectedSize = 10) is RangeOutcome.Invalid)
        assertTrue(classifyRangeResponse(206, ContentRange(0, 9, 10), offset = 4, expectedSize = 10) is RangeOutcome.Invalid)
        assertTrue(classifyRangeResponse(206, ContentRange(4, 9, 12), offset = 4, expectedSize = 10) is RangeOutcome.Invalid)
        assertEquals(RangeOutcome.AlreadyComplete, classifyRangeResponse(416, ContentRange(-1, -1, 10), offset = 10, expectedSize = 10))
        assertEquals(RangeOutcome.AlreadyComplete, classifyRangeResponse(416, null, offset = 10, expectedSize = 10))
        assertTrue(classifyRangeResponse(416, ContentRange(-1, -1, 12), offset = 10, expectedSize = 10) is RangeOutcome.Invalid)
        assertTrue(classifyRangeResponse(416, null, offset = 4, expectedSize = 10) is RangeOutcome.Invalid)
        assertTrue(classifyRangeResponse(500, null, offset = 0, expectedSize = 10) is RangeOutcome.Invalid)
    }

    @Test
    fun resumePlan_onlyForSameKnownVersion() {
        assertEquals(ResumePlan.From(4), resumePlan(partLength = 4, expectedSize = 10, partCTag = "c1", currentCTag = "c1"))
        assertEquals(ResumePlan.Complete, resumePlan(partLength = 10, expectedSize = 10, partCTag = "c1", currentCTag = "c1"))
        assertEquals(ResumePlan.FromZero, resumePlan(partLength = 0, expectedSize = 10, partCTag = "c1", currentCTag = "c1"))
        // Content changed, or partial file longer than the item.
        assertEquals(ResumePlan.FromZero, resumePlan(partLength = 4, expectedSize = 10, partCTag = "c1", currentCTag = "c2"))
        assertEquals(ResumePlan.FromZero, resumePlan(partLength = 12, expectedSize = 10, partCTag = "c1", currentCTag = "c1"))
        // Unknown version on either side: never considered identical (null == null included).
        assertEquals(ResumePlan.FromZero, resumePlan(partLength = 4, expectedSize = 10, partCTag = null, currentCTag = null))
        assertEquals(ResumePlan.FromZero, resumePlan(partLength = 4, expectedSize = 10, partCTag = "c1", currentCTag = null))
        assertEquals(ResumePlan.FromZero, resumePlan(partLength = 4, expectedSize = 0, partCTag = "c1", currentCTag = "c1"))
    }

    @Test
    fun cacheFileName_isOpaque_versioned_andSafe() {
        val name = cacheFileName("ITEM!123", "ctag-1", "IMG_0001.JPG", "image/jpeg")
        assertTrue(name, Regex("""^[0-9a-f]{16}_[0-9a-f]{8}\.jpg$""").matches(name))
        assertEquals(name, cacheFileName("ITEM!123", "ctag-1", "autre nom.jpg", null))
        assertNotEquals(name, cacheFileName("ITEM!123", "ctag-2", "IMG_0001.JPG", "image/jpeg"))
        assertNotEquals(name, cacheFileName("ITEM!124", "ctag-1", "IMG_0001.JPG", "image/jpeg"))
        // No piece of the original name and no path separator.
        val evil = cacheFileName("X", null, "../../etc/passwd", null)
        assertFalse(evil.contains('/'))
        assertFalse(evil.contains(".."))
        assertTrue(evil.endsWith(".bin"))
    }

    @Test
    fun fileExtension_fromNameThenMime() {
        assertEquals("heic", fileExtension("Photo.HEIC", null))
        assertEquals("mp4", fileExtension("clip", "video/mp4"))
        assertEquals("mov", fileExtension("clip.", "video/quicktime"))
        assertEquals("jpg", fileExtension("a.verylongext", "image/jpeg"))
        assertEquals("webp", fileExtension("sans-extension", "image/webp"))
        assertEquals("bin", fileExtension("nom.é", null))
        assertEquals("bin", fileExtension("x", "application/x-something-long"))
    }

    @Test
    fun safeDisplayName_stripsReservedAndControlCharacters() {
        assertEquals("a_b_c.jpg", safeDisplayName("""a/b\c.jpg"""))
        assertEquals("_ _ _ _ _ _ _.png", safeDisplayName(": * ? \" < > |.png"))
        assertEquals("passwd", safeDisplayName("../passwd").removePrefix("_"))
        assertFalse(safeDisplayName("../x").contains('/'))
        assertEquals("tab_nl_.jpg", safeDisplayName("tab\tnl\n.jpg"))
        assertEquals("Été à Noël 🎄.jpg", safeDisplayName("Été à Noël 🎄.jpg"))
        assertEquals("file", safeDisplayName(""))
        assertEquals("file", safeDisplayName("..."))
        assertEquals("file", safeDisplayName("   "))
        val long = safeDisplayName("a".repeat(300) + ".jpeg")
        assertEquals(120, long.length)
        assertTrue(long.endsWith(".jpeg"))
        // Never a split surrogate pair.
        val emoji = safeDisplayName("🎄".repeat(100))
        assertFalse(Character.isHighSurrogate(emoji.last()))
    }

    @Test
    fun progressIsThrottled() {
        assertFalse(shouldEmitProgress(lastEmitMs = 0, nowMs = 500, lastBytes = 0, bytes = 1_000))
        assertTrue(shouldEmitProgress(lastEmitMs = 0, nowMs = 1_000, lastBytes = 0, bytes = 1_000))
        assertTrue(shouldEmitProgress(lastEmitMs = 0, nowMs = 10, lastBytes = 0, bytes = 4L * 1024 * 1024))
        assertTrue(shouldEmitProgress(lastEmitMs = 0, nowMs = 10, lastBytes = 900, bytes = 1_000, totalBytes = 1_000))
    }

    @Test
    fun sameDrive_ignoresCase_andAcceptsUnknown() {
        assertTrue(sameDrive("ABCDEF0123", "abcdef0123"))
        assertFalse(sameDrive("ABCDEF0123", "1111111111"))
        assertTrue(sameDrive(null, "abc"))
        assertTrue(sameDrive("abc", null))
        assertTrue(sameDrive("", "abc"))
    }

    // --- Sweeping the copy folders ------------------------------------------------------------

    private val hour = 60L * 60 * 1000

    @Test
    fun sweep_neverTouchesKnownOrBusyFiles() {
        val entries = listOf(
            SweepEntry("known.jpg", isFile = true, ageMs = 100 * hour),
            SweepEntry("busy.jpg.part", isFile = true, ageMs = 100 * hour),
            SweepEntry("busy.jpg", isFile = true, ageMs = 100 * hour),
            SweepEntry("dir", isFile = false, ageMs = 100 * hour),
        )
        assertTrue(filesToSweep(entries, known = setOf("known.jpg"), busy = setOf("busy.jpg")).isEmpty())
    }

    @Test
    fun sweep_ageRules_partsAfter24h_orphansAfter1h() {
        val entries = listOf(
            // Copy renamed but not yet recorded: recent, kept.
            SweepEntry("fresh.jpg", isFile = true, ageMs = 30 * 60_000L),
            SweepEntry("orphan.jpg", isFile = true, ageMs = 2 * hour),
            // Partial file of a possible resume: kept for less than 24 h.
            SweepEntry("recent.jpg.part", isFile = true, ageMs = 2 * hour),
            SweepEntry("stale.jpg.part", isFile = true, ageMs = 25 * hour),
        )
        assertEquals(listOf("orphan.jpg", "stale.jpg.part"), filesToSweep(entries, known = emptySet(), busy = emptySet()))
    }

    @Test
    fun sweep_partOfAKnownCopyFollowsThePartRule() {
        // Partial file of a new version while the recorded copy has the same base name.
        val entries = listOf(SweepEntry("a.jpg.part", isFile = true, ageMs = 2 * hour))
        assertTrue(filesToSweep(entries, known = setOf("a.jpg"), busy = emptySet()).isEmpty())
    }
}
