package fr.eva.cloudgallery.ui.albums

import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.domain.model.CloudThumbRef
import fr.eva.cloudgallery.ui.components.OneUiCheckCircle
import fr.eva.cloudgallery.ui.image.ThumbnailImage
import fr.eva.cloudgallery.ui.theme.OneUiTheme

/** Petit badge rond affiché sur une couverture (cloud, album personnalisé, épinglé). */
@Immutable
internal data class AlbumBadge(val icon: ImageVector, val contentDescription: String)

/**
 * Cellule de la grille d'albums façon One UI : couverture carrée arrondie, nom sur une ligne,
 * nombre d'éléments en dessous. Sans couverture, affiche [emptyIcon] sur le fond de chargement.
 * Couverture : fichier local ([coverUri]) ou, à défaut, miniature OneDrive ([coverCloud]).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AlbumGridCell(
    name: String,
    countText: String,
    coverUri: Uri?,
    coverCloud: CloudThumbRef?,
    coverIsVideo: Boolean,
    coverVersion: Long,
    emptyIcon: ImageVector,
    badges: List<AlbumBadge>,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val colors = OneUiTheme.colors
    val typography = OneUiTheme.typography
    val haptics = LocalHapticFeedback.current
    val shape = RoundedCornerShape(OneUiTheme.dimens.albumCoverRadius)
    Column(
        modifier = modifier
            .clip(shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick?.let { longClick ->
                    {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        longClick()
                    }
                },
            )
            .semantics(mergeDescendants = true) { if (selectionMode) this.selected = selected }
            .padding(bottom = 4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(shape)
                .background(colors.placeholder),
        ) {
            if (coverUri != null || coverCloud != null) {
                ThumbnailImage(
                    uri = coverUri,
                    isVideo = coverIsVideo,
                    version = coverVersion,
                    modifier = Modifier.fillMaxSize(),
                    cloud = coverCloud,
                )
            } else {
                Icon(
                    imageVector = emptyIcon,
                    contentDescription = null,
                    tint = colors.textTertiary,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp),
                )
            }
            if (selectionMode && selected) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(colors.selectionOverlay),
                )
            }
            if (badges.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    badges.forEach { CoverBadge(it) }
                }
            }
            if (selectionMode) {
                OneUiCheckCircle(
                    checked = selected,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = name,
            style = typography.albumName,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
        Text(
            text = countText,
            style = typography.albumCount,
            color = colors.textSecondary,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 2.dp),
        )
    }
}

@Composable
private fun CoverBadge(badge: AlbumBadge) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .clip(CircleShape)
            .background(Color.Black.copy(alpha = 0.45f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = badge.icon,
            contentDescription = badge.contentDescription,
            tint = Color.White,
            modifier = Modifier.size(14.dp),
        )
    }
}
