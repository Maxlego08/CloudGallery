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
        // Après une recréation, l'intent a déjà été traité (la visionneuse est dans la pile).
        if (savedInstanceState == null) handleIntent(intent, coldStart = true)

        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val darkTheme = isAppInDarkTheme(settings.themeMode)
            // Barres système transparentes ; icônes claires/sombres selon le thème de l'appli
            // (et non celui du système quand l'utilisateur a forcé Clair ou Sombre).
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
     * Intent VIEW (ouverture d'une photo/vidéo depuis une autre appli) -> visionneuse. Le retour
     * rend la main à l'appelant quand l'activité a été lancée pour cet intent, ou quand elle vit
     * dans la tâche d'une autre appli (elle n'en est pas la racine).
     */
    private fun handleIntent(intent: Intent?, coldStart: Boolean) {
        if (intent?.action != Intent.ACTION_VIEW) return
        val uri = intent.data ?: return
        viewModel.handleViewIntent(uri, intent.type, exitOnBack = coldStart || !isTaskRoot)
    }
}
