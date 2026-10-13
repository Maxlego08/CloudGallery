package fr.eva.cloudgallery.ui.navigation

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import fr.eva.cloudgallery.ui.albums.AlbumVisibilityScreen
import fr.eva.cloudgallery.domain.model.MediaContext
import androidx.core.net.toUri
import fr.eva.cloudgallery.ui.collection.MediaCollectionScreen
import fr.eva.cloudgallery.ui.components.SystemBarsAppearance
import fr.eva.cloudgallery.ui.main.MainEvent
import fr.eva.cloudgallery.ui.main.MainScreen
import fr.eva.cloudgallery.ui.main.MainViewModel
import fr.eva.cloudgallery.ui.main.mainScreenMode
import fr.eva.cloudgallery.ui.permissions.rememberMediaPermissionController
import fr.eva.cloudgallery.ui.search.SearchScreen
import fr.eva.cloudgallery.ui.settings.SettingsScreen
import fr.eva.cloudgallery.ui.saver.SpaceSaverScreen
import fr.eva.cloudgallery.ui.storage.StorageScreen
import fr.eva.cloudgallery.ui.video.VideoPlayerScreen
import fr.eva.cloudgallery.ui.viewer.ExternalImageScreen
import fr.eva.cloudgallery.ui.viewer.LocalMediaBounds
import fr.eva.cloudgallery.ui.viewer.MediaBoundsRegistry
import fr.eva.cloudgallery.ui.viewer.ViewerScreen
import fr.eva.cloudgallery.ui.viewer.rememberViewerTransition

/** "Emphasized" curve: viewer appearance and dismissal. */
private val Emphasized = CubicBezierEasing(0.2f, 0f, 0f, 1f)
private const val PUSH_MS = 300
private const val MEDIA_MS = 250

/*
 * Screen-to-screen transition, measured on Samsung Gallery (One UI 8, Android 16, 60 fps):
 * the top screen enters / exits entirely from the right; the one below slides 30% to the
 * left and darkens almost to black (black NavHost background visible through transparency).
 * Same curve for both screens: slow start, fast middle, long deceleration (~340 ms).
 */
private val OneUiSlide = CubicBezierEasing(0.9f, 0f, 0.3f, 1f)
private const val SLIDE_MS = 340
private const val UNDER_OFFSET = 0.3f
private const val SHADE_MS = 130
private const val SHADE_ALPHA = 0.1f

/** Viewer: it animates its own opening and return (ViewerTransition.kt). */
private fun NavBackStackEntry.isViewer(): Boolean = destination.hasRoute(ViewerRoute::class)

private fun NavBackStackEntry.isFullscreenMedia(): Boolean =
    destination.hasRoute(ViewerRoute::class) || destination.hasRoute(VideoPlayerRoute::class) ||
        destination.hasRoute(ExternalImageRoute::class)

private fun AnimatedContentTransitionScope<NavBackStackEntry>.enter(): EnterTransition =
    if (targetState.isViewer()) {
        EnterTransition.None
    } else if (targetState.isFullscreenMedia()) {
        fadeIn(tween(MEDIA_MS)) + scaleIn(tween(PUSH_MS, easing = Emphasized), initialScale = 0.92f)
    } else {
        slideInHorizontally(tween(SLIDE_MS, easing = OneUiSlide)) { it }
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.exit(): ExitTransition =
    if (targetState.isFullscreenMedia()) {
        // The originating screen stays (dimmed) under the viewer while it appears.
        fadeOut(tween(PUSH_MS), targetAlpha = 0.4f)
    } else {
        slideOutHorizontally(tween(SLIDE_MS, easing = OneUiSlide)) { -(it * UNDER_OFFSET).toInt() } +
            fadeOut(tween(SHADE_MS, easing = LinearEasing), targetAlpha = SHADE_ALPHA)
    }

/*
 * Back: on the button, One UI curve; on the predictive back gesture, the transition is
 * positioned on the gesture progress (seekTo) then finished on release, hence a linear curve
 * so that the screen follows the finger. Without explicit "predictive" transitions, Navigation
 * applies its own (screen scaled to 70%, fade) instead of the slide.
 */
private fun AnimatedContentTransitionScope<NavBackStackEntry>.popEnter(easing: Easing = OneUiSlide): EnterTransition =
    if (initialState.isFullscreenMedia()) {
        EnterTransition.None
    } else {
        slideInHorizontally(tween(SLIDE_MS, easing = easing)) { -(it * UNDER_OFFSET).toInt() } +
            fadeIn(tween(SHADE_MS, easing = LinearEasing), initialAlpha = SHADE_ALPHA)
    }

private fun AnimatedContentTransitionScope<NavBackStackEntry>.popExit(easing: Easing = OneUiSlide): ExitTransition =
    if (initialState.isViewer()) {
        // Kept on screen while the photo returns to its thumbnail.
        ExitTransition.KeepUntilTransitionsFinished
    } else if (initialState.isFullscreenMedia()) {
        val mediaEasing = if (easing == LinearEasing) LinearEasing else Emphasized
        fadeOut(tween(MEDIA_MS, easing = mediaEasing)) + scaleOut(tween(PUSH_MS, easing = mediaEasing), targetScale = 0.92f)
    } else {
        // The screen exits entirely to the right, without fading.
        slideOutHorizontally(tween(SLIDE_MS, easing = easing)) { it }
    }

/** The app's navigation graph (typed routes from Routes.kt). */
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

    // System bar appearance: a single owner, based on the current destination.
    val currentEntry by navController.currentBackStackEntryAsState()
    SystemBarsAppearance(fullscreenMedia = currentEntry?.isFullscreenMedia() == true, darkTheme = darkTheme)

    LaunchedEffect(permissions.access) {
        mainViewModel.onMediaAccessChanged(permissions.access)
    }
    LaunchedEffect(permissions.grantedResults) {
        // New selection under partial access: the access state does not change, reindex anyway.
        if (permissions.grantedResults > 0) mainViewModel.onMediaAccessRegranted()
    }
    LaunchedEffect(mainViewModel, navController) {
        mainViewModel.events.collect { event ->
            when (event) {
                // No launchSingleTop: a viewer already on top would keep its ViewModel (and
                // hence the old media). popUpTo replaces any viewer / player with a fresh entry.
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
                    // Launched only to open this file: hand control back to the caller.
                    if (event.finish && navController.previousBackStackEntry == null) activity?.finish()
                }
            }
        }
    }

    val mediaBounds = remember { MediaBoundsRegistry() }

    val openMedia: (MediaContext, Long) -> Unit = { mediaContext, mediaId ->
        navController.navigate(ViewerRoute.of(mediaContext, mediaId))
    }
    val openAccount: () -> Unit = { navController.navigate(AccountRoute) { launchSingleTop = true } }

    CompositionLocalProvider(LocalMediaBounds provides mediaBounds) {
        NavHost(
            navController = navController,
            startDestination = MainRoute,
            modifier = modifier
                .fillMaxSize()
                // Black: the screen below darkens through transparency during transitions (One UI).
                .background(Color.Black),
            enterTransition = { enter() },
            exitTransition = { exit() },
            popEnterTransition = { popEnter() },
            popExitTransition = { popExit() },
            predictivePopEnterTransition = { popEnter(LinearEasing) },
            predictivePopExitTransition = { popExit(LinearEasing) },
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
                    onOpenAlbumVisibility = { navController.navigate(AlbumVisibilityRoute) { launchSingleTop = true } },
                    onOpenSettings = { navController.navigate(SettingsRoute) { launchSingleTop = true } },
                    onOpenAccount = openAccount,
                    onOpenSearch = { navController.navigate(SearchRoute) { launchSingleTop = true } },
                )
            }
            composable<SearchRoute> {
                SearchScreen(
                    onBack = dropUnlessResumed { navController.popBackStack() },
                    onOpenMedia = openMedia,
                    onOpenAlbum = { id -> navController.navigate(AlbumRoute(id)) },
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
                // Video player on top: the viewer exits without returning to the grid.
                val current by navController.currentBackStackEntryAsState()
                val covered = remember(current, entry) {
                    current?.id != entry.id &&
                        runCatching { navController.getBackStackEntry<ViewerRoute>() }.getOrNull()?.id == entry.id
                }
                ViewerScreen(
                    onBack = rememberBackAction(navController, route.exitOnBack),
                    onPlayVideo = { mediaId -> navController.navigate(VideoPlayerRoute(mediaId)) },
                    transition = rememberViewerTransition(transition, stayFullscreen = covered),
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
            composable<AlbumVisibilityRoute> {
                AlbumVisibilityScreen(onBack = dropUnlessResumed { navController.popBackStack() })
            }
            composable<SpaceSaverRoute> {
                SpaceSaverScreen(onBack = dropUnlessResumed { navController.popBackStack() })
            }
        }
    }
}

/**
 * "Back" action of a full-screen screen. When opened for another app ([exitOnBack]), back (button or system gesture) finishes the activity to
 * hand control back to the caller instead of showing the gallery. Otherwise: the usual pop.
 */
@Composable
private fun rememberBackAction(navController: NavHostController, exitOnBack: Boolean): () -> Unit {
    val activity = LocalActivity.current
    val pop = dropUnlessResumed { navController.popBackStack() }
    // External openings are always placed directly on top of MainRoute (popUpTo).
    if (!exitOnBack || activity == null) return pop
    BackHandler { activity.finish() }
    return { activity.finish() }
}
