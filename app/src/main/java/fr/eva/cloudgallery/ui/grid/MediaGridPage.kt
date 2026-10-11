package fr.eva.cloudgallery.ui.grid

import android.content.Context
import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.LibraryAdd
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.MediaContext
import fr.eva.cloudgallery.ui.albums.AlbumPickerSheet
import fr.eva.cloudgallery.ui.components.EmptyState
import fr.eva.cloudgallery.ui.components.OneUiAction
import fr.eva.cloudgallery.ui.components.OneUiBottomActionBar
import fr.eva.cloudgallery.ui.components.OneUiDialog
import fr.eva.cloudgallery.ui.components.OneUiIconButton
import fr.eva.cloudgallery.ui.components.OneUiLargeTitleHeader
import fr.eva.cloudgallery.ui.components.OneUiMenuItem
import fr.eva.cloudgallery.ui.components.OneUiOverflowMenu
import fr.eva.cloudgallery.ui.components.OneUiSelectionTopBar
import fr.eva.cloudgallery.ui.components.OneUiTextInputDialog
import fr.eva.cloudgallery.ui.components.OneUiTopBar
import fr.eva.cloudgallery.ui.components.rememberCollapsedTitleAlpha
import fr.eva.cloudgallery.ui.components.rememberIntentSenderLauncher
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import fr.eva.cloudgallery.ui.util.shareMedia

/** Action de sélection propre à un écran (remplace une action par défaut de la barre inférieure). */
data class GridExtraAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

private const val LARGE_TITLE_KEY = "large_title"

/**
 * Ecran de grille complet partagé par Photos et les collections : grand titre, barre supérieure
 * dont le titre apparaît au défilement, grille paginée, mode sélection avec barre d'actions,
 * dialogues (suppression, copie) et feuille de choix d'album.
 *
 * @param bottomPadding espace réservé en bas hors mode sélection (barre d'onglets ou barre système).
 * @param albumActionOverride remplace « Ajouter à l'album » dans la barre (l'action passe dans « Plus »).
 * @param favoriteActionOverride remplace l'action Favori par défaut.
 */
@Composable
fun MediaGridPage(
    viewModel: MediaGridViewModel,
    title: String,
    gridState: LazyGridState,
    emptyIcon: ImageVector,
    emptyTitle: String,
    onOpenMedia: (MediaContext, Long) -> Unit,
    modifier: Modifier = Modifier,
    emptyMessage: String? = null,
    bottomPadding: Dp = 0.dp,
    navigationIcon: (@Composable () -> Unit)? = null,
    menuItems: List<OneUiMenuItem> = emptyList(),
    albumActionOverride: GridExtraAction? = null,
    favoriteActionOverride: GridExtraAction? = null,
    isIndexing: Boolean = false,
) {
    val colors = OneUiTheme.colors
    val dimens = OneUiTheme.dimens

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val zoomLevel by viewModel.zoomLevel.collectAsStateWithLifecycle()
    val count by viewModel.count.collectAsStateWithLifecycle()
    val selection by viewModel.selection.collectAsStateWithLifecycle()
    val selectionMode by viewModel.selectionMode.collectAsStateWithLifecycle()
    val allSelected by viewModel.allSelected.collectAsStateWithLifecycle()
    val dayIds by viewModel.dayIds.collectAsStateWithLifecycle()
    val items = viewModel.gridItems.collectAsLazyPagingItems()

    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val gridBottom = if (selectionMode) dimens.actionBarHeight + navBottom else bottomPadding
    val gridTop = statusTop + dimens.appBarHeight

    var menuOpen by remember { mutableStateOf(false) }
    var moreOpen by remember { mutableStateOf(false) }
    var showDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var showCopyDialog by rememberSaveable { mutableStateOf(false) }
    var showAlbumPicker by rememberSaveable { mutableStateOf(false) }

    BackHandler(enabled = selectionMode) { viewModel.exitSelectionMode() }
    LaunchedEffect(selectionMode) {
        if (!selectionMode) {
            moreOpen = false
            showDeleteDialog = false
            showCopyDialog = false
            showAlbumPicker = false
        }
    }
    GridEventsEffect(viewModel)

    val currentOnOpenMedia by rememberUpdatedState(onOpenMedia)
    val onItemClick = remember(viewModel) {
        { item: GridItem.Media ->
            if (viewModel.selectionMode.value) {
                viewModel.toggle(item.media.id)
            } else {
                currentOnOpenMedia(viewModel.mediaContext, item.media.id)
            }
        }
    }
    val onItemLongClick = remember(viewModel) { { item: GridItem.Media -> viewModel.onLongPress(item.media.id) } }
    val onDragSelect = remember(viewModel) { { ids: Set<Long>, select: Boolean -> viewModel.setSelected(ids, select) } }
    val onDragSelectFinished = remember(viewModel) {
        { anchor: MediaSummary, end: MediaSummary, select: Boolean -> viewModel.finishDragSelect(anchor, end, select) }
    }
    val onHeaderToggle = remember(viewModel) { { header: GridItem.Header -> viewModel.toggleDay(header) } }
    val onZoomChange = remember(viewModel) { { delta: Int -> viewModel.onZoomChange(delta) } }

    val subtitle = count?.let { pluralStringResource(R.plurals.common_items_count, it, it) }
    val header = remember(title, subtitle) { largeTitleItem(title, subtitle) }

    // Le petit titre apparaît quand le grand titre quitte l'écran.
    val titleAlpha by rememberCollapsedTitleAlpha(gridState)

    Box(modifier.fillMaxSize().background(colors.background)) {
        val loadedSettings = settings
        val level = zoomLevel
        val isEmpty = count == 0
        if (loadedSettings != null && level != null) {
            if (isEmpty) {
                Column(Modifier.fillMaxSize()) {
                    Spacer(Modifier.height(gridTop))
                    OneUiLargeTitleHeader(title = title, subtitle = null)
                    EmptyState(
                        icon = emptyIcon,
                        title = if (isIndexing) stringResource(R.string.common_loading) else emptyTitle,
                        message = if (isIndexing) null else emptyMessage,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f),
                    )
                    Spacer(Modifier.height(gridBottom))
                }
            } else {
                MediaGrid(
                    items = items,
                    columns = GridZoomLevels.columns(level, landscape),
                    state = gridState,
                    selection = selection,
                    selectionMode = selectionMode,
                    onItemClick = onItemClick,
                    onItemLongClick = onItemLongClick,
                    onDragSelect = onDragSelect,
                    onDragSelectFinished = onDragSelectFinished,
                    onHeaderToggle = onHeaderToggle,
                    dayIds = dayIds,
                    onZoomChange = onZoomChange,
                    showVideoDuration = loadedSettings.showVideoDuration,
                    contentPadding = PaddingValues(top = gridTop, bottom = gridBottom),
                    header = header,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }

        if (selectionMode) {
            OneUiSelectionTopBar(
                selectedCount = selection.size,
                allSelected = allSelected,
                onToggleAll = viewModel::toggleAll,
                modifier = Modifier.align(Alignment.TopCenter),
            )
        } else {
            OneUiTopBar(
                title = title,
                titleAlpha = if (isEmpty) 0f else titleAlpha,
                navigationIcon = navigationIcon,
                modifier = Modifier.align(Alignment.TopCenter),
                actions = {
                    if (menuItems.isNotEmpty()) {
                        Box {
                            OneUiIconButton(
                                icon = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.common_more),
                                onClick = { menuOpen = true },
                            )
                            OneUiOverflowMenu(
                                expanded = menuOpen,
                                onDismiss = { menuOpen = false },
                                items = menuItems.map { entry ->
                                    entry.copy(onClick = {
                                        menuOpen = false
                                        entry.onClick()
                                    })
                                },
                            )
                        }
                    }
                },
            )
        }

        if (selectionMode) {
            val enabled = selection.isNotEmpty()
            val shareLabel = stringResource(R.string.common_share)
            val addToAlbumLabel = stringResource(R.string.common_add_to_album)
            val favoriteLabel = stringResource(R.string.grid_action_favorite)
            val deleteLabel = stringResource(R.string.common_delete)
            val moreLabel = stringResource(R.string.grid_action_more)
            val copyLabel = stringResource(R.string.common_copy_to_album)
            val confirmDelete = loadedSettings?.confirmBeforeDelete ?: true

            val actions = buildList {
                add(OneUiAction(Icons.Outlined.Share, shareLabel, enabled) { viewModel.share() })
                if (albumActionOverride != null) {
                    add(OneUiAction(albumActionOverride.icon, albumActionOverride.label, enabled, albumActionOverride.onClick))
                } else {
                    add(OneUiAction(Icons.Outlined.LibraryAdd, addToAlbumLabel, enabled) { showAlbumPicker = true })
                }
                if (favoriteActionOverride != null) {
                    add(OneUiAction(favoriteActionOverride.icon, favoriteActionOverride.label, enabled, favoriteActionOverride.onClick))
                } else {
                    add(OneUiAction(Icons.Outlined.FavoriteBorder, favoriteLabel, enabled) { viewModel.toggleFavorite() })
                }
                add(
                    OneUiAction(Icons.Outlined.Delete, deleteLabel, enabled) {
                        if (confirmDelete) showDeleteDialog = true else viewModel.requestDelete()
                    },
                )
                add(OneUiAction(Icons.Filled.MoreVert, moreLabel, enabled) { moreOpen = true })
            }
            val moreItems = buildList {
                if (albumActionOverride != null) {
                    add(OneUiMenuItem(addToAlbumLabel) { moreOpen = false; showAlbumPicker = true })
                }
                add(OneUiMenuItem(copyLabel) { moreOpen = false; showCopyDialog = true })
            }

            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth(),
            ) {
                OneUiBottomActionBar(actions = actions)
                Box(Modifier.align(Alignment.TopEnd)) {
                    OneUiOverflowMenu(expanded = moreOpen, onDismiss = { moreOpen = false }, items = moreItems)
                }
            }
        }
    }

    if (showDeleteDialog) {
        val n = selection.size
        OneUiDialog(
            title = pluralStringResource(R.plurals.common_delete_confirm, n, n),
            onDismiss = { showDeleteDialog = false },
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = {
                showDeleteDialog = false
                viewModel.requestDelete()
            },
            dismissLabel = stringResource(R.string.common_cancel),
            message = if (settings?.useTrash != false) {
                stringResource(R.string.common_trash_info)
            } else {
                stringResource(R.string.grid_delete_permanent_message)
            },
            destructive = true,
        )
    }

    if (showCopyDialog) {
        OneUiTextInputDialog(
            title = stringResource(R.string.common_copy_to_album),
            initialValue = "",
            confirmLabel = stringResource(R.string.grid_copy_confirm),
            onConfirm = { name ->
                showCopyDialog = false
                viewModel.copyToFolder(name)
            },
            onDismiss = { showCopyDialog = false },
            placeholder = stringResource(R.string.grid_copy_folder_hint),
        )
    }

    if (showAlbumPicker) {
        AlbumPickerSheet(
            onDismiss = { showAlbumPicker = false },
            onAlbumChosen = { albumId ->
                showAlbumPicker = false
                viewModel.addToAlbum(albumId)
            },
        )
    }
}

/** Grand titre One UI, premier élément pleine largeur de la grille. */
private fun largeTitleItem(title: String, subtitle: String?): LazyGridScope.() -> Unit = {
    item(key = LARGE_TITLE_KEY, span = { GridItemSpan(maxLineSpan) }, contentType = LARGE_TITLE_KEY) {
        OneUiLargeTitleHeader(title = title, subtitle = subtitle)
    }
}

/** Consomme les événements ponctuels du ViewModel (partage, demande de suppression, toasts). */
@Composable
private fun GridEventsEffect(viewModel: MediaGridViewModel) {
    val context = LocalContext.current
    val launcher = rememberIntentSenderLauncher { granted -> viewModel.onDeleteResult(granted) }
    val currentLauncher by rememberUpdatedState(launcher)
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(viewModel, lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            viewModel.events.collect { event ->
                when (event) {
                    is GridEvent.Share -> runCatching {
                        shareMedia(context, event.uris, event.mimeTypes)
                    }.onFailure {
                        Toast.makeText(context, R.string.common_error_generic, Toast.LENGTH_SHORT).show()
                    }

                    is GridEvent.LaunchDeleteRequest -> runCatching {
                        currentLauncher(event.intentSender)
                    }.onFailure {
                        Toast.makeText(context, R.string.common_error_generic, Toast.LENGTH_SHORT).show()
                    }

                    is GridEvent.Message ->
                        Toast.makeText(context, event.message.resolve(context), Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}

private fun GridMessage.resolve(context: Context): String = when (this) {
    is GridMessage.Text -> context.getString(resId, *args.toTypedArray())
    is GridMessage.Plural -> context.resources.getQuantityString(resId, count, *args.toTypedArray())
}
