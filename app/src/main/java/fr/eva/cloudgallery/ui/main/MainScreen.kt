package fr.eva.cloudgallery.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.domain.model.MediaAccess
import fr.eva.cloudgallery.domain.model.MediaContext
import fr.eva.cloudgallery.ui.albums.AlbumsScreen
import fr.eva.cloudgallery.ui.menu.MenuScreen
import fr.eva.cloudgallery.ui.permissions.MediaPermissionController
import fr.eva.cloudgallery.ui.permissions.NoLocalAccessBanner
import fr.eva.cloudgallery.ui.permissions.PartialAccessBanner
import fr.eva.cloudgallery.ui.permissions.PermissionScreen
import fr.eva.cloudgallery.ui.photos.NoLocalAccessEmpty
import fr.eva.cloudgallery.ui.photos.PhotosScreen
import fr.eva.cloudgallery.ui.theme.OneUiTheme

/** Hauteur réservée au bandeau d'accès (partiel, ou « OneDrive seul ») au-dessus de la barre d'onglets. */
private val PartialBannerHeight = 56.dp

/**
 * Ecran principal à onglets (Photos / Albums / Menu). Les onglets basculent instantanément ;
 * l'état sauvegardable de chacun (positions de défilement…) est conservé via un
 * SaveableStateHolder. Sans accès aux médias ([mode]) : écran d'autorisation, ou onglets sur
 * OneDrive seul (« Utiliser OneDrive seulement » choisi) avec un bandeau pour autoriser les photos.
 */
@Composable
fun MainScreen(
    permissions: MediaPermissionController,
    mode: MainScreenMode,
    cloudSignedIn: Boolean,
    onUseOneDriveOnly: (() -> Unit)?,
    onOpenMedia: (MediaContext, Long) -> Unit,
    onOpenAlbum: (Long) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenVideos: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenAccount: () -> Unit,
    modifier: Modifier = Modifier,
    onOpenSearch: (() -> Unit)? = null,
) {
    when (mode) {
        MainScreenMode.LOADING -> {
            Box(modifier.fillMaxSize().background(OneUiTheme.colors.background))
            return
        }
        MainScreenMode.PERMISSION -> {
            PermissionScreen(controller = permissions, modifier = modifier, onUseOneDriveOnly = onUseOneDriveOnly)
            return
        }
        MainScreenMode.ONEDRIVE_ONLY, MainScreenMode.LIBRARY -> Unit
    }
    val oneDriveOnly = mode == MainScreenMode.ONEDRIVE_ONLY

    var tab by rememberSaveable { mutableStateOf(MainTab.PHOTOS) }
    var photosSelecting by remember { mutableStateOf(false) }
    var albumsSelecting by remember { mutableStateOf(false) }
    val selecting = when (tab) {
        MainTab.PHOTOS -> photosSelecting
        MainTab.ALBUMS -> albumsSelecting
        MainTab.MENU -> false
    }
    val bannerOnPhotos = tab == MainTab.PHOTOS && !selecting
    val showPartialBanner = (permissions.access == MediaAccess.PARTIAL || oneDriveOnly) && bannerOnPhotos

    // Déclaré avant le contenu : les BackHandler des onglets (sortie du mode sélection) restent prioritaires.
    BackHandler(enabled = tab != MainTab.PHOTOS && !selecting) { tab = MainTab.PHOTOS }

    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val baseBottom = navBottom + OneUiTheme.dimens.bottomBarHeight
    val contentPadding = PaddingValues(bottom = if (showPartialBanner) baseBottom + PartialBannerHeight else baseBottom)

    val stateHolder = rememberSaveableStateHolder()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(OneUiTheme.colors.background),
    ) {
        stateHolder.SaveableStateProvider(tab.name) {
            when (tab) {
                MainTab.PHOTOS -> PhotosScreen(
                    onOpenMedia = onOpenMedia,
                    onSelectionModeChange = { photosSelecting = it },
                    onOpenSettings = onOpenSettings,
                    onOpenSearch = onOpenSearch,
                    contentPadding = contentPadding,
                    modifier = Modifier.fillMaxSize(),
                    noLocalAccess = if (oneDriveOnly) {
                        NoLocalAccessEmpty(
                            cloudSignedIn = cloudSignedIn,
                            onSignIn = onOpenAccount,
                            onAllowPhotos = permissions::requestOrOpenSettings,
                        )
                    } else {
                        null
                    },
                )
                MainTab.ALBUMS -> AlbumsScreen(
                    onOpenAlbum = onOpenAlbum,
                    onOpenFavorites = onOpenFavorites,
                    onOpenVideos = onOpenVideos,
                    onSelectionModeChange = { albumsSelecting = it },
                    contentPadding = contentPadding,
                    modifier = Modifier.fillMaxSize(),
                )
                MainTab.MENU -> MenuScreen(
                    onOpenFavorites = onOpenFavorites,
                    onOpenVideos = onOpenVideos,
                    onOpenSettings = onOpenSettings,
                    onOpenAccount = onOpenAccount,
                    contentPadding = contentPadding,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        AnimatedVisibility(
            visible = !selecting,
            enter = slideInVertically(tween(250)) { it } + fadeIn(tween(250)),
            exit = slideOutVertically(tween(200)) { it } + fadeOut(tween(200)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(Modifier.fillMaxWidth()) {
                if (showPartialBanner) {
                    if (oneDriveOnly) {
                        NoLocalAccessBanner(onAllow = permissions::requestOrOpenSettings)
                    } else {
                        PartialAccessBanner(onManage = permissions::request)
                    }
                }
                MainTabBar(selected = tab, onSelect = { tab = it })
            }
        }
    }

    // Un onglet quitté ne signale pas forcément la fin de son mode sélection.
    LaunchedEffect(tab) {
        if (tab != MainTab.PHOTOS) photosSelecting = false
        if (tab != MainTab.ALBUMS) albumsSelecting = false
    }
}
