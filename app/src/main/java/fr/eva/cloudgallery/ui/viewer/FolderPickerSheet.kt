package fr.eva.cloudgallery.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import fr.eva.cloudgallery.ui.components.fullscreenSheetProperties
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import kotlinx.coroutines.launch

/** "Copy to folder" sheet: lists the existing local (MediaStore) folders. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FolderPickerSheet(
    folders: List<AlbumSummary>,
    onDismiss: () -> Unit,
    onFolderChosen: (AlbumSummary) -> Unit,
) {
    val colors = OneUiTheme.colors
    val typography = OneUiTheme.typography
    val radius = OneUiTheme.dimens.sheetRadius
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        // Opened over the viewer (black background): light status bar icons.
        properties = fullscreenSheetProperties(),
        shape = RoundedCornerShape(topStart = radius, topEnd = radius),
        containerColor = colors.sheet,
        contentColor = colors.textPrimary,
        tonalElevation = 0.dp,
        dragHandle = {
            Box(
                Modifier
                    .padding(top = 10.dp, bottom = 6.dp)
                    .size(width = 36.dp, height = 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(colors.textTertiary),
            )
        },
    ) {
        Text(
            text = stringResource(R.string.viewer_folder_picker_title),
            style = typography.dialogTitle,
            color = colors.textPrimary,
            modifier = Modifier
                .padding(horizontal = OneUiTheme.dimens.screenPadding, vertical = 12.dp)
                .semantics { heading() },
        )
        if (folders.isEmpty()) {
            Text(
                text = stringResource(R.string.viewer_no_folders),
                style = typography.body,
                color = colors.textSecondary,
                modifier = Modifier.padding(horizontal = OneUiTheme.dimens.screenPadding, vertical = 24.dp),
            )
        } else {
            LazyColumn(Modifier.fillMaxWidth()) {
                items(folders, key = { it.id }) { folder ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = OneUiTheme.dimens.listItemMinHeight)
                            .clickable {
                                scope.launch { sheetState.hide() }.invokeOnCompletion { onFolderChosen(folder) }
                            }
                            .padding(horizontal = OneUiTheme.dimens.screenPadding, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Folder, contentDescription = null, tint = colors.accent, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.width(16.dp))
                        Column(Modifier.weight(1f)) {
                            Text(folder.name, style = typography.listTitle, color = colors.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            folder.relativePath?.let {
                                Text(it, style = typography.listSubtitle, color = colors.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
