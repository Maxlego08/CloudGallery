package fr.eva.cloudgallery.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState

/**
 * Wrapper around an offset-paged source (Room's LimitOffsetPagingSource) that fixes the refresh
 * key after invalidation.
 *
 * Without placeholders, `PagingState.anchorPosition` counts from the first page still in memory,
 * whereas Room interprets it as an absolute offset. As soon as pages had been dropped at the top
 * (`maxSize`) or after a fast-scroll jump, any database write sent the grid thousands of media
 * back up. The absolute offset of the first page (`itemsBefore`) is added.
 */
internal class AbsoluteRefreshPagingSource<V : Any>(
    private val delegate: PagingSource<Int, V>,
) : PagingSource<Int, V>() {

    init {
        // Invalidation in both directions (no loop: invalidate() notifies only once).
        delegate.registerInvalidatedCallback { invalidate() }
        registerInvalidatedCallback { delegate.invalidate() }
    }

    override val jumpingSupported: Boolean get() = delegate.jumpingSupported

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, V> = delegate.load(params)

    override fun getRefreshKey(state: PagingState<Int, V>): Int? = absoluteRefreshKey(state)
}

/**
 * Offset of the first row to reload: half the initial load before the anchor item, like Room,
 * but as an absolute offset. Null (reload at the top) without an anchor or a known offset.
 */
internal fun <V : Any> absoluteRefreshKey(state: PagingState<Int, V>): Int? {
    val anchor = state.anchorPosition ?: return null
    val firstPage = state.pages.firstOrNull { it.data.isNotEmpty() } ?: return null
    val before = firstPage.itemsBefore
    if (before == PagingSource.LoadResult.Page.COUNT_UNDEFINED) return null
    return maxOf(0, before + anchor - state.config.initialLoadSize / 2)
}
