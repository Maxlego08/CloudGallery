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
 * Propriétaire unique de l'apparence des barres système, placé au niveau de la navigation.
 *
 * Navigation Compose compose la destination entrante avant de retirer la sortante : un
 * enregistrement / restauration par écran capturerait l'état de l'autre écran. Ici l'apparence
 * découle uniquement de la destination courante et du thème de l'appli :
 * - médias plein écran (visionneuse, lecteur) : icônes claires sur fond noir, barres masquables ;
 * - ailleurs : barres visibles, icônes selon le thème.
 *
 * Les écrans plein écran ne gèrent que la visibilité ([ImmersiveSystemBars]).
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
 * Visibilité des barres système d'un écran plein écran. Réappliquée à chaque reprise : un écran
 * qui redevient le premier de la pile (retour depuis le lecteur) rétablit son propre état.
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
 * Propriétés d'un panneau ouvert au-dessus d'un média plein écran : la fenêtre du panneau
 * pilote les barres système, la barre d'état surplombe le fond noir (icônes claires) tandis que
 * la barre de navigation surplombe le panneau (icônes selon le thème).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun fullscreenSheetProperties(): ModalBottomSheetProperties = ModalBottomSheetProperties(
    isAppearanceLightStatusBars = false,
    isAppearanceLightNavigationBars = !OneUiTheme.isDark,
)
