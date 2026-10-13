package fr.eva.cloudgallery.data.dedup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Local file signature, local / OneDrive pairing, unmerging and hash batches. */
class DedupLogicTest {

    @Test
    fun signature_changesWithSizeDateOrGeneration_andMatchesTheSqlForm() {
        assertEquals("5:10:3", localHashSignature(5, 10, 3))
        assertTrue(localHashSignature(5, 10, 3) != localHashSignature(6, 10, 3))
        assertTrue(localHashSignature(5, 10, 3) != localHashSignature(5, 11, 3))
        assertTrue(localHashSignature(5, 10, 3) != localHashSignature(5, 10, 4))
        // Same form as the SQL expression `sizeBytes || ':' || dateModified || ':' || generation`.
        assertEquals("(sizeBytes || ':' || dateModified || ':' || generation)", LOCAL_HASH_SIGNATURE_SQL)
    }

    @Test
    fun contentHash_usesTheOneDrivePrefix() {
        assertEquals("qxh:abc=", contentHashOf("abc="))
        assertNull(contentHashOf(null))
    }

    private fun candidate(localSource: Long, localMedia: Long, cloudSource: Long, cloudMedia: Long, hash: String = "H", size: Long = 100) =
        MergeCandidateRow(localSource, localMedia, cloudSource, cloudMedia, hash, size)

    @Test
    fun planMerges_pairsOneLocalWithOneCloudItem() {
        assertEquals(listOf(MergePlan(localMediaId = 1, cloudMediaId = 2, cloudSourceId = 20)), planMerges(listOf(candidate(10, 1, 20, 2))))
    }

    @Test
    fun planMerges_duplicates_pairInSourceOrder_extrasStaySeparate() {
        // Two identical local files, three identical OneDrive items: 6 candidate pairs.
        val rows = listOf(11L to 1L, 12L to 2L).flatMap { (ls, lm) ->
            listOf(23L to 6L, 21L to 4L, 22L to 5L).map { (cs, cm) -> candidate(ls, lm, cs, cm) }
        }
        assertEquals(
            listOf(MergePlan(1, 4, 21), MergePlan(2, 5, 22)),
            planMerges(rows.shuffled(kotlin.random.Random(3))),
        )
    }

    @Test
    fun planMerges_groupsByHashAndSize_andNeverUsesAMediaTwice() {
        val plans = planMerges(
            listOf(
                candidate(10, 1, 20, 2, hash = "A"),
                candidate(11, 3, 21, 4, hash = "B"),
                // Inconsistent data: the same local media in another group.
                candidate(10, 1, 22, 5, hash = "C"),
            ),
        )
        assertEquals(2, plans.size)
        assertEquals(plans.map { it.localMediaId }.toSet().size, plans.size)
        assertEquals(plans.map { it.cloudMediaId }.toSet().size, plans.size)
    }

    private fun merged(localSize: Long = 100, localHash: String? = "H", current: Boolean = true, cloudSize: Long = 100, cloudHash: String? = "H") =
        MergedRow(1, localSize, localHash, current, cloudSourceId = 2, cloudItemId = "A", cloudSizeBytes = cloudSize, cloudQuickXorHash = cloudHash)

    @Test
    fun split_onSizeChange_orTwoKnownDifferentHashes_only() {
        assertFalse(shouldSplit(merged()))
        assertTrue(shouldSplit(merged(localSize = 101)))
        assertTrue(shouldSplit(merged(cloudHash = "X")))
        assertTrue(shouldSplit(merged(localHash = "X")))
        // Stale local hash, same size: kept until the next computation.
        assertFalse(shouldSplit(merged(localHash = "X", current = false)))
        // Hash unknown on one side: kept.
        assertFalse(shouldSplit(merged(cloudHash = null)))
        assertFalse(shouldSplit(merged(localHash = null)))
    }

    private fun target(id: Long, size: Long) = HashTarget(id, mediaId = id, uri = "content://x/$id", sizeBytes = size, dateModified = 1, generation = 1)

    @Test
    fun hashBatch_probableDuplicatesFirst_thenSourceOrder_withinBudgets() {
        val targets = listOf(target(1, 10), target(2, 500), target(3, 20), target(4, 500), target(5, 30))
        val batch = nextHashBatch(targets, cloudSizes = setOf(500L), maxCount = 3, maxBytes = 10_000)
        assertEquals(listOf(2L, 4L, 1L), batch.map { it.sourceId })
        // Byte budget: stops before exceeding it.
        assertEquals(listOf(2L), nextHashBatch(targets, setOf(500L), maxCount = 10, maxBytes = 600).map { it.sourceId })
        // A file larger than the budget still goes through alone (otherwise it would never be hashed).
        assertEquals(listOf(2L), nextHashBatch(targets, setOf(500L), maxCount = 10, maxBytes = 100).map { it.sourceId })
        assertTrue(nextHashBatch(emptyList(), emptySet(), 10, 100).isEmpty())
    }

    @Test
    fun hashTarget_signature_isTheCurrentFileState() {
        assertEquals(localHashSignature(500, 1, 1), target(2, 500).signature)
    }
}
