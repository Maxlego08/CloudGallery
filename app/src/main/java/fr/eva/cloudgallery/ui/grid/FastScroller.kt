package fr.eva.cloudgallery.ui.grid

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.UnfoldMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.paging.compose.LazyPagingItems
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneId
import kotlin.math.roundToInt

private val ThumbWidth = 22.dp
private val ThumbHeight = 48.dp
private val TouchWidth = 48.dp
private val TouchHeight = 64.dp
private val TrackMargin = 8.dp
private const val HIDE_DELAY_MS = 1_500L

/** Wait before reloading the grid elsewhere: the finger may still move. */
private const val JUMP_DEBOUNCE_MS = 90L

/** Maximum delay for pages to arrive after a jump. */
private const val JUMP_TIMEOUT_MS = 3_000L

/**
 * Fast-scroll thumb on the right of the grid (One UI style): visible while scrolling, then hidden
 * after [HIDE_DELAY_MS], draggable with the finger with a date bubble.
 *
 * The position spans the whole list ([index]: dates of all media), not just the loaded pages. A
 * period already loaded is reached with `scrollToItem`; otherwise [onJump] reloads the paging
 * starting from its first media, then the grid moves to its header as soon as it arrives. Only the
 * thumb captures gestures: drag-to-select and pinch remain intact.
 *
 * @param leadingItems grid items before the paged items (large title).
 * @param topPadding / [bottomPadding] bounds of the track (bars, tabs, insets).
 */
@Composable
internal fun FastScroller(
    state: LazyGridState,
    items: LazyPagingItems<GridItem>,
    index: FastScrollIndex,
    grouping: GridGrouping,
    leadingItems: Int,
    topPadding: Dp,
    bottomPadding: Dp,
    onJump: (offset: Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val locale = LocalConfiguration.current.locales[0]
    val zone = remember { ZoneId.systemDefault() }
    val colors = OneUiTheme.colors

    var dragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(0f) }
    var shown by remember { mutableStateOf(false) }
    /** Period targeted by the finger (epoch day of its start), null outside of a drag. */
    var targetPeriod by remember { mutableStateOf<Long?>(null) }

    val currentIndex by rememberUpdatedState(index)
    val currentGrouping by rememberUpdatedState(grouping)
    val currentOnJump by rememberUpdatedState(onJump)

    // Appears on scroll, disappears after a pause.
    val active = dragging || state.isScrollInProgress
    LaunchedEffect(active) {
        if (active) {
            shown = true
        } else {
            delay(HIDE_DELAY_MS)
            shown = false
        }
    }

    val scrollFraction by remember(state, items) {
        derivedStateOf {
            val idx = currentIndex
            val first = state.firstVisibleItemIndex - leadingItems
            val item = if (first in 0 until items.itemCount) items.peek(first) else null
            idx.thumbFraction(
                firstVisibleRank = item?.let { idx.rankOf(it, zone) },
                atTop = !state.canScrollBackward,
                atBottom = !state.canScrollForward,
            )
        }
    }

    // Jump to the targeted period: immediate if already loaded, otherwise the paging is reloaded.
    LaunchedEffect(state, items) {
        snapshotFlow { targetPeriod }.filterNotNull().collectLatest { epochDay ->
            val g = currentGrouping
            val key = headerKeyOf(g, epochDay)
            val loaded = items.itemSnapshotList.indexOfFirst { it?.key == key }
            if (loaded >= 0) {
                state.scrollToItem(leadingItems + loaded)
                return@collectLatest
            }
            delay(JUMP_DEBOUNCE_MS)
            currentOnJump(currentIndex.periodStartRank(epochDay, g, zone))
            withTimeoutOrNull(JUMP_TIMEOUT_MS) {
                // Header found: done. First media of the period at the top of the list (header not
                // inserted yet, previous page loading): scroll to it in the meantime.
                snapshotFlow {
                    val snapshot = items.itemSnapshotList
                    val header = snapshot.indexOfFirst { it?.key == key }
                    when {
                        header >= 0 -> header to true
                        (snapshot.firstOrNull() as? GridItem.Media)
                            ?.let { periodStartEpochDay(it.media.dateTaken, g, zone) == epochDay } == true -> 0 to false
                        else -> null
                    }
                }.filterNotNull().first { (position, isHeader) ->
                    state.scrollToItem(leadingItems + position)
                    isHeader
                }
            }
        }
    }

    AnimatedVisibility(
        visible = shown,
        enter = fadeIn(tween(150)),
        exit = fadeOut(tween(250)),
        modifier = modifier.fillMaxSize(),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val trackTopPx = with(density) { (topPadding + TrackMargin).toPx() }
            val trackBottomPx = with(density) { maxHeight.toPx() - (bottomPadding + TrackMargin).toPx() }
            val thumbHeightPx = with(density) { ThumbHeight.toPx() }
            val touchHeightPx = with(density) { TouchHeight.toPx() }
            val range = (trackBottomPx - trackTopPx - thumbHeightPx).coerceAtLeast(0f)
            val fraction = if (dragging) dragFraction else scrollFraction
            val thumbTopPx = trackTopPx + fraction * range
            // Read inside the gesture (pointerInput block not restarted during the drag).
            val currentThumbTop by rememberUpdatedState(thumbTopPx)
            val currentFraction by rememberUpdatedState(fraction)
            val currentTrackTop by rememberUpdatedState(trackTopPx)
            val currentTrackBottom by rememberUpdatedState(trackBottomPx)

            // Date bubble during the drag, to the left of the thumb.
            val period = targetPeriod
            if (dragging && period != null) {
                var bubbleHeight by remember { mutableFloatStateOf(0f) }
                Box(
                    Modifier
                        .align(Alignment.TopEnd)
                        .offset { IntOffset(0, (thumbTopPx + thumbHeightPx / 2f - bubbleHeight / 2f).roundToInt()) }
                        .padding(end = TouchWidth + 4.dp)
                        .onSizeChanged { bubbleHeight = it.height.toFloat() }
                        .shadow(4.dp, RoundedCornerShape(50))
                        .background(colors.sheet, RoundedCornerShape(50))
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                ) {
                    Text(
                        text = fastScrollLabel(period, grouping, locale),
                        style = OneUiTheme.typography.sectionHeader,
                        color = colors.textPrimary,
                    )
                }
            }

            val description = stringResource(R.string.fast_scroll_a11y)
            Box(
                contentAlignment = Alignment.CenterEnd,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset { IntOffset(0, (thumbTopPx - (touchHeightPx - thumbHeightPx) / 2f).roundToInt()) }
                    .size(TouchWidth, TouchHeight)
                    .semantics { contentDescription = description }
                    .pointerInput(state) {
                        var pointerY = 0f
                        var lastMonth = Int.MIN_VALUE
                        fun update() {
                            val f = trackFraction(pointerY, currentTrackTop, currentTrackBottom, thumbHeightPx)
                            dragFraction = f
                            val idx = currentIndex
                            val p = idx.periodAt(idx.rankAt(f), currentGrouping, zone) ?: return
                            if (p != targetPeriod) targetPeriod = p
                            val month = monthOrdinal(p)
                            if (lastMonth != Int.MIN_VALUE && month != lastMonth) {
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }
                            lastMonth = month
                        }
                        detectVerticalDragGestures(
                            onDragStart = { offset ->
                                // Absolute finger position in the track (the thumb follows the finger).
                                pointerY = currentThumbTop - (touchHeightPx - thumbHeightPx) / 2f + offset.y
                                dragFraction = currentFraction
                                lastMonth = Int.MIN_VALUE
                                dragging = true
                                update()
                            },
                            onDragEnd = {
                                dragging = false
                                targetPeriod = null
                            },
                            onDragCancel = {
                                dragging = false
                                targetPeriod = null
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                pointerY += dragAmount
                                update()
                            },
                        )
                    },
            ) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(ThumbWidth, ThumbHeight)
                        .shadow(3.dp, RoundedCornerShape(50))
                        .background(colors.sheet, RoundedCornerShape(50)),
                ) {
                    Icon(
                        imageVector = Icons.Filled.UnfoldMore,
                        contentDescription = null,
                        tint = colors.textSecondary,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}
