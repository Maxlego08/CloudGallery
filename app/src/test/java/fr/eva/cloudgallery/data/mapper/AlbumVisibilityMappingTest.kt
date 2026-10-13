package fr.eva.cloudgallery.data.mapper

import fr.eva.cloudgallery.data.db.entity.AlbumRow
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.AlbumVisibility
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumVisibilityMappingTest {

    private fun row(isHidden: Boolean, hideMedia: Boolean) = AlbumRow(
        id = 1, name = "Camera", customName = null, type = AlbumType.LOCAL_FOLDER, sourceKey = "local:1",
        relativePath = "DCIM/Camera/", isPinned = false, isHidden = isHidden, hideMedia = hideMedia, sortIndex = 0,
        mediaCount = 3, coverMediaId = null, coverUri = null, coverType = null, coverDateModified = null,
        coverCloudItemId = null, coverCloudCTag = null, latestDate = null,
    )

    @Test
    fun visibility_followsBothFlags() {
        assertEquals(AlbumVisibility.SHOWN, row(isHidden = false, hideMedia = false).toSummary().visibility)
        assertEquals(AlbumVisibility.HIDDEN_IN_ALBUMS, row(isHidden = true, hideMedia = false).toSummary().visibility)
        assertEquals(AlbumVisibility.HIDDEN_EVERYWHERE, row(isHidden = true, hideMedia = true).toSummary().visibility)
    }
}
