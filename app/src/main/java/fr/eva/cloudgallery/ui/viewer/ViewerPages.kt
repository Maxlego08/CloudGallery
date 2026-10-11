package fr.eva.cloudgallery.ui.viewer

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.ui.image.CloudPreviewImage
import fr.eva.cloudgallery.ui.image.ThumbnailImage
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import fr.eva.cloudgallery.ui.theme.ViewerColors
import fr.eva.cloudgallery.ui.util.Formatters

/** Une page du pager : image zoomable, vidéo (affiche + bouton lecture) ou média uniquement distant. */
@Composable
internal fun ViewerPage(
    media: MediaDetails?,
    isCurrentPage: Boolean,
    callbacks: PageGestureCallbacks,
    onZoomChanged: (Boolean) -> Unit,
    onPlayVideo: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (media == null) {
        // Chargement des métadonnées (instantané depuis Room) : fond noir.
        Box(modifier.fillMaxSize())
        return
    }
    val uri = media.uri?.takeIf { media.hasLocal }
    when {
        uri == null -> CloudOnlyPage(media = media, isCurrentPage = isCurrentPage, callbacks = callbacks, modifier = modifier)
        media.type == MediaType.VIDEO -> VideoPage(
            media = media,
            uri = uri,
            isCurrentPage = isCurrentPage,
            callbacks = callbacks,
            onPlay = { onPlayVideo(media.id) },
            modifier = modifier,
        )
        else -> {
            var loadError by remember(uri, media.dateModified) { mutableStateOf(false) }
            Box(modifier.fillMaxSize()) {
                ZoomableImage(
                    media = media,
                    uri = uri,
                    isCurrentPage = isCurrentPage,
                    callbacks = callbacks,
                    onZoomChanged = onZoomChanged,
                    onLoadError = { loadError = it },
                )
                if (loadError) {
                    PageMessage(
                        icon = { Icon(Icons.Outlined.BrokenImage, contentDescription = null, tint = ViewerColors.content, modifier = Modifier.size(40.dp)) },
                        title = stringResource(R.string.viewer_load_error),
                        message = null,
                        modifier = Modifier.align(Alignment.Center),
                    )
                }
            }
        }
    }
}

@Composable
private fun VideoPage(
    media: MediaDetails,
    uri: Uri,
    isCurrentPage: Boolean,
    callbacks: PageGestureCallbacks,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state = remember { ZoomState(scope) }
    val swap = media.orientation % 180 != 0
    SideEffect {
        state.contentW = (if (swap) media.height else media.width).toFloat()
        state.contentH = (if (swap) media.width else media.height).toFloat()
    }
    LaunchedEffect(isCurrentPage) { if (!isCurrentPage) state.reset() }

    ZoomableBox(state = state, zoomEnabled = false, callbacks = callbacks, modifier = modifier) {
        ThumbnailImage(
            uri = uri,
            isVideo = true,
            version = media.dateModified,
            modifier = Modifier.matchParentSize(),
            contentScale = ContentScale.Fit,
        )
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val playLabel = stringResource(R.string.viewer_play)
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(Color(0x66000000))
                    .clickable(role = Role.Button, onClickLabel = playLabel, onClick = onPlay),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = playLabel,
                    tint = Color.White,
                    modifier = Modifier.size(36.dp),
                )
            }
            if (media.durationMs > 0) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = Formatters.duration(media.durationMs),
                    style = OneUiTheme.typography.badge,
                    color = Color.White,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color(0x66000000))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/**
 * Média présent uniquement sur OneDrive : grand aperçu Microsoft Graph aux proportions d'origine
 * (sans zoom), mention « aperçu » et, pour une vidéo, un badge (durée) sans bouton de lecture.
 * Aperçu indisponible (hors connexion sans cache, session expirée…) : message explicatif.
 */
@Composable
private fun CloudOnlyPage(
    media: MediaDetails,
    isCurrentPage: Boolean,
    callbacks: PageGestureCallbacks,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state = remember { ZoomState(scope) }
    LaunchedEffect(isCurrentPage) { if (!isCurrentPage) state.reset() }
    val ref = media.cloudThumb
    var previewFailed by remember(ref) { mutableStateOf(false) }
    // TODO(phase 4): original OneDrive (téléchargement à la demande, zoom, lecture vidéo en streaming).
    ZoomableBox(state = state, zoomEnabled = false, callbacks = callbacks, modifier = modifier) {
        if (ref != null) {
            CloudPreviewImage(
                ref = ref,
                modifier = Modifier.matchParentSize(),
                onError = { previewFailed = it },
            )
        }
        if (ref == null || previewFailed) {
            PageMessage(
                icon = { Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = ViewerColors.content, modifier = Modifier.size(40.dp)) },
                title = stringResource(R.string.viewer_cloud_only_title),
                message = stringResource(R.string.viewer_cloud_only_message),
                modifier = Modifier.align(Alignment.Center),
            )
        } else {
            CloudPreviewCaption(
                media = media,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 72.dp, start = 24.dp, end = 24.dp),
            )
        }
    }
}

/** Mention « aperçu OneDrive » et, pour une vidéo, badge avec sa durée (aucune action). */
@Composable
private fun CloudPreviewCaption(media: MediaDetails, modifier: Modifier = Modifier) {
    val pill = Modifier
        .clip(RoundedCornerShape(50))
        .background(Color(0x66000000))
        .padding(horizontal = 10.dp, vertical = 4.dp)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (media.type == MediaType.VIDEO) {
            Row(pill, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Videocam, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (media.durationMs > 0) Formatters.duration(media.durationMs) else stringResource(R.string.common_video),
                    style = OneUiTheme.typography.badge,
                    color = Color.White,
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        Row(pill, verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Cloud, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.viewer_cloud_only_preview),
                style = OneUiTheme.typography.badge,
                color = Color.White,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PageMessage(
    icon: @Composable () -> Unit,
    title: String,
    message: String?,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = OneUiTheme.typography.listTitle,
            color = ViewerColors.content,
            textAlign = TextAlign.Center,
        )
        if (message != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                text = message,
                style = OneUiTheme.typography.listSubtitle,
                color = ViewerColors.contentSecondary,
                textAlign = TextAlign.Center,
            )
        }
    }
}
