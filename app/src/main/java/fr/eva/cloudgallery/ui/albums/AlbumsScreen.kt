package fr.eva.cloudgallery.ui.albums

import android.content.res.Configuration
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material.icons.outlined.VideoLibrary
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.AlbumSort
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AlbumType
import fr.eva.cloudgallery.domain.model.MediaType
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
import fr.eva.cloudgallery.ui.theme.OneUiTheme

/** Dialogs of the Albums tab (enum => saveable). */
private enum class AlbumsDialog { CREATE, SORT, RENAME, DELETE }

/**
 * Samsung Gallery-style (One UI 8) "Albums" tab: large title, Favorites / Videos quick access,
 * album grid (device folders, OneDrive folders, custom albums) and selection mode.
 *
 * @param contentPadding space taken by the shell's tab bar (bottom), hidden in selection mode.
 */
@Composable
fun AlbumsScreen(
    onOpenAlbum: (Long) -> Unit,
    onOpenFavorites: () -> Unit,
    onOpenVideos: () -> Unit,
    onOpenAlbumVisibility: () -> Unit,
    onSelectionModeChange: (Boolean) -> Unit,
    contentPadding: PaddingValues,
    modifier: Modifier = Modifier,
) {
    val viewModel: AlbumsViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val colors = OneUiTheme.colors
    val dimens = OneUiTheme.dimens

    // Reports selection mode up to the shell (which hides its tab bar) and releases it when leaving the screen.
    val currentOnSelectionModeChange by rememberUpdatedState(onSelectionModeChange)
    LaunchedEffect(state.selectionMode) { currentOnSelectionModeChange(state.selectionMode) }
    DisposableEffect(Unit) { onDispose { currentOnSelectionModeChange(false) } }
    BackHandler(enabled = state.selectionMode) { viewModel.clearSelection() }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                AlbumsEvent.Error ->
                    Toast.makeText(context, R.string.common_error_generic, Toast.LENGTH_SHORT).show()
            }
        }
    }

    var dialog by rememberSaveable { mutableStateOf<AlbumsDialog?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    val gridState = rememberSaveable(saver = LazyGridState.Saver) { LazyGridState() }
    val titleAlpha by rememberCollapsedTitleAlpha(gridState)

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val columns = AlbumsLogic.columnCount(isLandscape)
    val navBarBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val bottomPadding = if (state.selectionMode) {
        dimens.actionBarHeight + navBarBottom
    } else {
        contentPadding.calculateBottomPadding()
    }

    val title = stringResource(R.string.albums_title)
    val badgeCloud = stringResource(R.string.albums_badge_cloud)
    val badgeVirtual = stringResource(R.string.albums_badge_virtual)
    val badgePinned = stringResource(R.string.albums_badge_pinned)

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        Column(Modifier.fillMaxSize()) {
            if (state.selectionMode) {
                OneUiSelectionTopBar(
                    selectedCount = state.selection.size,
                    allSelected = state.allSelected,
                    onToggleAll = viewModel::toggleSelectAll,
                )
            } else {
                OneUiTopBar(
                    title = title,
                    titleAlpha = titleAlpha,
                    actions = {
                        OneUiIconButton(
                            icon = Icons.Filled.Add,
                            contentDescription = stringResource(R.string.albums_create),
                            onClick = { dialog = AlbumsDialog.CREATE },
                        )
                        Box {
                            OneUiIconButton(
                                icon = Icons.Filled.MoreVert,
                                contentDescription = stringResource(R.string.common_more),
                                onClick = { menuExpanded = true },
                            )
                            OneUiOverflowMenu(
                                expanded = menuExpanded,
                                onDismiss = { menuExpanded = false },
                                items = listOf(
                                    OneUiMenuItem(label = stringResource(R.string.albums_sort)) {
                                        menuExpanded = false
                                        dialog = AlbumsDialog.SORT
                                    },
                                    OneUiMenuItem(
                                        label = stringResource(R.string.albums_select),
                                        enabled = state.albums.isNotEmpty(),
                                    ) {
                                        menuExpanded = false
                                        viewModel.startSelection()
                                    },
                                    OneUiMenuItem(label = stringResource(R.string.albums_visibility_menu)) {
                                        menuExpanded = false
                                        onOpenAlbumVisibility()
                                    },
                                ),
                            )
                        }
                    },
                )
            }

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                state = gridState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = bottomPadding + 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "header", span = { GridItemSpan(maxLineSpan) }, contentType = "header") {
                    OneUiLargeTitleHeader(title = title, subtitle = null)
                }
                if (!state.loading) {
                    item(key = "favorites", contentType = "album") {
                        QuickAccessCell(
                            name = stringResource(R.string.albums_favorites),
                            quick = state.favorites,
                            emptyIcon = Icons.Outlined.FavoriteBorder,
                            dimmed = state.selectionMode,
                            onClick = { if (!state.selectionMode) onOpenFavorites() },
                        )
                    }
                    item(key = "videos", contentType = "album") {
                        QuickAccessCell(
                            name = stringResource(R.string.albums_videos),
                            quick = state.videos,
                            emptyIcon = Icons.Outlined.VideoLibrary,
                            dimmed = state.selectionMode,
                            onClick = { if (!state.selectionMode) onOpenVideos() },
                        )
                    }
                    items(state.albums, key = { "album_${it.id}" }, contentType = { "album" }) { album ->
                        val selected = album.id in state.selection
                        AlbumGridCell(
                            name = album.name,
                            countText = pluralStringResource(R.plurals.common_items_count, album.mediaCount, album.mediaCount),
                            coverUri = album.coverUri,
                            coverCloud = album.coverCloud,
                            coverIsVideo = album.coverType == MediaType.VIDEO,
                            coverVersion = album.coverDateModified,
                            emptyIcon = if (album.type == AlbumType.VIRTUAL) Icons.Outlined.Collections else Icons.Outlined.PhotoLibrary,
                            badges = albumBadges(album, badgeCloud, badgeVirtual, badgePinned),
                            selectionMode = state.selectionMode,
                            selected = selected,
                            onClick = {
                                if (state.selectionMode) viewModel.toggleSelection(album.id) else onOpenAlbum(album.id)
                            },
                            onLongClick = {
                                if (state.selectionMode) viewModel.toggleSelection(album.id) else viewModel.startSelection(album.id)
                            },
                        )
                    }
                    if (state.albums.isEmpty()) {
                        item(key = "empty", span = { GridItemSpan(maxLineSpan) }, contentType = "empty") {
                            EmptyState(
                                icon = Icons.Outlined.PhotoAlbum,
                                title = stringResource(R.string.albums_empty_title),
                                message = stringResource(R.string.albums_empty_message),
                                modifier = Modifier.padding(top = 32.dp),
                            )
                        }
                    }
                }
            }
        }

        if (state.selectionMode) {
            val actions = state.actions
            OneUiBottomActionBar(
                actions = listOf(
                    OneUiAction(
                        icon = Icons.Outlined.DriveFileRenameOutline,
                        label = stringResource(R.string.common_rename),
                        enabled = actions.canRename,
                    ) { dialog = AlbumsDialog.RENAME },
                    OneUiAction(
                        icon = if (actions.pinTarget) Icons.Outlined.PushPin else Icons.Filled.PushPin,
                        label = stringResource(if (actions.pinTarget) R.string.albums_pin else R.string.albums_unpin),
                        enabled = actions.canPin,
                    ) { viewModel.togglePinSelected() },
                    OneUiAction(
                        icon = Icons.Outlined.Delete,
                        label = stringResource(R.string.common_delete),
                        enabled = actions.canDelete,
                    ) { dialog = AlbumsDialog.DELETE },
                ),
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }

    when (dialog) {
        AlbumsDialog.CREATE -> OneUiTextInputDialog(
            title = stringResource(R.string.albums_create),
            initialValue = "",
            confirmLabel = stringResource(R.string.common_create),
            placeholder = stringResource(R.string.albums_name_placeholder),
            onConfirm = { name ->
                dialog = null
                viewModel.createAlbum(name)
            },
            onDismiss = { dialog = null },
        )

        AlbumsDialog.SORT -> SortDialog(
            current = state.sort,
            onSelect = { sort ->
                dialog = null
                viewModel.setSort(sort)
            },
            onDismiss = { dialog = null },
        )

        AlbumsDialog.RENAME -> {
            val album = state.selectedAlbums.singleOrNull()
            if (album == null) {
                LaunchedEffect(Unit) { dialog = null }
            } else {
                RenameAlbumDialog(
                    album = album,
                    onConfirm = { name ->
                        dialog = null
                        viewModel.renameSelected(name)
                    },
                    onDismiss = { dialog = null },
                )
            }
        }

        AlbumsDialog.DELETE -> {
            val count = state.selectedAlbums.size
            if (count == 0 || !state.actions.canDelete) {
                LaunchedEffect(Unit) { dialog = null }
            } else {
                OneUiDialog(
                    title = pluralStringResource(R.plurals.albums_delete_title, count, count),
                    message = stringResource(R.string.albums_delete_message),
                    confirmLabel = stringResource(R.string.common_delete),
                    dismissLabel = stringResource(R.string.common_cancel),
                    destructive = true,
                    onConfirm = {
                        dialog = null
                        viewModel.deleteSelected()
                    },
                    onDismiss = { dialog = null },
                )
            }
        }

        null -> Unit
    }
}

private fun albumBadges(
    album: AlbumSummary,
    cloud: String,
    virtual: String,
    pinned: String,
): List<AlbumBadge> = buildList {
    if (album.isPinned) add(AlbumBadge(Icons.Filled.PushPin, pinned))
    when (album.type) {
        AlbumType.CLOUD_FOLDER -> add(AlbumBadge(Icons.Outlined.Cloud, cloud))
        AlbumType.VIRTUAL -> add(AlbumBadge(Icons.Outlined.Collections, virtual))
        AlbumType.LOCAL_FOLDER -> Unit
    }
}

/** "Favorites" / "Videos" entry presented as an album (not selectable). */
@Composable
private fun QuickAccessCell(
    name: String,
    quick: QuickAlbum,
    emptyIcon: ImageVector,
    dimmed: Boolean,
    onClick: () -> Unit,
) {
    val cover = quick.cover
    AlbumGridCell(
        name = name,
        countText = pluralStringResource(R.plurals.common_items_count, quick.count, quick.count),
        coverUri = cover?.uri,
        coverCloud = cover?.cloudThumb,
        coverIsVideo = cover?.type == MediaType.VIDEO,
        coverVersion = cover?.dateModified ?: 0L,
        emptyIcon = emptyIcon,
        badges = emptyList(),
        selectionMode = false,
        selected = false,
        onClick = onClick,
        onLongClick = null,
        modifier = if (dimmed) Modifier.alpha(0.4f) else Modifier,
    )
}

@Composable
private fun SortDialog(
    current: AlbumSort,
    onSelect: (AlbumSort) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OneUiTheme.colors
    val options = listOf(
        AlbumSort.CUSTOM to R.string.albums_sort_custom,
        AlbumSort.NAME to R.string.albums_sort_name,
        AlbumSort.RECENT to R.string.albums_sort_recent,
        AlbumSort.COUNT to R.string.albums_sort_count,
    )
    OneUiDialog(
        title = stringResource(R.string.albums_sort_title),
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.common_cancel),
        onConfirm = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth()) {
                options.forEach { (sort, label) ->
                    val selected = sort == current
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = selected, role = Role.RadioButton) { onSelect(sort) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = selected,
                            onClick = null,
                            colors = RadioButtonDefaults.colors(
                                selectedColor = colors.accent,
                                unselectedColor = colors.textSecondary,
                            ),
                        )
                        Spacer(Modifier.width(12.dp))
                        Text(
                            text = stringResource(label),
                            style = OneUiTheme.typography.listTitle,
                            color = colors.textPrimary,
                        )
                    }
                }
            }
        },
    )
}

/**
 * Renames an album. For a folder album, only the display name changes: the message explains it.
 * Confirmation is ignored while the field is blank.
 */
@Composable
private fun RenameAlbumDialog(
    album: AlbumSummary,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = OneUiTheme.colors
    var value by remember(album.id) {
        mutableStateOf(TextFieldValue(album.name, selection = TextRange(0, album.name.length)))
    }
    val focusRequester = remember { FocusRequester() }
    val submit = { AlbumsLogic.normalizeName(value.text)?.let(onConfirm) }
    OneUiDialog(
        title = stringResource(R.string.albums_rename_title),
        message = if (AlbumsLogic.renameIsDisplayOnly(album)) stringResource(R.string.albums_rename_folder_info) else null,
        confirmLabel = stringResource(R.string.common_rename),
        dismissLabel = stringResource(R.string.common_cancel),
        onConfirm = { submit() },
        onDismiss = onDismiss,
        content = {
            TextField(
                value = value,
                onValueChange = { value = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.albums_name_placeholder)) },
                textStyle = OneUiTheme.typography.body.copy(color = colors.textPrimary),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent,
                    unfocusedContainerColor = Color.Transparent,
                    focusedIndicatorColor = colors.accent,
                    unfocusedIndicatorColor = colors.divider,
                    cursorColor = colors.accent,
                    focusedPlaceholderColor = colors.textTertiary,
                    unfocusedPlaceholderColor = colors.textTertiary,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
            LaunchedEffect(Unit) { runCatching { focusRequester.requestFocus() } }
        },
    )
}
