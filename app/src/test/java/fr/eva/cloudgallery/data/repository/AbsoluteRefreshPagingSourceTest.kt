package fr.eva.cloudgallery.data.repository

import androidx.paging.PagingConfig
import androidx.paging.PagingSource.LoadResult.Page
import androidx.paging.PagingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AbsoluteRefreshPagingSourceTest {

    private val config = PagingConfig(pageSize = 150, enablePlaceholders = false, initialLoadSize = 450, maxSize = 1_800)

    private fun page(offset: Int, size: Int, total: Int = 50_000) = Page(
        data = List(size) { offset + it },
        prevKey = offset.takeIf { it > 0 },
        nextKey = offset + size,
        itemsBefore = offset,
        itemsAfter = total - offset - size,
    )

    private fun state(anchor: Int?, vararg pages: Page<Int, Int>) =
        PagingState(pages.toList(), anchor, config, leadingPlaceholderCount = 0)

    @Test
    fun `ancre relative aux pages chargées ramenée au rang absolu`() {
        // Pages lâchées en haut : la première page en mémoire commence au rang 30 000.
        val s = state(300, page(30_000, 150), page(30_150, 150), page(30_300, 150))
        assertEquals(30_075, absoluteRefreshKey(s))
    }

    @Test
    fun `en haut de liste, clé bornée à zéro`() {
        assertEquals(0, absoluteRefreshKey(state(10, page(0, 450))))
    }

    @Test
    fun `sans ancre ni page, rechargement en haut`() {
        assertNull(absoluteRefreshKey(state(null, page(1_000, 150))))
        assertNull(absoluteRefreshKey(state(5)))
    }

    @Test
    fun `rang inconnu, rechargement en haut`() {
        val unknown = Page(data = listOf(1, 2), prevKey = null, nextKey = 2)
        assertNull(absoluteRefreshKey(state(1, unknown)))
    }
}
