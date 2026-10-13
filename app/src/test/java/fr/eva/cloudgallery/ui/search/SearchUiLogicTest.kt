package fr.eva.cloudgallery.ui.search

import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AlbumType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Search screen suggestions. */
class SearchUiLogicTest {

    private fun album(id: Long, name: String, count: Int = 3) = AlbumSummary(
        id = id,
        name = name,
        type = AlbumType.LOCAL_FOLDER,
        sourceKey = "local:$id",
        relativePath = null,
        mediaCount = count,
        coverMediaId = null,
        coverUri = null,
        coverType = null,
        coverDateModified = 0L,
        latestDate = 0L,
        isPinned = false,
    )

    @Test
    fun albumSuggestions_matchNames_skipEmptyAlbums_andAreBounded() {
        val albums = listOf(album(1, "Vacances"), album(2, "Vacances vides", count = 0), album(3, "Camera"), album(4, "Été vacances"))
        assertEquals(listOf(1L, 4L), albumSuggestions(albums, "vac").map { it.id })
        assertTrue(albumSuggestions(albums, "").isEmpty())
        val many = (1L..10L).map { album(it, "Vac $it") }
        assertEquals(2, albumSuggestions(many, "vac", max = 2).size)
    }

    @Test
    fun recentSuggestions_allWhenBlank_elseMatching() {
        val recent = listOf("plage", "août 2026")
        assertEquals(recent, recentSuggestions(recent, " "))
        assertEquals(listOf("août 2026"), recentSuggestions(recent, "aout"))
    }

    @Test
    fun submit_needsAWord_andTrims() {
        assertTrue(canSubmitSearch(" chat "))
        assertFalse(canSubmitSearch(" * "))
        assertEquals("chat noir", submittedQuery("  chat noir "))
    }
}
