package fr.eva.cloudgallery.ui.albums

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoAlbum
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.AlbumSummary
import fr.eva.cloudgallery.domain.model.AlbumVisibility
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.ui.components.OneUiBackButton
import fr.eva.cloudgallery.ui.components.OneUiDialog
import fr.eva.cloudgallery.ui.components.OneUiDivider
import fr.eva.cloudgallery.ui.components.OneUiLargeTitleHeader
import fr.eva.cloudgallery.ui.components.OneUiTopBar
import fr.eva.cloudgallery.ui.components.rememberCollapsedTitleAlpha
import fr.eva.cloudgallery.ui.image.ThumbnailImage
import fr.eva.cloudgallery.ui.theme.OneUiTheme

private val VisibilityLabels = listOf(
    AlbumVisibility.SHOWN to R.string.albums_visibility_shown,
    AlbumVisibility.HIDDEN_IN_ALBUMS to R.string.albums_visibility_hidden_albums,
    AlbumVisibility.HIDDEN_EVERYWHERE to R.string.albums_visibility_hidden_everywhere,
)

/**
 * « Masquer des albums » (menu de l'onglet Albums) : chaque album, masqués compris, avec son
 * affichage (affiché, masqué dans Albums, masqué dans Albums et Photos). Rien n'est supprimé.
 */
@Composable
fun AlbumVisibilityScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlbumVisibilityViewModel = hiltViewModel(),
) {
    val albums by viewModel.albums.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val titleAlpha by rememberCollapsedTitleAlpha(listState)
    val colors = OneUiTheme.colors
    val title = stringResource(R.string.albums_visibility_menu)
    val layoutDirection = LocalLayoutDirection.current
    val horizontalInsets = WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal).asPaddingValues()
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    var editingId by rememberSaveable { mutableStateOf<Long?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.groupedBackground),
    ) {
        OneUiTopBar(
            title = title,
            titleAlpha = titleAlpha,
            navigationIcon = { OneUiBackButton(onClick = onBack) },
            containerColor = colors.groupedBackground,
        )
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = horizontalInsets.calculateStartPadding(layoutDirection) + 12.dp,
                end = horizontalInsets.calculateEndPadding(layoutDirection) + 12.dp,
                bottom = bottomInset + 24.dp,
            ),
        ) {
            item(key = "header") {
                OneUiLargeTitleHeader(title = title, subtitle = null)
            }
            item(key = "intro") {
                Text(
                    text = stringResource(R.string.albums_visibility_intro),
                    style = OneUiTheme.typography.listSubtitle,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                )
            }
            val list = albums
            if (list != null && list.isEmpty()) {
                item(key = "empty") {
                    Text(
                        text = stringResource(R.string.albums_visibility_empty),
                        style = OneUiTheme.typography.listTitle,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            } else if (list != null) {
                // Une ligne par élément (des centaines de dossiers OneDrive possibles), carte
                // reconstituée par les coins arrondis de la première et de la dernière ligne.
                itemsIndexed(list, key = { _, album -> album.id }) { index, album ->
                    val radius = OneUiTheme.dimens.cardRadius
                    val shape = RoundedCornerShape(
                        topStart = if (index == 0) radius else 0.dp,
                        topEnd = if (index == 0) radius else 0.dp,
                        bottomStart = if (index == list.lastIndex) radius else 0.dp,
                        bottomEnd = if (index == list.lastIndex) radius else 0.dp,
                    )
                    Column(Modifier.clip(shape).background(colors.card)) {
                        if (index > 0) OneUiDivider(startIndent = 76.dp)
                        AlbumVisibilityRow(album = album, onClick = { editingId = album.id })
                    }
                }
            }
        }
    }

    val editing = albums?.firstOrNull { it.id == editingId }
    if (editing != null) {
        VisibilityDialog(
            album = editing,
            onSelect = { visibility ->
                editingId = null
                viewModel.setVisibility(editing.id, visibility)
            },
            onDismiss = { editingId = null },
        )
    }
}

@Composable
private fun AlbumVisibilityRow(album: AlbumSummary, onClick: () -> Unit) {
    val colors = OneUiTheme.colors
    val hidden = album.visibility != AlbumVisibility.SHOWN
    val stateLabel = stringResource(VisibilityLabels.first { it.first == album.visibility }.second)
    val count = pluralStringResource(R.plurals.common_items_count, album.mediaCount, album.mediaCount)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 72.dp)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(colors.placeholder)
                .alpha(if (hidden) 0.45f else 1f),
        ) {
            if (album.coverUri != null || album.coverCloud != null) {
                ThumbnailImage(
                    uri = album.coverUri,
                    isVideo = album.coverType == MediaType.VIDEO,
                    version = album.coverDateModified,
                    modifier = Modifier.fillMaxSize(),
                    cloud = album.coverCloud,
                )
            } else {
                Icon(
                    imageVector = Icons.Outlined.PhotoAlbum,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(24.dp),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = album.name,
                style = OneUiTheme.typography.listTitle,
                color = if (hidden) colors.textSecondary else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$count · $stateLabel",
                style = OneUiTheme.typography.listSubtitle,
                color = if (hidden) colors.accent else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VisibilityDialog(album: AlbumSummary, onSelect: (AlbumVisibility) -> Unit, onDismiss: () -> Unit) {
    val colors = OneUiTheme.colors
    OneUiDialog(
        title = album.name,
        onDismiss = onDismiss,
        confirmLabel = stringResource(R.string.common_cancel),
        onConfirm = onDismiss,
        content = {
            Column(Modifier.fillMaxWidth().selectableGroup()) {
                VisibilityLabels.forEach { (visibility, label) ->
                    val selected = visibility == album.visibility
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .selectable(selected = selected, role = Role.RadioButton) { onSelect(visibility) },
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
