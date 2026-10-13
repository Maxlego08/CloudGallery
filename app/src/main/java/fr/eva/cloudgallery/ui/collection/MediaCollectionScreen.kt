package fr.eva.cloudgallery.ui.collection

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.RemoveCircleOutline
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.net.toUri
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.MediaContext
import fr.eva.cloudgallery.ui.components.OneUiBackButton
import fr.eva.cloudgallery.ui.components.OneUiMenuItem
import fr.eva.cloudgallery.ui.grid.GridExtraAction
import fr.eva.cloudgallery.ui.grid.MediaGridPage
import fr.eva.cloudgallery.ui.navigation.encodeContext

/** Grille d'un album, des Favoris ou des Vidéos, avec la même sélection que l'onglet Photos. */
@Composable
fun MediaCollectionScreen(
    context: MediaContext,
    onBack: () -> Unit,
    onOpenMedia: (MediaContext, Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val vmKey = remember(context) {
        val (type, arg) = encodeContext(context)
        "collection:$type:$arg"
    }
    val viewModel = hiltViewModel<CollectionViewModel, CollectionViewModel.Factory>(
        key = vmKey,
        creationCallback = { factory -> factory.create(context) },
    )
    val album by viewModel.album.collectAsStateWithLifecycle()
    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }

    val title = when (context) {
        is MediaContext.Album -> album?.name.orEmpty()
        MediaContext.Favorites -> stringResource(R.string.collection_favorites)
        MediaContext.Videos -> stringResource(R.string.collection_videos)
        is MediaContext.Search -> context.query
        MediaContext.Timeline, is MediaContext.Single -> stringResource(R.string.photos_title)
    }
    val emptyIcon = when (context) {
        MediaContext.Favorites -> Icons.Outlined.FavoriteBorder
        MediaContext.Videos -> Icons.Outlined.VideoLibrary
        else -> Icons.Outlined.PhotoAlbum
    }
    val emptyTitle = when (context) {
        MediaContext.Favorites -> stringResource(R.string.collection_empty_favorites)
        MediaContext.Videos -> stringResource(R.string.collection_empty_videos)
        else -> stringResource(R.string.collection_empty_album)
    }
    val emptyMessage = if (context == MediaContext.Favorites) {
        stringResource(R.string.collection_empty_favorites_message)
    } else {
        null
    }

    // Album virtuel : « Retirer » remplace « Ajouter à l'album » (qui passe dans « Plus »).
    val isVirtualAlbum = album?.type == AlbumType.VIRTUAL
    val removeLabel = stringResource(R.string.collection_remove_from_album)
    val albumOverride = if (isVirtualAlbum) {
        GridExtraAction(Icons.Outlined.RemoveCircleOutline, removeLabel) { viewModel.removeFromAlbum() }
    } else {
        null
    }
    val unfavoriteLabel = stringResource(R.string.common_favorite_remove)
    val favoriteOverride = if (context == MediaContext.Favorites) {
        GridExtraAction(Icons.Outlined.FavoriteBorder, unfavoriteLabel) { viewModel.removeFromFavorites() }
    } else {
        null
    }

    val selectLabel = stringResource(R.string.grid_select)
    val oneDriveLabel = stringResource(R.string.albums_open_onedrive)
    val isCloudAlbum = album?.type == AlbumType.CLOUD_FOLDER
    val menuItems = remember(viewModel, selectLabel, oneDriveLabel, isCloudAlbum) {
        buildList {
            add(OneUiMenuItem(selectLabel) { viewModel.enterSelectionMode() })
            if (isCloudAlbum) add(OneUiMenuItem(oneDriveLabel) { viewModel.openInOneDrive() })
        }
    }
    val androidContext = LocalContext.current
    LaunchedEffect(viewModel) {
        viewModel.oneDriveLinks.collect { url -> openOneDriveLink(androidContext, url) }
    }

    MediaGridPage(
        viewModel = viewModel,
        title = title,
        gridState = gridState,
        emptyIcon = emptyIcon,
        emptyTitle = emptyTitle,
        emptyMessage = emptyMessage,
        onOpenMedia = onOpenMedia,
        bottomPadding = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding(),
        navigationIcon = { OneUiBackButton(onClick = onBack) },
        menuItems = menuItems,
        albumActionOverride = albumOverride,
        favoriteActionOverride = favoriteOverride,
        modifier = modifier,
    )
}

/**
 * Ouvre [url] (lien web du dossier OneDrive) dans un navigateur. L'appli OneDrive ne sait pas
 * ouvrir un dossier à partir d'un lien (testé sur l'appli 2026, compte personnel : « Cette page ne
 * peut pas s'ouvrir dans l'application OneDrive ») et intercepte ces liens : le navigateur est
 * visé explicitement (par défaut, sinon Chrome, sinon le premier trouvé).
 */
private fun openOneDriveLink(context: Context, url: String) {
    val view = Intent(Intent.ACTION_VIEW, url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val browser = browserPackage(context)
    for (intent in listOfNotNull(browser?.let { Intent(view).setPackage(it) }, view)) {
        try {
            context.startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) {
            // Essai suivant.
        }
    }
    Toast.makeText(context, R.string.albums_open_onedrive_failed, Toast.LENGTH_SHORT).show()
}

/** Navigateur pour un lien web quelconque : celui par défaut, sinon Chrome, sinon le premier ; jamais OneDrive. */
private fun browserPackage(context: Context): String? {
    val pm = context.packageManager
    val probe = Intent(Intent.ACTION_VIEW, "https://example.com".toUri()).addCategory(Intent.CATEGORY_BROWSABLE)
    val default = pm.resolveActivity(probe, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
    val candidates = pm.queryIntentActivities(probe, PackageManager.MATCH_ALL)
        .map { it.activityInfo.packageName }
        .filter { it != ONEDRIVE_PACKAGE }
    return default?.takeIf { it in candidates } ?: candidates.firstOrNull { it == CHROME_PACKAGE } ?: candidates.firstOrNull()
}

private const val ONEDRIVE_PACKAGE = "com.microsoft.skydrive"
private const val CHROME_PACKAGE = "com.android.chrome"
