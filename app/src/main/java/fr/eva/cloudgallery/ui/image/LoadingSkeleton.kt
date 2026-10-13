package fr.eva.cloudgallery.ui.image

import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.ui.theme.OneUiTheme

/** Durée d'un passage de la bande lumineuse sur toute la largeur de l'écran. */
private const val SHIMMER_PERIOD_MS = 1_400L

/** Inclinaison de la bande (décalage horizontal par pixel vertical). */
private const val SHIMMER_SLANT = 0.35f

/**
 * Fond d'attente animé (« skeleton ») d'une image en cours de chargement : couleur d'attente et
 * bande lumineuse oblique qui balaie l'écran. La phase vient de l'horloge des images et de la
 * position de la cellule dans la fenêtre : toutes les cellules en attente forment une seule bande
 * continue, au lieu de clignoter chacune de son côté. Seul le dessin est invalidé (aucune
 * recomposition) ; l'animation s'arrête quand l'appelant retire ce composant. Animations
 * désactivées dans les options d'accessibilité / développeur : fond fixe.
 */
@Composable
internal fun LoadingSkeleton(modifier: Modifier = Modifier) {
    val colors = OneUiTheme.colors
    val base = colors.placeholder
    val highlight = if (OneUiTheme.isDark) Color.White.copy(alpha = 0.08f) else Color.White.copy(alpha = 0.6f)
    val context = LocalContext.current
    val animate = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }
    val bandPx = with(LocalDensity.current) { 220.dp.toPx() }
    val windowWidth = LocalWindowInfo.current.containerSize.width.toFloat()

    var frameMs by remember { mutableLongStateOf(0L) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    if (animate) {
        LaunchedEffect(Unit) {
            while (true) withFrameMillis { frameMs = it }
        }
    }

    Box(
        modifier
            .onGloballyPositioned { origin = it.positionInWindow() }
            .drawBehind {
                drawRect(base)
                if (!animate) return@drawBehind
                // Coordonnée oblique s = x + SLANT·y (repère de la fenêtre) ; le centre de la bande
                // parcourt [-bande, largeur + bande] à chaque période.
                val phase = (frameMs % SHIMMER_PERIOD_MS) / SHIMMER_PERIOD_MS.toFloat()
                val center = -bandPx + phase * (windowWidth + 2 * bandPx)
                // Point local d'ordonnée 0 où s = centre - bande/2, puis direction (1, SLANT).
                val startX = center - bandPx / 2f - origin.x - SHIMMER_SLANT * origin.y
                val length = bandPx / (1f + SHIMMER_SLANT * SHIMMER_SLANT)
                drawRect(
                    Brush.linearGradient(
                        colors = listOf(Color.Transparent, highlight, Color.Transparent),
                        start = Offset(startX, 0f),
                        end = Offset(startX + length, SHIMMER_SLANT * length),
                    ),
                )
            },
    )
}
