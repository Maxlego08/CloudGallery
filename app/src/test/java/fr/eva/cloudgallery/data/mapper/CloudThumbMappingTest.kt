package fr.eva.cloudgallery.data.mapper

import fr.eva.cloudgallery.data.db.entity.AlbumRow
import fr.eva.cloudgallery.data.db.entity.MediaRow
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.CloudThumbRef
import fr.eva.cloudgallery.domain.model.MediaType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Référence de miniature OneDrive portée par les lignes de grille et d'album (sans URI locale). */
class CloudThumbMappingTest {

    private fun row(cloudItemId: String?, cTag: String?) = MediaRow(
        id = 1, mediaType = MediaType.IMAGE, mimeType = "image/jpeg", dateTaken = 10, dateModified = 42,
        durationMs = 0, width = 1, height = 1, orientation = 0, primaryUri = null,
        hasLocal = false, hasCloud = cloudItemId != null, hasOffline = false, isFavorite = false,
        cloudItemId = cloudItemId, cloudCTag = cTag,
    )

    private fun album(coverItemId: String?, coverCTag: String?) = AlbumRow(
        id = 1, name = "Photos", customName = null, type = AlbumType.CLOUD_FOLDER, sourceKey = "onedrive:P",
        relativePath = "OneDrive/Photos/", isPinned = false, sortIndex = 0, mediaCount = 1, coverMediaId = 1,
        coverUri = null, coverType = MediaType.IMAGE, coverDateModified = 7, coverCloudItemId = coverItemId,
        coverCloudCTag = coverCTag, latestDate = 7,
    )

    @Test
    fun mediaRow_withCloudItem_hasThumbRefVersionedByCTag() {
        val summary = row("ITEM!1", "{CTAG,2}").toSummary()
        assertNull(summary.uri)
        assertEquals(CloudThumbRef("ITEM!1", "{CTAG,2}"), summary.cloudThumb)
    }

    @Test
    fun mediaRow_withoutCTag_usesModificationDateAsVersion() {
        assertEquals(CloudThumbRef("ITEM!1", "m42"), row("ITEM!1", null).toSummary().cloudThumb)
        assertEquals(CloudThumbRef("ITEM!1", "m42"), row("ITEM!1", " ").toSummary().cloudThumb)
    }

    @Test
    fun mediaRow_withoutCloudItem_hasNoThumbRef() {
        assertNull(row(null, null).toSummary().cloudThumb)
    }

    @Test
    fun albumRow_exposesCloudCover() {
        assertEquals(CloudThumbRef("C!9", "t"), album("C!9", "t").toSummary().coverCloud)
        assertEquals(CloudThumbRef("C!9", "m7"), album("C!9", null).toSummary().coverCloud)
        assertNull(album(null, null).toSummary().coverCloud)
    }
}
