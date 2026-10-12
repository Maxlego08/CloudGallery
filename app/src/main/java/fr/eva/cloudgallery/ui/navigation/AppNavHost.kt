package fr.eva.cloudgallery.ui.navigation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.dropUnlessResumed
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.ui.account.AccountScreen
import fr.eva.cloudgallery.domain.model.MediaContext
import androidx.core.net.toUri
import fr.eva.cloudgallery.ui.collection.MediaCollectionScreen
import fr.eva.cloudgallery.ui.components.SystemBarsAppearance
import fr.eva.cloudgallery.ui.main.MainEvent
import fr.eva.cloudgallery.ui.main.MainScreen
import fr.eva.cloudgallery.ui.main.MainViewModel
import fr.eva.cloudgallery.ui.main.mainScreenMode
import fr.eva.cloudgallery.ui.permissions.rememberMediaPermissionController
import fr.eva.cloudgallery.ui.settings.SettingsScreen
import fr.eva.cloudgallery.ui.saver.SpaceSaverScreen
import fr.eva.cloudgallery.ui.storage.StorageScreen
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import fr.eva.cloudgallery.ui.video.VideoPlayerScreen
import fr.eva.cloudgallery.ui.viewer.ExternalImageScreen
import fr.eva.cloudgallery.ui.viewer.ViewerScreen

/** Courbe « emphasized » proche des transitions One UI. */
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val PUSH_MS = 300
private const val MEDIA_MS = 250

private fun NavBackStackEntry.isFullscreenMedia(): Boolean =
    destination.hasRoute(ViewerRoute::class) || destination.hasRoute(VideoPlayerRoute::class) ||
        destination.hasRoute(ExternalImageRoute::class)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition =
    if (targetState.isFullscreenMedia()) {
        fadeIn(tween(MEDIA_MS)) + scaleIn(tween(PUSH_MS, easing = Emphasized), initialScale = 0.92f)
    } else {
        slideInHorizontally(tween(PUSH_MS, easing = Emphasized)) { (it * 0.3f).toInt() } + fadeIn(tween(PUSH_MS))
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition =
    if (targetState.isFullscreenMedia()) {
        // L'écran d'origine reste (atténué) sous la visionneuse pendant son apparition.
        fadeOut(tween(PUSH_MS), targetAlpha = 0.4f)
    } else {
        slideOutHorizontally(tween(PUSH_MS, easing = Emphasized)) { -(it * 0.1f).toInt() } + fadeOut(tween(PUSH_MS))
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(): EnterTransition =
    if (initialState.isFullscreenMedia()) {
        EnterTransition.None
    } else {
        slideInHorizontally(tween(PUSH_MS, easing = Emphasized)) { -(it * 0.1f).toInt() } + fadeIn(tween(PUSH_MS))
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(): ExitTransition =
    if (initialState.isFullscreenMedia()) {
        fadeOut(tween(MEDIA_MS)) + scaleOut(tween(PUSH_MS, easing = Emphasized), targetScale = 0.92f)
    } else {
        slideOutHorizontally(tween(PUSH_MS, easing = Emphasized)) { (it * 0.3f).toInt() } + fadeOut(tween(PUSH_MS))
    }

/** Graphe de navigation de l'appli (routes typées de Routes.kt). */
@Composable
fun AppNavHost(
    mainViewModel: MainViewModel,
    darkTheme: Boolean,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    val context = LocalContext.current
    val activity = LocalActivity.current
    val permissions = rememberMediaPermissionController()

    // Apparence des barres système : un seul propriétaire, d'après la destination courante.
    val currentEntry by navController.currentBackStackEntryAsState()
    SystemBarsAppearance(fullscreenMedia = currentEntry?.isFullscreenMedia() == true, darkTheme = darkTheme)

    LaunchedEffect(permissions.access) {
        mainViewModel.onMediaAccessChanged(permissions.access)
    }
    LaunchedEffect(permissions.grantedResults) {
        // Nouvelle sélection en accès partiel : l'état d'accès ne change pas, on réindexe quand même.
        if (permissions.grantedResults > 0) mainViewModel.onMediaAccessRegranted()
    }
    LaunchedEffect(mainViewModel, navController) {
        mainViewModel.events.collect { event ->
            when (event) {
                // Pas de launchSingleTop : une visionneuse déjà au sommet garderait son ViewModel (et
                // donc l'ancien média). popUpTo remplace toute visionneuse / lecteur par une entrée neuve.
                is MainEvent.OpenViewer -> navController.navigate(
                    ViewerRoute.of(MediaContext.Single(event.mediaId), event.mediaId, exitOnBack = event.exitOnBack),
                ) { popUpTo<MainRoute>() }
                is MainEvent.OpenExternal -> {
                    val uri = event.uri.toString()
                    val route: Any = if (event.isVideo) {
                        VideoPlayerRoute(
                            mediaId = -1L,
                            externalUri = uri,
                            title = event.title,
                            mimeType = event.mimeType,
                            exitOnBack = event.exitOnBack,
                        )
                    } else {
                        ExternalImageRoute(uri = uri, title = event.title, mimeType = event.mimeType, exitOnBack = event.exitOnBack)
                    }
                    navController.navigate(route) { popUpTo<MainRoute>() }
                }
                is MainEvent.CannotOpen -> {
                    Toast.makeText(context, R.string.shell_open_failed, Toast.LENGTH_LONG).show()
                    // Lancée uniquement pour ouvrir ce fichier : rendre la main à l'appelant.
                    if (event.finish && navController.previousBackStackEntry == null) activity?.finish()
                }
            }
        }
    }

    val openMedia: (MediaContext, Long) -> Unit = { mediaContext, mediaId ->
        navController.navigate(ViewerRoute.of(mediaContext, mediaId))
    }
    val openAccount: () -> Unit = { navController.navigate(AccountRoute) { launchSingleTop = true } }

    NavHost(
        navController = navController,
        startDestination = MainRoute,
        modifier = modifier
            .fillMaxSize()
            .background(OneUiTheme.colors.background),
        enterTransition = { enter() },
        exitTransition = { exit() },
        popEnterTransition = { popEnter() },
        popExitTransition = { popExit() },
    ) {
        composable<MainRoute> {
            val oneDriveOnlyChosen by mainViewModel.oneDriveOnlyChosen.collectAsStateWithLifecycle()
            val cloudSignedIn by mainViewModel.cloudSignedIn.collectAsStateWithLifecycle()
            MainScreen(
                permissions = permissions,
                mode = mainScreenMode(permissions.access, mainViewModel.cloudAvailable, oneDriveOnlyChosen),
                cloudSignedIn = cloudSignedIn,
                onUseOneDriveOnly = if (mainViewModel.cloudAvailable) mainViewModel::chooseOneDriveOnly else null,
                onOpenMedia = openMedia,
                onOpenAlbum = { id -> navController.navigate(AlbumRoute(id)) },
                onOpenFavorites = { navController.navigate(FavoritesRoute) },
                onOpenVideos = { navController.navigate(VideosRoute) },
                onOpenSettings = { navController.navigate(SettingsRoute) { launchSingleTop = true } },
                onOpenAccount = openAccount,
            )
        }
        composable<AlbumRoute> { entry ->
            val route = entry.toRoute<AlbumRoute>()
            MediaCollectionScreen(
                context = MediaContext.Album(route.albumId),
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenMedia = openMedia,
            )
        }
        composable<FavoritesRoute> {
            MediaCollectionScreen(
                context = MediaContext.Favorites,
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenMedia = openMedia,
            )
        }
        composable<VideosRoute> {
            MediaCollectionScreen(
                context = MediaContext.Videos,
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenMedia = openMedia,
            )
        }
        composable<ViewerRoute> { entry ->
            val route = entry.toRoute<ViewerRoute>()
            ViewerScreen(
                onBack = rememberBackAction(navController, route.exitOnBack),
                onPlayVideo = { mediaId -> navController.navigate(VideoPlayerRoute(mediaId)) },
            )
        }
        composable<VideoPlayerRoute> { entry ->
            val route = entry.toRoute<VideoPlayerRoute>()
            VideoPlayerScreen(
                onBack = rememberBackAction(navController, route.exitOnBack),
                onOpenAccount = openAccount,
            )
        }
        composable<ExternalImageRoute> { entry ->
            val route = entry.toRoute<ExternalImageRoute>()
            ExternalImageScreen(
                uri = route.uri.toUri(),
                title = route.title,
                mimeType = route.mimeType,
                onBack = rememberBackAction(navController, route.exitOnBack),
            )
        }
        composable<SettingsRoute> {
            SettingsScreen(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenAccount = openAccount,
                onOpenStorage = { navController.navigate(StorageRoute) { launchSingleTop = true } },
            )
        }
        composable<AccountRoute> {
            AccountScreen(onBack = dropUnlessResumed { navController.popBackStack() })
        }
        composable<StorageRoute> {
            StorageScreen(
                onBack = dropUnlessResumed { navController.popBackStack() },
                onOpenAccount = openAccount,
                onOpenSpaceSaver = { navController.navigate(SpaceSaverRoute) { launchSingleTop = true } },
            )
        }
        composable<SpaceSaverRoute> {
            SpaceSaverScreen(onBack = dropUnlessResumed { navController.popBackStack() })
        }
    }
}

/**
 * Action « retour » d'un écran plein écran. Ouvert pour une autre appli ([exitOnBack]), le retour (bouton ou geste système) termine l'activité pour
 * rendre la main à l'appelant au lieu d'afficher la galerie. Sinon : dépilement habituel.
 */
@Composable
private fun rememberBackAction(navController: NavHostController, exitOnBack: Boolean): () -> Unit {
    val activity = LocalActivity.current
    val pop = dropUnlessResumed { navController.popBackStack() }
    // Les ouvertures externes sont toujours posées directement sur MainRoute (popUpTo).
    if (!exitOnBack || activity == null) return pop
    BackHandler { activity.finish() }
    return { activity.finish() }
}
