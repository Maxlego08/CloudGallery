package fr.eva.cloudgallery.data.repository

import androidx.paging.PagingSource
import androidx.paging.PagingState

/**
 * Enveloppe d'une source paginée par rang (LimitOffsetPagingSource de Room) qui corrige la clé de
 * rechargement après invalidation.
 *
 * Sans placeholders, `PagingState.anchorPosition` compte à partir de la première page encore en
 * mémoire, alors que Room l'interprète comme un rang absolu. Dès que des pages ont été lâchées en
 * haut (`maxSize`) ou après un saut du défilement rapide, toute écriture en base renvoyait la grille
 * des milliers de médias plus haut. Le rang absolu de la première page (`itemsBefore`) est rajouté.
 */
internal class AbsoluteRefreshPagingSource<V : Any>(
    private val delegate: PagingSource<Int, V>,
) : PagingSource<Int, V>() {

    init {
        // Invalidation dans les deux sens (sans boucle : invalidate() ne notifie qu'une fois).
        delegate.registerInvalidatedCallback { invalidate() }
        registerInvalidatedCallback { delegate.invalidate() }
    }

    override val jumpingSupported: Boolean get() = delegate.jumpingSupported

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, V> = delegate.load(params)

    override fun getRefreshKey(state: PagingState<Int, V>): Int? = absoluteRefreshKey(state)
}

/**
 * Rang de la première ligne à recharger : la moitié du chargement initial avant l'élément ancre,
 * comme Room, mais en rang absolu. Null (rechargement en haut) sans ancre ni rang connu.
 */
internal fun <V : Any> absoluteRefreshKey(state: PagingState<Int, V>): Int? {
    val anchor = state.anchorPosition ?: return null
    val firstPage = state.pages.firstOrNull { it.data.isNotEmpty() } ?: return null
    val before = firstPage.itemsBefore
    if (before == PagingSource.LoadResult.Page.COUNT_UNDEFINED) return null
    return maxOf(0, before + anchor - state.config.initialLoadSize / 2)
}
