package fr.eva.cloudgallery.ui.viewer

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.ui.image.ThumbnailImage
import fr.eva.cloudgallery.ui.image.offlineThumbnail
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val CellSize = 44.dp
private val CellSpacing = 2.dp
private val CellShape = RoundedCornerShape(6.dp)

/** Number of cells loaded on either side of the visible area. */
private const val PREFETCH = 15

/**
 * Thumbnail strip above the action bar. The current item is centered and outlined; summaries
 * are loaded on demand for the visible window ([onRequestSummaries]).
 */
@Composable
internal fun Filmstrip(
    ids: List<Long>,
    currentIndex: Int,
    summaries: Map<Long, MediaSummary>,
    onRequestSummaries: (List<Long>) -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState(initialFirstVisibleItemIndex = currentIndex.coerceAtLeast(0))
    val currentIds by rememberUpdatedState(ids)
    val requestSummaries by rememberUpdatedState(onRequestSummaries)

    // The strip follows the pager (the current item stays centered thanks to the contentPadding).
    LaunchedEffect(currentIndex) {
        if (currentIndex < 0) return@LaunchedEffect
        val first = listState.firstVisibleItemIndex
        if (abs(first - currentIndex) > 30) listState.scrollToItem(currentIndex) else listState.animateScrollToItem(currentIndex)
    }

    LaunchedEffect(listState) {
        snapshotFlow {
            val visible = listState.layoutInfo.visibleItemsInfo
            (visible.firstOrNull()?.index ?: listState.firstVisibleItemIndex) to (visible.lastOrNull()?.index ?: listState.firstVisibleItemIndex)
        }
            .distinctUntilChanged()
            .collect { (first, last) ->
                val all = currentIds
                if (all.isEmpty()) return@collect
                val from = max(0, first - PREFETCH)
                val to = min(all.size, last + PREFETCH + 1)
                if (from < to) requestSummaries(all.subList(from, to).toList())
            }
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val sidePadding = (maxWidth / 2 - CellSize / 2).coerceAtLeast(0.dp)
        LazyRow(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .height(CellSize + 12.dp),
            contentPadding = PaddingValues(horizontal = sidePadding, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(CellSpacing),
        ) {
            itemsIndexed(ids, key = { _, id -> id }) { index, id ->
                val summary = summaries[id]
                val selected = index == currentIndex
                val description = stringResource(R.string.viewer_filmstrip_item, index + 1, ids.size)
                ThumbnailImage(
                    uri = summary?.uri,
                    isVideo = summary?.type == MediaType.VIDEO,
                    version = summary?.dateModified ?: 0L,
                    cloud = summary?.cloudThumb,
                    offline = summary?.offlineThumbnail(),
                    modifier = Modifier
                        .size(CellSize)
                        .clip(CellShape)
                        .then(if (selected) Modifier.border(2.dp, Color.White, CellShape) else Modifier.alpha(0.75f))
                        .semantics {
                            contentDescription = description
                            this.selected = selected
                        }
                        .clickable { onSelect(index) },
                )
            }
        }
    }
}
