package fr.eva.cloudgallery

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dagger.hilt.android.AndroidEntryPoint
import fr.eva.cloudgallery.ui.main.MainViewModel
import fr.eva.cloudgallery.ui.navigation.AppNavHost
import fr.eva.cloudgallery.ui.theme.CloudGalleryTheme
import fr.eva.cloudgallery.ui.theme.isAppInDarkTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // After a recreation, the intent has already been handled (the viewer is in the back stack).
        if (savedInstanceState == null) handleIntent(intent, coldStart = true)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val darkTheme = isAppInDarkTheme(settings.themeMode)
            // Transparent system bars; light/dark icons according to the app's theme
            // (and not the system's when the user forced Light or Dark).
            DisposableEffect(darkTheme) {
                val style = if (darkTheme) {
                    SystemBarStyle.dark(Color.TRANSPARENT)
                } else {
                    SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
                }
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                window.isNavigationBarContrastEnforced = false
                onDispose { }
            }
            CloudGalleryTheme(darkTheme = darkTheme) {
                AppNavHost(mainViewModel = viewModel, darkTheme = darkTheme)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent, coldStart = false)
    }

    /**
     * VIEW intent (opening a photo/video from another app) -> viewer. Back hands control
     * back to the caller when the activity was launched for this intent, or when it lives
     * in another app's task (it is not its root).
     */
    private fun handleIntent(intent: Intent?, coldStart: Boolean) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        viewModel.handleViewIntent(uri, intent.type, exitOnBack = coldStart || !isTaskRoot)
    }
}
