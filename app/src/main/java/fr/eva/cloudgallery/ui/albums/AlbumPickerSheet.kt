package fr.eva.cloudgallery.ui.albums

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Collections
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import fr.eva.cloudgallery.ui.components.fullscreenSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.ui.components.OneUiTextInputDialog
import fr.eva.cloudgallery.ui.image.ThumbnailImage
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import kotlinx.coroutines.launch

/**
 * "Add to album" sheet: lists the custom albums (VIRTUAL) and lets the user create one.
 * Media items are added by reference (no file copy) — the adding itself is done by the caller.
 *
 * [onAlbumChosen] is called after the closing animation; the caller then removes the sheet
 * (without receiving [onDismiss]). [onDismiss] means the sheet was closed without a choice.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumPickerSheet(
    onDismiss: () -> Unit,
    onAlbumChosen: (albumId: Long) -> Unit,
    modifier: Modifier = Modifier,
    /** Opened over a full-screen media item (black background): light status bar icons. */
    overFullscreenMedia: Boolean = false,
) {
    val viewModel: AlbumPickerViewModel = hiltViewModel()
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val colors = OneUiTheme.colors
    val typography = OneUiTheme.typography
    var showCreate by rememberSaveable { mutableStateOf(false) }

    val choose: (Long) -> Unit = { id ->
        scope.launch { sheetState.hide() }.invokeOnCompletion { onAlbumChosen(id) }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = sheetState,
        properties = if (overFullscreenMedia) fullscreenSheetProperties() else ModalBottomSheetProperties(),
        shape = RoundedCornerShape(topStart = OneUiTheme.dimens.sheetRadius, topEnd = OneUiTheme.dimens.sheetRadius),
        containerColor = colors.sheet,
        contentColor = colors.textPrimary,
        dragHandle = { BottomSheetDefaults.DragHandle(color = colors.textTertiary) },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.common_add_to_album),
                style = typography.dialogTitle,
                color = colors.textPrimary,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Text(
                text = stringResource(R.string.albums_picker_caption),
                style = typography.listSubtitle,
                color = colors.textSecondary,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 12.dp),
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                item(key = "new") {
                    PickerRow(
                        title = stringResource(R.string.albums_picker_new),
                        subtitle = null,
                        onClick = { showCreate = true },
                        titleColor = colors.accent,
                    ) {
                        IconCover(Icons.Filled.Add, tint = colors.accent)
                    }
                }
                items(state.albums, key = { it.id }) { album ->
                    PickerAlbumRow(album = album, onClick = { choose(album.id) })
                }
                if (!state.loading && state.albums.isEmpty()) {
                    item(key = "empty") {
                        Text(
                            text = stringResource(R.string.albums_picker_empty),
                            style = typography.listSubtitle,
                            color = colors.textSecondary,
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp),
                        )
                    }
                }
                item(key = "bottom") { Spacer(Modifier.height(16.dp)) }
            }
        }
    }

    if (showCreate) {
        OneUiTextInputDialog(
            title = stringResource(R.string.albums_picker_new),
            initialValue = "",
            confirmLabel = stringResource(R.string.common_create),
            placeholder = stringResource(R.string.albums_name_placeholder),
            onConfirm = { name ->
                showCreate = false
                viewModel.createAlbum(
                    name = name,
                    onCreated = choose,
                    onError = {
                        Toast.makeText(context, R.string.common_error_generic, Toast.LENGTH_SHORT).show()
                    },
                )
            },
            onDismiss = { showCreate = false },
        )
    }
}

@Composable
private fun PickerAlbumRow(album: AlbumSummary, onClick: () -> Unit) {
    val colors = OneUiTheme.colors
    PickerRow(
        title = album.name,
        subtitle = pluralStringResource(R.plurals.common_items_count, album.mediaCount, album.mediaCount),
        onClick = onClick,
    ) {
        if (album.coverUri != null || album.coverCloud != null) {
            ThumbnailImage(
                uri = album.coverUri,
                isVideo = album.coverType == MediaType.VIDEO,
                version = album.coverDateModified,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp)),
                cloud = album.coverCloud,
            )
        } else {
            IconCover(Icons.Outlined.Collections, tint = colors.textTertiary)
        }
    }
}

@Composable
private fun IconCover(icon: ImageVector, tint: Color) {
    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(OneUiTheme.colors.placeholder),
        contentAlignment = Alignment.Center,
    ) {
        Icon(imageVector = icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
    }
}

@Composable
private fun PickerRow(
    title: String,
    subtitle: String?,
    onClick: () -> Unit,
    titleColor: Color = OneUiTheme.colors.textPrimary,
    leading: @Composable () -> Unit,
) {
    val colors = OneUiTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = OneUiTheme.dimens.listItemMinHeight)
            .clickable(onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading()
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = OneUiTheme.typography.listTitle,
                color = titleColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = OneUiTheme.typography.listSubtitle,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
        }
    }
}
