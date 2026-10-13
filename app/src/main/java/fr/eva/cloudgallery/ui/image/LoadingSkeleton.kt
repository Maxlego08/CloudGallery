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

/** Duration of one pass of the light band across the full width of the screen. */
private const val SHIMMER_PERIOD_MS = 1_400L

/** Slant of the band (horizontal offset per vertical pixel). */
private const val SHIMMER_SLANT = 0.35f

/**
 * Animated placeholder background ("skeleton") of an image being loaded: placeholder color and
 * an oblique light band sweeping across the screen. The phase comes from the frame clock and the
 * cell's position in the window: all waiting cells form a single continuous band, instead of
 * each one blinking on its own. Only drawing is invalidated (no
 * recomposition); the animation stops when the caller removes this composable. Animations
 * disabled in the accessibility / developer options: static background.
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
                // Oblique coordinate s = x + SLANT·y (window coordinates); the band center
                // travels [-band, width + band] in each period.
                val phase = (frameMs % SHIMMER_PERIOD_MS) / SHIMMER_PERIOD_MS.toFloat()
                val center = -bandPx + phase * (windowWidth + 2 * bandPx)
                // Local point with y = 0 where s = center - band/2, then direction (1, SLANT).
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
