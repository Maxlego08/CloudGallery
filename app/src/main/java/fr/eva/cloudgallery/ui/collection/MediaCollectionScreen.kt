package fr.eva.cloudgallery.ui.collection

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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
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
    val menuItems = remember(viewModel, selectLabel) {
        listOf(OneUiMenuItem(selectLabel) { viewModel.enterSelectionMode() })
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
