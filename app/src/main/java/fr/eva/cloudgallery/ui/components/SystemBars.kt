package fr.eva.cloudgallery.ui.components

import androidx.activity.compose.LocalActivity
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import fr.eva.cloudgallery.ui.theme.OneUiTheme

/**
 * Single owner of the system bars' appearance, placed at the navigation level.
 *
 * Navigation Compose composes the incoming destination before removing the outgoing one: a
 * per-screen save / restore would capture the other screen's state. Here the appearance
 * derives solely from the current destination and the app theme:
 * - full-screen media (viewer, player): light icons on a black background, hideable bars;
 * - elsewhere: visible bars, icons following the theme.
 *
 * Full-screen screens only manage visibility ([ImmersiveSystemBars]).
 */
@Composable
fun SystemBarsAppearance(fullscreenMedia: Boolean, darkTheme: Boolean) {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    val controller = remember(activity, view) { WindowCompat.getInsetsController(activity.window, view) }
    DisposableEffect(controller, fullscreenMedia, darkTheme) {
        if (fullscreenMedia) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.isAppearanceLightStatusBars = false
            controller.isAppearanceLightNavigationBars = false
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            controller.isAppearanceLightStatusBars = !darkTheme
            controller.isAppearanceLightNavigationBars = !darkTheme
        }
        onDispose { }
    }
}

/**
 * System bar visibility for a full-screen screen. Reapplied on every resume: a screen
 * that becomes the top of the stack again (back from the player) restores its own state.
 */
@Composable
fun ImmersiveSystemBars(visible: Boolean) {
    val activity = LocalActivity.current ?: return
    val view = LocalView.current
    val controller = remember(activity, view) { WindowCompat.getInsetsController(activity.window, view) }
    LifecycleResumeEffect(controller, visible) {
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onPauseOrDispose { }
    }
}

/**
 * Properties of a panel opened over full-screen media: the panel's window
 * drives the system bars, the status bar sits over the black background (light icons) while
 * the navigation bar sits over the panel (icons following the theme).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun fullscreenSheetProperties(): ModalBottomSheetProperties = ModalBottomSheetProperties(
    isAppearanceLightStatusBars = false,
    isAppearanceLightNavigationBars = !OneUiTheme.isDark,
)
