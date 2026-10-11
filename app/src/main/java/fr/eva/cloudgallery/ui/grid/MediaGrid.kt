package fr.eva.cloudgallery.ui.grid

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridItemInfo
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.OfflinePin
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.paging.compose.LazyPagingItems
import androidx.paging.compose.itemContentType
import androidx.paging.compose.itemKey
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.ui.components.OneUiCheckCircle
import fr.eva.cloudgallery.ui.image.ThumbnailImage
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import fr.eva.cloudgallery.ui.util.Formatters
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.abs

private const val CONTENT_TYPE_HEADER = "grid_header"
private const val CONTENT_TYPE_MEDIA = "grid_media"

/** Seuils de pincement (zoom cumulé) déclenchant un changement de nombre de colonnes. */
private const val PINCH_OUT_THRESHOLD = 1.25f
private const val PINCH_IN_THRESHOLD = 0.8f

/**
 * Ancrage mémorisé avant un changement de colonnes : l'élément [key] doit rester à [pointerY],
 * exprimé en coordonnées de contenu (même repère que LazyGridItemInfo.offset).
 */
private class ZoomAnchor(val key: Any?, val pointerY: Float, val delta: Int, val timeMs: Long)

@Stable
private class AnchorHolder {
    var anchor: ZoomAnchor? = null
}

/** Résultat d'un test de position sur la grille (index dans [LazyPagingItems]). */
private class GridHit(val pagingIndex: Int, val item: GridItem?, val info: LazyGridItemInfo)

/**
 * Grille paginée haute performance façon Samsung Galerie : cellules carrées espacées de 2 dp,
 * en-têtes de jour / mois pleine largeur, pincement pour changer de colonnes, appui long puis
 * glisser pour sélectionner une plage, défilement automatique près des bords.
 *
 * Les gestes (tap, appui long, glisser, pincer) sont gérés au niveau de la grille et non
 * par cellule, afin qu'un appui long ne déclenche jamais aussi un clic.
 *
 * @param onDragSelect ajoute (select = true) ou retire des identifiants de la sélection (éléments chargés).
 * @param onDragSelectFinished fin du glisser : la plage complète entre l'ancre et le dernier
 *   élément est à résoudre en base (éléments non chargés compris).
 * @param onHeaderToggle sélectionne / désélectionne tous les médias d'un jour (résolus en base).
 * @param dayIds identifiants complets des jours déjà résolus en base (clé : epochDay de l'en-tête).
 * @param onZoomChange -1 = moins de colonnes (écarter les doigts), +1 = plus de colonnes.
 * @param header éléments ajoutés en tête de grille (grand titre), avec des clés String.
 */
@Composable
fun MediaGrid(
    items: LazyPagingItems<GridItem>,
    columns: Int,
    state: LazyGridState,
    selection: Set<Long>,
    selectionMode: Boolean,
    onItemClick: (GridItem.Media) -> Unit,
    onItemLongClick: (GridItem.Media) -> Unit,
    onDragSelect: (ids: Set<Long>, select: Boolean) -> Unit,
    onDragSelectFinished: (anchor: MediaSummary, end: MediaSummary, select: Boolean) -> Unit,
    onHeaderToggle: (GridItem.Header) -> Unit,
    onZoomChange: (delta: Int) -> Unit,
    modifier: Modifier = Modifier,
    dayIds: Map<Long, List<Long>> = emptyMap(),
    showVideoDuration: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(0.dp),
    header: (LazyGridScope.() -> Unit)? = null,
) {
    val spacing = OneUiTheme.dimens.thumbnailSpacing
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current

    val currentSelection by rememberUpdatedState(selection)
    val currentSelectionMode by rememberUpdatedState(selectionMode)
    val currentOnItemClick by rememberUpdatedState(onItemClick)
    val currentOnItemLongClick by rememberUpdatedState(onItemLongClick)
    val currentOnDragSelect by rememberUpdatedState(onDragSelect)
    val currentOnDragSelectFinished by rememberUpdatedState(onDragSelectFinished)
    val currentOnHeaderToggle by rememberUpdatedState(onHeaderToggle)
    val currentOnZoomChange by rememberUpdatedState(onZoomChange)
    val currentItems by rememberUpdatedState(items)

    val topEdgePx = with(density) { contentPadding.calculateTopPadding().toPx() }
    val bottomEdgePx = with(density) { contentPadding.calculateBottomPadding().toPx() }
    val autoScrollZonePx = with(density) { 72.dp.toPx() }
    val maxAutoScrollPx = with(density) { 22.dp.toPx() }
    val currentTopEdge by rememberUpdatedState(topEdgePx)
    val currentBottomEdge by rememberUpdatedState(bottomEdgePx)

    // Retour visuel du pincement : échelle suivant les doigts + fondu lors du changement de colonnes.
    val pinchScale = remember { Animatable(1f) }
    val stepScale = remember { Animatable(1f) }
    val stepAlpha = remember { Animatable(1f) }
    var pivot by remember { mutableStateOf(Offset.Unspecified) }
    val anchorHolder = remember { AnchorHolder() }

    fun hitTest(position: Offset): GridHit? {
        val layout = state.layoutInfo
        // LazyGridItemInfo.offset est relatif au début du contenu (marge intérieure exclue), alors que
        // les positions du pointeur sont relatives au conteneur : on retire la marge intérieure.
        val p = toContentCoordinates(position, layout.beforeContentPadding)
        val info = layout.visibleItemsInfo.firstOrNull {
            p.x >= it.offset.x && p.x < it.offset.x + it.size.width &&
                p.y >= it.offset.y && p.y < it.offset.y + it.size.height
        } ?: return null
        val paging = currentItems
        val leading = layout.totalItemsCount - paging.itemCount
        val index = info.index - leading
        if (index < 0 || index >= paging.itemCount) return null
        return GridHit(index, paging.peek(index), info)
    }

    fun mediaIdsBetween(from: Int, to: Int): Set<Long> {
        val paging = currentItems
        val last = paging.itemCount - 1
        val start = minOf(from, to).coerceIn(0, maxOf(last, 0))
        val end = maxOf(from, to).coerceIn(0, maxOf(last, 0))
        if (last < 0) return emptySet()
        val result = HashSet<Long>(end - start + 1)
        for (i in start..end) (paging.peek(i) as? GridItem.Media)?.let { result += it.media.id }
        return result
    }

    // Après un changement de colonnes, replace l'élément ancré sous les doigts.
    LaunchedEffect(columns) {
        val anchor = anchorHolder.anchor ?: return@LaunchedEffect
        anchorHolder.anchor = null
        if (System.currentTimeMillis() - anchor.timeMs > 1_500) return@LaunchedEffect
        // Transition : léger zoom + fondu vers la nouvelle disposition.
        launch {
            stepScale.snapTo(if (anchor.delta < 0) 0.92f else 1.08f)
            stepScale.animateTo(1f, tween(260))
        }
        launch {
            stepAlpha.snapTo(0.35f)
            stepAlpha.animateTo(1f, tween(260))
        }
        if (anchor.key == null) return@LaunchedEffect
        withFrameNanos { }
        val leading = state.layoutInfo.totalItemsCount - items.itemCount
        val visible = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchor.key }
        val index = visible?.index ?: run {
            val snapshot = items.itemSnapshotList
            val i = snapshot.indexOfFirst { it?.key == anchor.key }
            if (i >= 0) i + leading else -1
        }
        if (index >= 0) {
            state.scrollToItem(index)
            val info = state.layoutInfo.visibleItemsInfo.firstOrNull { it.key == anchor.key }
            if (info != null) {
                val delta = info.offset.y + info.size.height / 2f - anchor.pointerY
                if (abs(delta) > 1f) state.scrollBy(delta)
            }
        }
    }

    val pinchModifier = Modifier.pointerInput(state) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            var accumulated = 1f
            var pinching = false
            do {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val pressed = event.changes.count { it.pressed }
                if (pressed >= 2) {
                    pinching = true
                    accumulated *= event.calculateZoom()
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (centroid != Offset.Unspecified) pivot = centroid
                    // On ne consomme qu'avec deux doigts : le défilement à un doigt reste libre.
                    event.changes.forEach { it.consume() }
                    val visual = accumulated.coerceIn(0.88f, 1.12f)
                    scope.launch { pinchScale.snapTo(visual) }
                    val delta = when {
                        accumulated > PINCH_OUT_THRESHOLD -> -1
                        accumulated < PINCH_IN_THRESHOLD -> 1
                        else -> 0
                    }
                    if (delta != 0) {
                        accumulated = 1f
                        val focus = if (centroid != Offset.Unspecified) centroid else Offset(size.width / 2f, size.height / 2f)
                        val hit = hitTest(focus)
                        val now = System.currentTimeMillis()
                        val hitItem = hit?.item
                        anchorHolder.anchor = if (hitItem is GridItem.Media) {
                            // Ancrage en coordonnées de contenu, comme LazyGridItemInfo.offset.
                            val contentY = toContentCoordinates(focus, state.layoutInfo.beforeContentPadding).y
                            ZoomAnchor(hitItem.key, contentY, delta, now)
                        } else {
                            state.layoutInfo.visibleItemsInfo
                                .firstOrNull { it.contentType == CONTENT_TYPE_MEDIA }
                                ?.let { ZoomAnchor(it.key, it.offset.y + it.size.height / 2f, delta, now) }
                                ?: ZoomAnchor(null, 0f, delta, now)
                        }
                        currentOnZoomChange(delta)
                        scope.launch { pinchScale.snapTo(1f) }
                    }
                }
            } while (event.changes.any { it.pressed })
            if (pinching) scope.launch { pinchScale.animateTo(1f, tween(180)) }
        }
    }

    val pressModifier = Modifier.pointerInput(state) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            val wasScrolling = state.isScrollInProgress
            val slop = viewConfiguration.touchSlop
            var tapped = false
            var cancelled = false
            val completed = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    if (event.changes.count { it.pressed } > 1) {
                        cancelled = true
                        break
                    }
                    val change = event.changes.firstOrNull { it.id == down.id }
                    if (change == null) {
                        cancelled = true
                        break
                    }
                    if (!change.pressed) {
                        tapped = !change.isConsumed
                        break
                    }
                    if (change.isConsumed || (change.position - down.position).getDistance() > slop) {
                        cancelled = true
                        break
                    }
                }
                true
            }
            if (completed != null) {
                if (tapped && !cancelled && !wasScrolling) {
                    val hit = hitTest(down.position) ?: return@awaitEachGesture
                    when (val item = hit.item) {
                        is GridItem.Media -> currentOnItemClick(item)
                        // Les identifiants du jour sont lus en base (y compris les éléments non chargés).
                        is GridItem.Header -> if (currentSelectionMode && item.grouping == GridGrouping.DAY) {
                            currentOnHeaderToggle(item)
                        }
                        null -> Unit
                    }
                }
                return@awaitEachGesture
            }

            // Appui long : sélection puis glisser pour étendre la plage.
            val hit = hitTest(down.position) ?: return@awaitEachGesture
            val anchorItem = hit.item as? GridItem.Media ?: return@awaitEachGesture
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            val baseline: Set<Long> = if (currentSelectionMode) currentSelection else emptySet()
            val selectMode = anchorItem.media.id !in baseline
            currentOnItemLongClick(anchorItem)

            // L'ancre est mémorisée par clé : une invalidation de la pagination pendant le geste
            // (indexation, synchro MediaStore) décale les index, pas les clés.
            val anchorKey = anchorItem.key
            var anchorIndex = hit.pagingIndex
            var currentIndex = anchorIndex
            var endMedia = anchorItem.media
            var range: Set<Long> = setOf(anchorItem.media.id)
            var lastPosition = down.position
            var autoScrollSpeed = 0f
            var autoScrollJob: Job? = null

            /** Index courant de l'ancre, ou -1 si elle n'est plus chargée. */
            fun resolveAnchorIndex(): Int {
                val paging = currentItems
                if (anchorIndex in 0 until paging.itemCount && paging.peek(anchorIndex)?.key == anchorKey) return anchorIndex
                val layout = state.layoutInfo
                val leading = layout.totalItemsCount - paging.itemCount
                layout.visibleItemsInfo.firstOrNull { it.key == anchorKey }?.let { return it.index - leading }
                return paging.itemSnapshotList.indexOfFirst { it?.key == anchorKey }
            }

            fun updateRange(position: Offset) {
                val target = hitTest(position) ?: return
                val resolvedAnchor = resolveAnchorIndex()
                // Ancre introuvable (supprimée, déchargée) : on garde la plage telle quelle.
                if (resolvedAnchor < 0) return
                if (target.pagingIndex == currentIndex && resolvedAnchor == anchorIndex) return
                anchorIndex = resolvedAnchor
                currentIndex = target.pagingIndex
                (target.item as? GridItem.Media)?.let { endMedia = it.media }
                val newRange = mediaIdsBetween(anchorIndex, currentIndex)
                val added = newRange - range
                val removed = range - newRange
                if (added.isNotEmpty()) currentOnDragSelect(added, selectMode)
                if (removed.isNotEmpty()) {
                    // Les éléments sortis de la plage retrouvent leur état d'avant le glisser.
                    val restoreSelected = removed.filterTo(HashSet()) { it in baseline }
                    val restoreUnselected = removed - restoreSelected
                    if (restoreSelected.isNotEmpty()) currentOnDragSelect(restoreSelected, true)
                    if (restoreUnselected.isNotEmpty()) currentOnDragSelect(restoreUnselected, false)
                }
                range = newRange
            }

            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) {
                        change.consume()
                        break
                    }
                    change.consume()
                    lastPosition = change.position
                    updateRange(lastPosition)

                    val viewportHeight = size.height.toFloat()
                    val topZone = currentTopEdge + autoScrollZonePx
                    val bottomZone = viewportHeight - currentBottomEdge - autoScrollZonePx
                    autoScrollSpeed = when {
                        lastPosition.y < topZone ->
                            -maxAutoScrollPx * ((topZone - lastPosition.y) / autoScrollZonePx).coerceIn(0.1f, 1f)
                        lastPosition.y > bottomZone ->
                            maxAutoScrollPx * ((lastPosition.y - bottomZone) / autoScrollZonePx).coerceIn(0.1f, 1f)
                        else -> 0f
                    }
                    if (autoScrollSpeed != 0f && autoScrollJob?.isActive != true) {
                        autoScrollJob = scope.launch {
                            while (isActive && autoScrollSpeed != 0f) {
                                withFrameNanos { }
                                val consumed = state.scrollBy(autoScrollSpeed)
                                if (consumed == 0f) break
                                updateRange(lastPosition)
                            }
                        }
                    }
                }
            } finally {
                autoScrollSpeed = 0f
                autoScrollJob?.cancel()
                // Résolution finale en base : inclut les éléments non chargés pendant un défilement rapide.
                if (endMedia.id != anchorItem.media.id) currentOnDragSelectFinished(anchorItem.media, endMedia, selectMode)
            }
        }
    }

    val configuration = LocalConfiguration.current
    val locale = remember(configuration) { configuration.locales[0] }
    // « Aujourd'hui » / « Hier » : la date est relue au retour au premier plan et à chaque
    // changement de date, d'heure ou de fuseau (l'appli peut rester ouverte au passage de minuit).
    var today by remember { mutableStateOf(LocalDate.now()) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { today = LocalDate.now() }
    val context = LocalContext.current
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                today = LocalDate.now()
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_DATE_CHANGED)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { context.unregisterReceiver(receiver) }
    }
    val todayLabel = stringResource(R.string.common_today)
    val yesterdayLabel = stringResource(R.string.common_yesterday)
    val videoLabel = stringResource(R.string.common_video)
    val imageLabel = stringResource(R.string.common_image)

    // Lambdas stables pour l'accessibilité (les gestes réels passent par pointerInput).
    val a11yClick = remember { { item: GridItem.Media -> currentOnItemClick(item) } }
    val a11yLongClick = remember { { item: GridItem.Media -> currentOnItemLongClick(item) } }

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalArrangement = Arrangement.spacedBy(spacing),
        modifier = modifier
            .graphicsLayer {
                val s = pinchScale.value * stepScale.value
                scaleX = s
                scaleY = s
                alpha = stepAlpha.value
                val p = pivot
                transformOrigin = if (p != Offset.Unspecified && size.width > 0f && size.height > 0f) {
                    TransformOrigin(
                        (p.x / size.width).coerceIn(0f, 1f),
                        (p.y / size.height).coerceIn(0f, 1f),
                    )
                } else {
                    TransformOrigin.Center
                }
            }
            .then(pinchModifier)
            .then(pressModifier),
    ) {
        header?.invoke(this)
        items(
            count = items.itemCount,
            key = items.itemKey { it.key },
            span = { index ->
                if (items.peek(index) is GridItem.Header) GridItemSpan(maxLineSpan) else GridItemSpan(1)
            },
            contentType = items.itemContentType { if (it is GridItem.Header) CONTENT_TYPE_HEADER else CONTENT_TYPE_MEDIA },
        ) { index ->
            when (val item = items[index]) {
                null -> Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .background(OneUiTheme.colors.placeholder),
                )

                is GridItem.Header -> {
                    val label = remember(item, today, locale, todayLabel, yesterdayLabel) {
                        when (item.grouping) {
                            GridGrouping.DAY -> Formatters.dayHeader(
                                date = item.date,
                                today = today,
                                locale = locale,
                                todayLabel = todayLabel,
                                yesterdayLabel = yesterdayLabel,
                            )

                            GridGrouping.MONTH -> Formatters.monthHeader(item.yearMonth, today, locale)
                        }
                    }
                    val showCheck = selectionMode && item.grouping == GridGrouping.DAY
                    val checked = showCheck && isDayFullySelected(
                        resolvedIds = dayIds[item.epochDay],
                        loaded = loadedDayMediaIds(items, index),
                        selection = selection,
                    )
                    GridHeaderCell(label = label, showCheck = showCheck, checked = checked)
                }

                is GridItem.Media -> MediaCell(
                    item = item,
                    selected = item.media.id in selection,
                    selectionMode = selectionMode,
                    showVideoDuration = showVideoDuration,
                    typeLabel = if (item.media.type == MediaType.VIDEO) videoLabel else imageLabel,
                    onA11yClick = a11yClick,
                    onA11yLongClick = a11yLongClick,
                )
            }
        }
    }
}

/** Médias chargés d'une période ; [complete] = faux si la suite de la période n'est pas encore chargée. */
internal class LoadedDay(val ids: List<Long>, val complete: Boolean)

/** Identifiants des médias chargés appartenant à la période de l'en-tête [headerIndex]. */
internal fun loadedDayMediaIds(items: LazyPagingItems<GridItem>, headerIndex: Int): LoadedDay {
    val result = ArrayList<Long>()
    var i = headerIndex + 1
    while (i < items.itemCount) {
        when (val item = items.peek(i)) {
            is GridItem.Media -> result += item.media.id
            is GridItem.Header -> return LoadedDay(result, complete = true)
            null -> return LoadedDay(result, complete = false)
        }
        i++
    }
    // Fin des éléments chargés : la période n'est complète que si toute la liste l'est.
    return LoadedDay(result, complete = items.loadState.append.endOfPaginationReached)
}

/**
 * Case d'un en-tête de jour : cochée seulement si TOUS les médias du jour sont sélectionnés.
 * Liste résolue en base si disponible, sinon les éléments chargés à condition qu'ils couvrent
 * tout le jour (jamais « cochée » sur une liste tronquée).
 */
internal fun isDayFullySelected(resolvedIds: List<Long>?, loaded: LoadedDay, selection: Set<Long>): Boolean {
    if (resolvedIds != null) return resolvedIds.isNotEmpty() && resolvedIds.all { it in selection }
    return loaded.complete && loaded.ids.isNotEmpty() && loaded.ids.all { it in selection }
}

/** Position du pointeur (repère du conteneur) dans le repère du contenu de la grille. */
internal fun toContentCoordinates(position: Offset, beforeContentPadding: Int): Offset =
    Offset(position.x, position.y - beforeContentPadding)

@Composable
private fun GridHeaderCell(label: String, showCheck: Boolean, checked: Boolean) {
    val colors = OneUiTheme.colors
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)
            .semantics(mergeDescendants = true) {
                heading()
                if (showCheck) selected = checked
            },
    ) {
        if (showCheck) {
            HeaderCheck(checked)
            Spacer(Modifier.width(12.dp))
        }
        Text(text = label, style = OneUiTheme.typography.sectionHeader, color = colors.textPrimary)
    }
}

/** Case ronde d'en-tête : contour gris (la case des miniatures a un contour blanc, invisible ici). */
@Composable
private fun HeaderCheck(checked: Boolean) {
    val colors = OneUiTheme.colors
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(if (checked) colors.accent else Color.Transparent)
            .then(if (checked) Modifier else Modifier.border(1.5.dp, colors.textTertiary, CircleShape)),
    ) {
        if (checked) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
    }
}

private val BadgeTextShadow = Shadow(color = Color(0x99000000), offset = Offset(0f, 1f), blurRadius = 4f)

@Composable
private fun MediaCell(
    item: GridItem.Media,
    selected: Boolean,
    selectionMode: Boolean,
    showVideoDuration: Boolean,
    typeLabel: String,
    onA11yClick: (GridItem.Media) -> Unit,
    onA11yLongClick: (GridItem.Media) -> Unit,
) {
    val media = item.media
    val colors = OneUiTheme.colors
    val scale by animateFloatAsState(if (selected) 0.88f else 1f, label = "cellScale")
    val corner by animateDpAsState(if (selected) 10.dp else 0.dp, label = "cellCorner")
    val isVideo = media.type == MediaType.VIDEO
    val duration = remember(media.durationMs) { Formatters.duration(media.durationMs) }
    val description = if (isVideo) "$typeLabel, $duration" else typeLabel

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .semantics {
                contentDescription = description
                if (selectionMode) this.selected = selected
                onClick { onA11yClick(item); true }
                onLongClick { onA11yLongClick(item); true }
            },
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    shape = RoundedCornerShape(corner)
                    clip = corner > 0.dp
                },
        ) {
            ThumbnailImage(
                uri = media.uri,
                isVideo = isVideo,
                version = media.dateModified,
                modifier = Modifier.fillMaxSize(),
            )
            if (isVideo) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 5.dp, bottom = 4.dp),
                ) {
                    if (showVideoDuration) {
                        Text(
                            text = duration,
                            style = OneUiTheme.typography.badge.copy(shadow = BadgeTextShadow),
                            color = Color.White,
                        )
                        Spacer(Modifier.width(2.dp))
                    }
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                }
            }
            if (media.hasCloud && !media.hasLocal) {
                CornerBadge(Icons.Filled.Cloud, Modifier.align(Alignment.TopEnd))
            } else if (media.hasOffline) {
                CornerBadge(Icons.Filled.OfflinePin, Modifier.align(Alignment.TopEnd))
            }
        }
        if (selectionMode) {
            OneUiCheckCircle(
                checked = selected,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp),
            )
        } else if (selected) {
            Box(Modifier.fillMaxSize().background(colors.selectionOverlay))
        }
    }
}

@Composable
private fun CornerBadge(icon: ImageVector, modifier: Modifier) {
    Icon(
        imageVector = icon,
        contentDescription = null,
        tint = Color.White,
        modifier = modifier
            .padding(5.dp)
            .size(15.dp),
    )
}
