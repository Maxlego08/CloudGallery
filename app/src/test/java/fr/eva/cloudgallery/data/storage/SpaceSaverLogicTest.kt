package fr.eva.cloudgallery.data.storage

import fr.eva.cloudgallery.data.db.dao.SpaceSaverRow
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SpaceSaverCandidate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Space saver: offered copies and re-check before moving to trash. */
class SpaceSaverLogicTest {

    private fun row(id: Long, merged: Boolean = true, userData: Boolean = false, type: MediaType = MediaType.IMAGE) =
        SpaceSaverRow(
            sourceId = id,
            mediaId = 100 + id,
            uri = "content://media/external/images/media/$id",
            sizeBytes = 1_000 * id,
            dateModified = 10 * id,
            mediaType = type,
            mimeType = "image/jpeg",
            displayName = "IMG_$id.jpg",
            dateTaken = 5 * id,
            mergedWithCloud = merged,
            hasUserData = userData,
        )

    private fun candidate(id: Long, size: Long = 1_000 * id, dateModified: Long = 10 * id, uri: String = "u$id") =
        SpaceSaverCandidate(id, 100 + id, uri, size, dateModified, false, "IMG_$id.jpg", 0)

    @Test
    fun signature_matchesTheSqlFormat() {
        assertEquals("1234:56:7", spaceSaverSignature(1234, 56, 7))
    }

    @Test
    fun list_keepsCopiesWithoutUserData_andMergedOnesWithUserData() {
        val list = spaceSaverListOf(
            listOf(
                row(1),
                row(2, merged = false),
                row(3, merged = true, userData = true),
                row(4, merged = false, userData = true, type = MediaType.VIDEO),
            ),
        )
        // Favorite or album of a media distinct from the OneDrive item: excluded (it would be lost).
        assertEquals(listOf(1L, 2L, 3L), list.candidates.map { it.sourceId })
        assertEquals(1, list.excludedUserData)
        assertEquals("content://media/external/images/media/1", list.candidates.first().uri)
    }

    @Test
    fun list_marksVideos() {
        val list = spaceSaverListOf(listOf(row(1, type = MediaType.VIDEO), row(2)))
        assertTrue(list.candidates[0].isVideo)
        assertEquals(false, list.candidates[1].isVideo)
    }

    @Test
    fun revalidate_dropsChangedOrVanishedCopies() {
        val reviewed = listOf(candidate(1), candidate(2), candidate(3), candidate(4))
        val fresh = listOf(
            candidate(1),
            candidate(2, size = 9), // content modified since the review
            candidate(3, uri = "autre"), // re-identified by MediaStore
            // 4: no longer offered (OneDrive item deleted, stale hash…)
        )
        assertEquals(listOf(1L), stillCandidates(reviewed, fresh).map { it.sourceId })
    }

    @Test
    fun revalidate_keepsTheReviewedOrderAndIgnoresNewCopies() {
        val reviewed = listOf(candidate(2), candidate(1))
        val fresh = listOf(candidate(1), candidate(2), candidate(3))
        assertEquals(listOf(2L, 1L), stillCandidates(reviewed, fresh).map { it.sourceId })
    }
}
