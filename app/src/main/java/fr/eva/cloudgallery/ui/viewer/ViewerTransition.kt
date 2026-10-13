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
 * Transition grille ↔ visionneuse façon Samsung Galerie (mesurée sur Galerie 15.8, One UI 8) :
 * à l'ouverture, la grille disparaît aussitôt (fond noir) et la photo grandit depuis sa vignette,
 * recadrée en carré, jusqu'à sa taille ajustée ; au retour, barres masquées et fond transparent
 * aussitôt, la photo rétrécit jusqu'à sa vignette par-dessus la grille (courbes mesurées ci-dessous).
 */

/** Courbe et durée mesurées sur Samsung Galerie (ouverture d'une photo). */
internal val ViewerZoomEasing = CubicBezierEasing(0.6f, 0.4f, 0.45f, 1f)
internal const val VIEWER_ZOOM_MS = 250

/**
 * Retour mesuré sur Samsung Galerie : bien plus vif au départ (la photo perd la moitié de sa
 * taille en ~50 ms) puis longue décélération jusqu'à la vignette.
 */
internal val ViewerCloseEasing = CubicBezierEasing(0.15f, 0.4f, 0.1f, 1f)
internal const val VIEWER_CLOSE_MS = 280

/**
 * Vignettes affichées, par identifiant de média : position à l'écran (fenêtre, px) et média de la
 * grille (miniature déjà en cache, dimensions), point de départ de l'ouverture et d'arrivée du
 * retour. [hiddenId] : vignette masquée pendant que la photo y revient (pas de doublon en vol).
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
 * Avancée de la transition de la visionneuse : [progress] 0 (vignette) → 1 (plein écran) ;
 * [closing] vrai pendant le retour vers la grille.
 */
@Stable
class ViewerTransition internal constructor(
    private val progressState: State<Float>,
    private val closingState: State<Boolean>,
) {
    val progress: Float get() = progressState.value
    val closing: Boolean get() = closingState.value

    companion object {
        /** Sans transition (aperçus, tests) : plein écran. */
        val Static = ViewerTransition(mutableFloatStateOf(1f), mutableStateOf(false))
    }
}

/**
 * Retour : piloté par la transition de la NavHost (bouton, geste prédictif positionné au doigt).
 * Ouverture : animation propre lancée après la première image dessinée ; la composition de la
 * visionneuse prend plusieurs dizaines de ms et l'horloge de la NavHost aurait fait démarrer la
 * photo déjà à moitié agrandie.
 * [stayFullscreen] : sortie vers un autre écran plein écran posé par-dessus (lecteur vidéo) ; la
 * photo reste en place au lieu de regagner la grille.
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

/** Transformation de la zone de la visionneuse à un instant de la transition (px, repère de la visionneuse). */
internal data class ZoomFrame(val scale: Float, val translation: Offset, val clip: Rect)

internal object ViewerZoomGeometry {

    /** Rectangle de l'image ajustée (Fit) et centrée dans le conteneur ; null sans dimensions. */
    fun fittedRect(container: Size, imageW: Int, imageH: Int): Rect? {
        if (imageW <= 0 || imageH <= 0 || container.width <= 0f || container.height <= 0f) return null
        val k = min(container.width / imageW, container.height / imageH)
        val size = Size(imageW * k, imageH * k)
        return Rect(Offset((container.width - size.width) / 2f, (container.height - size.height) / 2f), size)
    }

    /**
     * À [progress] 0, l'image couvre la vignette [cell] (recadrage centré) ; à 1, elle occupe
     * [fitted]. Échelle et centre interpolés, découpe interpolée de la vignette au rectangle ajusté.
     * Le pivot de l'échelle est le centre du conteneur (centre de [fitted]).
     */
    fun frame(cell: Rect, fitted: Rect, progress: Float): ZoomFrame {
        val p = progress.coerceIn(0f, 1f)
        val cover = max(cell.width / fitted.width, cell.height / fitted.height)
        val scale = lerp(cover, 1f, p)
        val center = lerp(cell.center, fitted.center, p)
        return ZoomFrame(scale, center - fitted.center, lerp(cell, fitted, p))
    }
}
