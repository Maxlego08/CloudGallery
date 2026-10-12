package fr.eva.cloudgallery.ui.viewer

import fr.eva.cloudgallery.domain.model.CloudThumbRef
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaSourceInfo
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.domain.model.SourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Bande de miniatures : résumé en cache rafraîchi depuis le média affiché. */
class ViewerSummaryTest {

    private val cached = MediaSummary(
        id = 1, type = MediaType.IMAGE, mimeType = "image/jpeg", dateTaken = 5, durationMs = 0, width = 1, height = 1,
        orientation = 0, uri = null, dateModified = 10, isFavorite = false, hasLocal = false, hasCloud = true,
        hasOffline = false, cloudThumb = CloudThumbRef("ITEM!1", "old"),
    )

    private fun details(cTag: String, favorite: Boolean = false, dateModified: Long = 10) = MediaDetails(
        id = 1, type = MediaType.IMAGE, displayName = "a.jpg", mimeType = "image/jpeg", dateTaken = 5, dateAdded = 5,
        dateModified = dateModified, width = 1, height = 1, durationMs = 0, sizeBytes = 1, orientation = 0, uri = null,
        isFavorite = favorite,
        sources = listOf(
            MediaSourceInfo(1, SourceKind.ONEDRIVE, "ITEM!1", null, "onedrive:P", "P", "OneDrive/P/", 1, dateModified),
        ),
        cloudThumb = CloudThumbRef("ITEM!1", cTag),
    )

    @Test
    fun newCloudContent_updatesFilmstripThumbnail() {
        val refreshed = refreshedSummary(cached, details("new", dateModified = 20))
        assertEquals(CloudThumbRef("ITEM!1", "new"), refreshed.cloudThumb)
        assertEquals(20L, refreshed.dateModified)
        assertTrue(refreshed.hasCloud)
        assertFalse(refreshed.hasLocal)
    }

    @Test
    fun favoriteChange_isReflected() {
        assertTrue(refreshedSummary(cached, details("old", favorite = true)).isFavorite)
    }

    @Test
    fun unchangedMedia_givesEqualSummary() {
        val refreshed = refreshedSummary(cached, details("old"))
        assertEquals(cached, refreshed)
    }

    @Test
    fun keptOffline_updatesFilmstripBadge() {
        val withCopy = details("old").let { d ->
            d.copy(sources = d.sources + MediaSourceInfo(2, SourceKind.OFFLINE_COPY, "ITEM!1", null, null, null, null, 1, 10, "old"))
        }
        val refreshed = refreshedSummary(cached, withCopy)
        assertTrue(refreshed.hasOffline)
        assertFalse(refreshedSummary(refreshed, details("old")).hasOffline)
    }
}
