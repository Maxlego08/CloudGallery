package fr.eva.cloudgallery.ui.viewer

import android.net.Uri
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateCentroidSize
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.ui.image.ThumbnailImage
import fr.eva.cloudgallery.ui.image.fullImageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

/**
 * Etat de zoom/translation d'une page de la visionneuse. Toutes les valeurs sont en pixels.
 * Les animations (double tap, inertie, retour du glisser vertical) tournent dans [scope].
 */
@Stable
internal class ZoomState(private val scope: CoroutineScope) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offsetX by mutableFloatStateOf(0f)
        private set
    var offsetY by mutableFloatStateOf(0f)
        private set

    /** Translation verticale du geste « glisser vers le bas pour fermer / vers le haut pour les détails ». */
    var dragY by mutableFloatStateOf(0f)
        private set

    var containerW by mutableFloatStateOf(0f)
    var containerH by mutableFloatStateOf(0f)
    var contentW by mutableFloatStateOf(0f)
    var contentH by mutableFloatStateOf(0f)

    private var job: Job? = null

    val isZoomed: Boolean get() = scale > 1.01f

    val fitted: Pair<Float, Float> get() = ZoomMath.fitSize(contentW, contentH, containerW, containerH)

    private fun clampX(x: Float, s: Float) = ZoomMath.clampOffset(x, fitted.first, containerW, s)
    private fun clampY(y: Float, s: Float) = ZoomMath.clampOffset(y, fitted.second, containerH, s)

    fun stop() {
        job?.cancel()
        job = null
    }

    fun reset() {
        stop()
        scale = 1f
        offsetX = 0f
        offsetY = 0f
        dragY = 0f
    }

    /** Pincement : [focalX]/[focalY] relatifs au centre du conteneur. */
    fun transform(zoom: Float, panX: Float, panY: Float, focalX: Float, focalY: Float) {
        val newScale = ZoomMath.clampScale(scale * zoom)
        val ox = ZoomMath.zoomAround(offsetX, scale, newScale, focalX) + panX
        val oy = ZoomMath.zoomAround(offsetY, scale, newScale, focalY) + panY
        scale = newScale
        offsetX = clampX(ox, newScale)
        offsetY = clampY(oy, newScale)
    }

    /** Déplacement de l'image zoomée. Renvoie la part horizontale non absorbée (bord atteint). */
    fun pan(dx: Float, dy: Float): Float {
        val targetX = offsetX + dx
        val clampedX = clampX(targetX, scale)
        offsetX = clampedX
        offsetY = clampY(offsetY + dy, scale)
        return targetX - clampedX
    }

    fun dragVertical(dy: Float) {
        // Résistance vers le haut : le geste ne sert qu'à ouvrir le panneau Détails.
        dragY += if (dragY + dy < 0f) dy * 0.4f else dy
    }

    /** Double tap : 1x <-> 2,5x autour du point touché ([x], [y] dans le repère du conteneur). */
    fun animateDoubleTap(x: Float, y: Float) {
        stop()
        val s0 = scale
        val ox0 = offsetX
        val oy0 = offsetY
        val target = if (isZoomed) ZoomMath.MIN_SCALE else ZoomMath.DOUBLE_TAP_SCALE
        val fx = x - containerW / 2f
        val fy = y - containerH / 2f
        job = scope.launch {
            animate(
                initialValue = s0,
                targetValue = target,
                animationSpec = tween(durationMillis = 280, easing = FastOutSlowInEasing),
            ) { s, _ ->
                offsetX = clampX(ZoomMath.zoomAround(ox0, s0, s, fx), s)
                offsetY = clampY(ZoomMath.zoomAround(oy0, s0, s, fy), s)
                scale = s
            }
        }
    }

    /** Inertie après un déplacement de l'image zoomée, bornée aux bords. */
    fun fling(vx: Float, vy: Float, decay: DecayAnimationSpec<Offset>) {
        if (!isZoomed) return
        stop()
        job = scope.launch {
            AnimationState(Offset.VectorConverter, Offset(offsetX, offsetY), Offset(vx, vy))
                .animateDecay(decay) {
                    val cx = clampX(value.x, scale)
                    val cy = clampY(value.y, scale)
                    offsetX = cx
                    offsetY = cy
                    if (cx != value.x && cy != value.y) cancelAnimation()
                }
        }
    }

    fun animateDragBack() {
        stop()
        val d0 = dragY
        if (d0 == 0f) return
        job = scope.launch {
            animate(initialValue = d0, targetValue = 0f, animationSpec = spring()) { v, _ -> dragY = v }
        }
    }
}

/** Rappels des gestes de page vers l'écran (pager, chrome, fermeture). */
internal class PageGestureCallbacks(
    val onTap: () -> Unit,
    val onDismiss: () -> Unit,
    val onSwipeUp: () -> Unit,
    /** Progression 0..1 du glisser vers le bas (fond et chrome s'estompent). */
    val onDragProgress: (Float) -> Unit,
    /** Déplacement horizontal (px, sens du doigt) à transmettre au pager quand l'image zoomée est au bord. */
    val onPagerDrag: (Float) -> Unit,
    /** Fin d'un geste ayant entraîné le pager : vitesse horizontale du doigt en px/s. */
    val onPagerDragEnd: (Float) -> Unit,
)

private enum class GestureMode { Undecided, Transform, Pan, Vertical, Ignore }

/**
 * Conteneur de page : gère tap / double tap, pincement, déplacement avec inertie, transfert au pager
 * au bord de l'image et glisser vertical. [content] est dessiné dans la zone ajustée (Fit) et suit le zoom.
 * À 1x, les glissements horizontaux ne sont pas consommés : le pager les reçoit normalement.
 */
@Composable
internal fun ZoomableBox(
    state: ZoomState,
    zoomEnabled: Boolean,
    callbacks: PageGestureCallbacks,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val density = LocalDensity.current
    val decay = rememberSplineBasedDecay<Offset>()
    val current by rememberUpdatedState(callbacks)
    val dismissThreshold = with(density) { 120.dp.toPx() }
    val swipeUpThreshold = with(density) { 64.dp.toPx() }
    val flingThreshold = with(density) { 1000.dp.toPx() }

    LaunchedEffect(state) {
        snapshotFlow { if (state.containerH > 0f) (state.dragY / state.containerH).coerceIn(0f, 1f) else 0f }
            .collect { current.onDragProgress(it) }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged {
                state.containerW = it.width.toFloat()
                state.containerH = it.height.toFloat()
            }
            .pointerInput(state, zoomEnabled) {
                detectTapGestures(
                    onTap = { current.onTap() },
                    onDoubleTap = if (zoomEnabled) {
                        { pos: Offset -> state.animateDoubleTap(pos.x, pos.y) }
                    } else {
                        null
                    },
                )
            }
            .pointerInput(state, zoomEnabled) {
                detectViewerGestures(
                    state = state,
                    zoomEnabled = zoomEnabled,
                    callbacks = { current },
                    decay = decay,
                    dismissThreshold = dismissThreshold,
                    swipeUpThreshold = swipeUpThreshold,
                    flingThreshold = flingThreshold,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        val (fw, fh) = state.fitted
        Box(
            modifier = Modifier
                .size(with(density) { fw.toDp() }, with(density) { fh.toDp() })
                .graphicsLayer {
                    val drag = state.dragY
                    val progress = if (state.containerH > 0f) (abs(drag) / state.containerH).coerceIn(0f, 1f) else 0f
                    val dismissScale = if (drag > 0f) 1f - 0.35f * progress else 1f
                    scaleX = state.scale * dismissScale
                    scaleY = state.scale * dismissScale
                    translationX = state.offsetX
                    translationY = state.offsetY + drag
                },
            content = content,
        )
    }
}

private suspend fun PointerInputScope.detectViewerGestures(
    state: ZoomState,
    zoomEnabled: Boolean,
    callbacks: () -> PageGestureCallbacks,
    decay: DecayAnimationSpec<Offset>,
    dismissThreshold: Float,
    swipeUpThreshold: Float,
    flingThreshold: Float,
) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        state.stop()
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        val slop = viewConfiguration.touchSlop
        var mode = GestureMode.Undecided
        var panAcc = Offset.Zero
        var zoomAcc = 1f
        var pagerAccum = 0f

        while (true) {
            val event = awaitPointerEvent()
            val pressedCount = event.changes.count { it.pressed }
            if (pressedCount == 0) break
            if (mode == GestureMode.Undecided && event.changes.any { it.isConsumed }) mode = GestureMode.Ignore

            val zoom = event.calculateZoom()
            val pan = event.calculatePan()
            event.changes.firstOrNull { it.id == down.id && it.pressed }?.let { tracker.addPosition(it.uptimeMillis, it.position) }

            if (mode == GestureMode.Undecided) {
                zoomAcc *= zoom
                panAcc += pan
                val zoomMotion = abs(1f - zoomAcc) * event.calculateCentroidSize(useCurrent = false)
                if (zoomEnabled && pressedCount >= 2 && zoomMotion > slop) {
                    mode = GestureMode.Transform
                } else if (panAcc.getDistance() > slop) {
                    mode = when {
                        zoomEnabled && pressedCount >= 2 -> GestureMode.Transform
                        state.isZoomed -> GestureMode.Pan
                        abs(panAcc.y) > abs(panAcc.x) -> GestureMode.Vertical
                        else -> GestureMode.Ignore
                    }
                }
            }
            if (mode == GestureMode.Pan && zoomEnabled && pressedCount >= 2 && pagerAccum == 0f) {
                mode = GestureMode.Transform
            }

            when (mode) {
                GestureMode.Transform -> {
                    val centroid = event.calculateCentroid(useCurrent = true)
                    if (centroid.isSpecified) {
                        state.transform(
                            zoom = zoom,
                            panX = pan.x,
                            panY = pan.y,
                            focalX = centroid.x - state.containerW / 2f,
                            focalY = centroid.y - state.containerH / 2f,
                        )
                    }
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                GestureMode.Pan -> {
                    val (toPager, forImage, accum) = ZoomMath.splitPagerDelta(pan.x, pagerAccum)
                    pagerAccum = accum
                    var pagerDx = toPager
                    val overflow = state.pan(forImage, if (pagerAccum == 0f) pan.y else 0f)
                    if (overflow != 0f) {
                        pagerDx += overflow
                        pagerAccum += overflow
                    }
                    if (pagerDx != 0f) callbacks().onPagerDrag(pagerDx)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                GestureMode.Vertical -> {
                    state.dragVertical(pan.y)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
                GestureMode.Undecided, GestureMode.Ignore -> Unit
            }
        }

        val velocity = tracker.calculateVelocity()
        when (mode) {
            GestureMode.Pan, GestureMode.Transform -> {
                if (pagerAccum != 0f) {
                    callbacks().onPagerDragEnd(velocity.x)
                } else {
                    state.fling(velocity.x, velocity.y, decay)
                }
            }
            GestureMode.Vertical -> {
                val dy = state.dragY
                when {
                    dy > dismissThreshold || (dy > 0f && velocity.y > flingThreshold) -> callbacks().onDismiss()
                    dy < -swipeUpThreshold * 0.4f || (dy < 0f && velocity.y < -flingThreshold) -> {
                        callbacks().onSwipeUp()
                        state.animateDragBack()
                    }
                    else -> state.animateDragBack()
                }
            }
            GestureMode.Undecided, GestureMode.Ignore -> Unit
        }
    }
}

/**
 * Page image zoomable : miniature MediaStore instantanée, puis image bornée à 2 x l'écran (≤ 4096 px),
 * puis rechargement haute résolution (≤ ~24 MP, plafond Coil relevé en conséquence) au-delà de 2,5x.
 *
 * TODO(viewer): décodage par tuiles (BitmapRegionDecoder) pour afficher les très grandes images
 *  (> 24 MP, panoramas) à pleine résolution sans risque d'OOM.
 */
@Composable
internal fun ZoomableImage(
    media: MediaDetails,
    uri: Uri,
    isCurrentPage: Boolean,
    callbacks: PageGestureCallbacks,
    onZoomChanged: (Boolean) -> Unit,
    onLoadError: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state = remember { ZoomState(scope) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val version = media.dateModified
    val currentOnZoomChanged by rememberUpdatedState(onZoomChanged)

    val baseSide = remember(configuration.screenWidthDp, configuration.screenHeightDp, density.density) {
        val longDp = max(configuration.screenWidthDp, configuration.screenHeightDp)
        ZoomMath.baseMaxSide((longDp * density.density).toInt())
    }
    val hiResSide = remember(media.width, media.height, baseSide) {
        ZoomMath.hiResMaxSide(media.width, media.height, baseSide)
    }
    val baseRequest = remember(uri, version, baseSide) { fullImageRequest(context, uri, version, baseSide) }

    var loadedSize by remember(uri, version) { mutableStateOf<Size?>(null) }
    var baseLoaded by remember(uri, version) { mutableStateOf(false) }
    var hiResWanted by remember(uri, version) { mutableStateOf(false) }
    var hiResLoaded by remember(uri, version) { mutableStateOf(false) }

    // Taille du contenu : celle de l'image décodée (orientation EXIF appliquée), sinon les métadonnées.
    val size = loadedSize
    val swap = media.orientation % 180 != 0
    val contentW = size?.width ?: (if (swap) media.height else media.width).toFloat()
    val contentH = size?.height ?: (if (swap) media.width else media.height).toFloat()
    SideEffect {
        state.contentW = contentW
        state.contentH = contentH
    }

    LaunchedEffect(isCurrentPage) {
        if (!isCurrentPage) {
            state.reset()
            hiResWanted = false
            hiResLoaded = false
        }
    }
    LaunchedEffect(state, isCurrentPage) {
        if (isCurrentPage) {
            snapshotFlow { state.isZoomed }.collect { currentOnZoomChanged(it) }
        }
    }
    LaunchedEffect(state, isCurrentPage, hiResSide) {
        if (isCurrentPage && hiResSide != null) {
            snapshotFlow { state.scale > ZoomMath.HI_RES_THRESHOLD }.collect { if (it) hiResWanted = true }
        }
    }

    ZoomableBox(state = state, zoomEnabled = true, callbacks = callbacks, modifier = modifier) {
        if (!baseLoaded && !hiResLoaded) {
            ThumbnailImage(
                uri = uri,
                isVideo = false,
                version = version,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit,
            )
        }
        if (!hiResLoaded) {
            AsyncImage(
                model = baseRequest,
                contentDescription = media.displayName,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit,
                onSuccess = { success ->
                    baseLoaded = true
                    onLoadError(false)
                    val intrinsic = success.painter.intrinsicSize
                    if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) loadedSize = intrinsic
                },
                onError = { onLoadError(true) },
            )
        }
        if (hiResWanted && hiResSide != null) {
            val hiResRequest = remember(uri, version, hiResSide) { fullImageRequest(context, uri, version, hiResSide) }
            AsyncImage(
                model = hiResRequest,
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = ContentScale.Fit,
                onSuccess = { hiResLoaded = true },
            )
        }
    }
}
