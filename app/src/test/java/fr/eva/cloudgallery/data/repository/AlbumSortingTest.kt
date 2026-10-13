package fr.eva.cloudgallery.data.repository

import fr.eva.cloudgallery.domain.model.AlbumSort
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AlbumType
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Locale

class AlbumSortingTest {

    private fun album(
        id: Long,
        name: String,
        path: String? = null,
        count: Int = 1,
        latest: Long = 0,
        pinned: Boolean = false,
        type: AlbumType = AlbumType.LOCAL_FOLDER,
    ) = AlbumSummary(
        id = id,
        name = name,
        type = type,
        sourceKey = if (type == AlbumType.VIRTUAL) null else "local:$id",
        relativePath = path,
        mediaCount = count,
        coverMediaId = null,
        coverUri = null,
        coverType = null,
        coverDateModified = 0,
        latestDate = latest,
        isPinned = pinned,
    )

    private val camera = album(1, "Camera", "DCIM/Camera/", count = 500, latest = 100)
    private val screenshots = album(2, "Screenshots", "DCIM/Screenshots/", count = 50, latest = 300)
    private val whatsapp = album(3, "WhatsApp Images", "Pictures/WhatsApp Images/", count = 900, latest = 500)
    private val eclair = album(4, "Éclairs", "Pictures/Eclairs/", count = 3, latest = 200)
    private val vacances = album(5, "vacances", null, count = 10, latest = 400, type = AlbumType.VIRTUAL)

    private val all = listOf(vacances, eclair, whatsapp, screenshots, camera)

    private fun sorted(sort: AlbumSort, list: List<AlbumSummary> = all) =
        list.sortedWith(albumComparator(sort, Locale.FRENCH)).map { it.id }

    @Test
    fun custom_cameraThenScreenshotsThenRecent() {
        assertEquals(listOf(1L, 2L, 3L, 5L, 4L), sorted(AlbumSort.CUSTOM))
    }

    @Test
    fun custom_picturesScreenshotsAlsoRanked() {
        val ps = album(6, "Screenshots", "Pictures/Screenshots", latest = 1)
        assertEquals(listOf(1L, 6L, 3L), sorted(AlbumSort.CUSTOM, listOf(whatsapp, ps, camera)))
    }

    @Test
    fun name_isLocaleAwareAndCaseInsensitive() {
        // "Éclairs" sorts with the E's, lowercase "vacances" with the V's.
        assertEquals(listOf(1L, 4L, 2L, 5L, 3L), sorted(AlbumSort.NAME))
    }

    @Test
    fun recent_byLatestDateDesc() {
        assertEquals(listOf(3L, 5L, 2L, 4L, 1L), sorted(AlbumSort.RECENT))
    }

    @Test
    fun count_desc() {
        assertEquals(listOf(3L, 1L, 2L, 5L, 4L), sorted(AlbumSort.COUNT))
    }

    @Test
    fun pinnedAlwaysFirst() {
        val pinned = eclair.copy(isPinned = true)
        val list = listOf(vacances, pinned, whatsapp, screenshots, camera)
        for (sort in AlbumSort.entries) {
            assertEquals(4L, sorted(sort, list).first())
        }
    }

    @Test
    fun defaultRank() {
        assertEquals(0, defaultAlbumRank("DCIM/Camera/"))
        assertEquals(0, defaultAlbumRank("dcim/camera"))
        assertEquals(1, defaultAlbumRank("Pictures/Screenshots/"))
        assertEquals(1, defaultAlbumRank("DCIM/Screenshots/"))
        assertEquals(2, defaultAlbumRank("Download/"))
        assertEquals(2, defaultAlbumRank(null))
    }
}
