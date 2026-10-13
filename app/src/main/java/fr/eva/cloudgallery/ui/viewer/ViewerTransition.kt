package fr.eva.cloudgallery.ui.viewer

import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Transition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.util.lerp
import fr.eva.cloudgallery.domain.model.MediaSummary
import kotlin.math.max
import kotlin.math.min

/*
 * Samsung Gallery-style grid ↔ viewer transition (measured on Gallery 15.8, One UI 8):
 * on open, the grid disappears immediately (black background) and the photo grows from its
 * thumbnail, cropped to a square, up to its fitted size; on return, bars hidden and transparent
 * background immediately, the photo shrinks to its thumbnail over the grid (curves measured below).
 */

/** Curve and duration measured on Samsung Gallery (opening a photo). */
internal val ViewerZoomEasing = CubicBezierEasing(0.6f, 0.4f, 0.45f, 1f)
internal const val VIEWER_ZOOM_MS = 250

/**
 * Return measured on Samsung Gallery: much snappier at the start (the photo loses half its size
 * in ~50 ms) then a long deceleration down to the thumbnail.
 */
internal val ViewerCloseEasing = CubicBezierEasing(0.15f, 0.4f, 0.1f, 1f)
internal const val VIEWER_CLOSE_MS = 280

/**
 * Displayed thumbnails, by media id: on-screen position (window, px) and grid media (thumbnail
 * already cached, dimensions), the starting point of the open and end point of the return.
 * [hiddenId]: thumbnail hidden while the photo flies back to it (no duplicate mid-flight).
 */
@Stable
class MediaBoundsRegistry {
    data class Entry(val rect: Rect, val media: MediaSummary)

    private val entries = mutableStateMapOf<Long, Entry>()

    var hiddenId by mutableStateOf<Long?>(null)

    fun update(media: MediaSummary, rect: Rect) {
        val entry = Entry(rect, media)
        if (entries[media.id] != entry) entries[media.id] = entry
    }

    fun remove(id: Long) {
        entries.remove(id)
    }

    fun entryOf(id: Long): Entry? = entries[id]
}

val LocalMediaBounds = staticCompositionLocalOf<MediaBoundsRegistry?> { null }

/**
 * Progress of the viewer transition: [progress] 0 (thumbnail) → 1 (fullscreen);
 * [closing] true during the return to the grid.
 */
@Stable
class ViewerTransition internal constructor(
    private val progressState: State<Float>,
    private val closingState: State<Boolean>,
) {
    val progress: Float get() = progressState.value
    val closing: Boolean get() = closingState.value

    companion object {
        /** No transition (previews, tests): fullscreen. */
        val Static = ViewerTransition(mutableFloatStateOf(1f), mutableStateOf(false))
    }
}

/**
 * Return: driven by the NavHost transition (button, predictive gesture following the finger).
 * Open: own animation started after the first frame is drawn; composing the viewer takes several
 * tens of ms and the NavHost clock would have started the photo already half-enlarged.
 * [stayFullscreen]: exit to another fullscreen screen placed on top (video player); the photo
 * stays in place instead of going back to the grid.
 */
@Composable
fun rememberViewerTransition(transition: Transition<EnterExitState>, stayFullscreen: Boolean): ViewerTransition {
    val stay by rememberUpdatedState(stayFullscreen)
    val exitProgress = transition.animateFloat(
        transitionSpec = { tween(VIEWER_CLOSE_MS, easing = ViewerCloseEasing) },
        label = "viewerZoom",
    ) { state ->
        when (state) {
            EnterExitState.PostExit -> if (stay) 1f else 0f
            else -> 1f
        }
    }
    val openProgress = remember { Animatable(if (transition.currentState == EnterExitState.Visible) 1f else 0f) }
    LaunchedEffect(openProgress) {
        if (openProgress.value < 1f) {
            withFrameNanos { }
            withFrameNanos { }
            openProgress.animateTo(1f, tween(VIEWER_ZOOM_MS, easing = ViewerZoomEasing))
        }
    }
    val closing = remember(transition) { derivedStateOf { transition.targetState == EnterExitState.PostExit && !stay } }
    val progress = remember(transition) {
        derivedStateOf { if (closing.value) exitProgress.value else openProgress.value }
    }
    return remember(progress, closing) { ViewerTransition(progress, closing) }
}

/** Transform of the viewer area at a given point of the transition (px, viewer coordinate space). */
internal data class ZoomFrame(val scale: Float, val translation: Offset, val clip: Rect)

internal object ViewerZoomGeometry {

    /** Rectangle of the image fitted (Fit) and centered in the container; null without dimensions. */
    fun fittedRect(container: Size, imageW: Int, imageH: Int): Rect? {
        if (imageW <= 0 || imageH <= 0 || container.width <= 0f || container.height <= 0f) return null
        val k = min(container.width / imageW, container.height / imageH)
        val size = Size(imageW * k, imageH * k)
        return Rect(Offset((container.width - size.width) / 2f, (container.height - size.height) / 2f), size)
    }

    /**
     * At [progress] 0, the image covers the thumbnail [cell] (center crop); at 1, it fills
     * [fitted]. Scale and center are interpolated, clip interpolated from the thumbnail to the
     * fitted rectangle. The scale pivot is the container's center (center of [fitted]).
     */
    fun frame(cell: Rect, fitted: Rect, progress: Float): ZoomFrame {
        val p = progress.coerceIn(0f, 1f)
        val cover = max(cell.width / fitted.width, cell.height / fitted.height)
        val scale = lerp(cover, 1f, p)
        val center = lerp(cell.center, fitted.center, p)
        return ZoomFrame(scale, center - fitted.center, lerp(cell, fitted, p))
    }
}
