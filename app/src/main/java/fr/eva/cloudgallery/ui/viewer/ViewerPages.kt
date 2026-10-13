package fr.eva.cloudgallery.ui.viewer

import android.net.Uri
import android.text.format.Formatter
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.outlined.BrokenImage
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.OfflinePin
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.data.cloud.thumb.CLOUD_PREVIEW_PX
import fr.eva.cloudgallery.data.cloud.thumb.previewSideFor
import fr.eva.cloudgallery.domain.model.DownloadError
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaType
import fr.eva.cloudgallery.ui.image.CloudPreviewImage
import fr.eva.cloudgallery.ui.image.ThumbnailImage
import fr.eva.cloudgallery.ui.image.offlineThumbnail
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import fr.eva.cloudgallery.ui.theme.ViewerColors
import fr.eva.cloudgallery.ui.util.Formatters
import fr.eva.cloudgallery.ui.util.downloadErrorMessage
import fr.eva.cloudgallery.ui.util.progressPercent
import kotlin.math.max

/**
 * One pager page: zoomable image, video (poster + play button) or media without a local file.
 * Source order: local file, offline copy, then OneDrive (preview, original).
 */
@Composable
internal fun ViewerPage(
    media: MediaDetails?,
    isCurrentPage: Boolean,
    callbacks: PageGestureCallbacks,
    onZoomChanged: (Boolean) -> Unit,
    onPlayVideo: (Long) -> Unit,
    cloudOriginal: CloudOriginalState,
    /** Metered network; null until the actual state is known. */
    costlyNetwork: Boolean?,
    onHiResWanted: (mediaId: Long, wanted: Boolean, requestedLongPx: Int, previewSide: Int) -> Unit,
    onLoadOriginal: (Long) -> Unit,
    onCancelOriginal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (media == null) {
        // Loading metadata (instant from Room): black background.
        Box(modifier.fillMaxSize())
        return
    }
    val uri = media.uri?.takeIf { media.hasLocal }
    val offline = media.offlineCopy
    when {
        uri == null && offline != null && media.type == MediaType.IMAGE -> OfflineImagePage(
            media = media,
            fileUri = remember(offline.file) { Uri.fromFile(offline.file) },
            versionKey = offline.version ?: "o${offline.sizeBytes}",
            isCurrentPage = isCurrentPage,
            callbacks = callbacks,
            onZoomChanged = onZoomChanged,
            modifier = modifier,
        )
        uri == null && media.type == MediaType.VIDEO -> CloudVideoPage(
            media = media,
            isCurrentPage = isCurrentPage,
            callbacks = callbacks,
            onPlay = { onPlayVideo(media.id) },
            modifier = modifier,
        )
        uri == null -> CloudImagePage(
            media = media,
            original = cloudOriginal.takeIf { it.mediaId == media.id }?.ui ?: CloudOriginalUi.Idle,
            isCurrentPage = isCurrentPage,
            costlyNetwork = costlyNetwork,
            callbacks = callbacks,
            onZoomChanged = onZoomChanged,
            onHiResWanted = onHiResWanted,
            onLoadOriginal = { onLoadOriginal(media.id) },
            onCancelOriginal = onCancelOriginal,
            modifier = modifier,
        )
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
                    source = ViewerImageSource.MediaStore(uri, media.dateModified),
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

/**
 * Image kept offline: the copy (app-owned file) is displayed and zoomed like a local file, with
 * the OneDrive preview as a placeholder only if it is already cached (never a network request
 * for an item present on the device).
 */
@Composable
private fun OfflineImagePage(
    media: MediaDetails,
    fileUri: Uri,
    versionKey: String,
    isCurrentPage: Boolean,
    callbacks: PageGestureCallbacks,
    onZoomChanged: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    var loadError by remember(fileUri, versionKey) { mutableStateOf(false) }
    Box(modifier.fillMaxSize()) {
        ZoomableImage(
            media = media,
            source = ViewerImageSource.File(fileUri, versionKey, media.cloudThumb, CLOUD_PREVIEW_PX, previewFromCacheOnly = true),
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
            placeholder = false,
        )
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PlayButton(label = stringResource(R.string.viewer_play), onPlay = onPlay)
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

/** Round play button in the center of a video page. */
@Composable
private fun PlayButton(label: String, onPlay: () -> Unit) {
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(CircleShape)
            .background(Color(0x66000000))
            .clickable(role = Role.Button, onClickLabel = label, onClick = onPlay),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier.size(36.dp),
        )
    }
}

/**
 * Image present only on OneDrive: large zoomable Microsoft Graph preview (tier chosen based on
 * the screen, the image and the network), replaced by the original once downloaded (deep zoom:
 * automatic on an unmetered network, offered otherwise). Preview unavailable (offline without
 * cache, expired session…): explanatory message.
 */
@Composable
private fun CloudImagePage(
    media: MediaDetails,
    original: CloudOriginalUi,
    isCurrentPage: Boolean,
    costlyNetwork: Boolean?,
    callbacks: PageGestureCallbacks,
    onZoomChanged: (Boolean) -> Unit,
    onHiResWanted: (mediaId: Long, wanted: Boolean, requestedLongPx: Int, previewSide: Int) -> Unit,
    onLoadOriginal: () -> Unit,
    onCancelOriginal: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ref = media.cloudThumb
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // Tier fixed as soon as the actual network state is known (no reload if it changes later).
    val networkKnown = costlyNetwork != null
    val previewSide: Int? = remember(media.id, ref, networkKnown) {
        val viewportLongPx = (max(configuration.screenWidthDp, configuration.screenHeightDp) * density.density).toInt()
        costlyNetwork?.let { previewSideFor(viewportLongPx, it, imageLongSide(media)) }
    }
    var previewFailed by remember(ref, previewSide) { mutableStateOf(false) }
    val source: ViewerImageSource? = when {
        original is CloudOriginalUi.Ready -> ViewerImageSource.File(original.fileUri, original.versionKey, ref, previewSide ?: CLOUD_PREVIEW_PX)
        ref != null && previewSide != null -> ViewerImageSource.CloudPreview(ref, previewSide)
        else -> null
    }
    // The original only adds detail if it is larger than the preview (unknown size: yes).
    val originalLarger = previewSide == null || (imageLongSide(media)?.let { it > previewSide } ?: true)
    Box(modifier.fillMaxSize()) {
        if (source != null) {
            ZoomableImage(
                media = media,
                source = source,
                isCurrentPage = isCurrentPage,
                callbacks = callbacks,
                onZoomChanged = onZoomChanged,
                onLoadError = { previewFailed = it },
                onHiResWanted = { wanted, requested -> onHiResWanted(media.id, wanted, requested, previewSide ?: CLOUD_PREVIEW_PX) },
            )
        } else {
            // Neither preview nor original: page gestures (close, details) remain available.
            val scope = rememberCoroutineScope()
            val state = remember(media.id) { ZoomState(scope) }
            ZoomableBox(state = state, zoomEnabled = false, callbacks = callbacks) {}
        }
        val ready = original is CloudOriginalUi.Ready
        // No preview possible (no thumbnail, failure): message. Network not known yet: wait.
        if (!ready && (ref == null || previewFailed)) {
            PageMessage(
                icon = { Icon(Icons.Outlined.CloudOff, contentDescription = null, tint = ViewerColors.content, modifier = Modifier.size(40.dp)) },
                title = stringResource(R.string.viewer_cloud_only_title),
                message = stringResource(R.string.viewer_cloud_only_message),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (!ready && isCurrentPage) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .padding(top = 72.dp, start = 24.dp, end = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!previewFailed && source != null) CloudPreviewCaption(media = media, zoomForOriginal = originalLarger)
                OriginalStatusChip(
                    original = original,
                    onLoad = onLoadOriginal,
                    onCancel = onCancelOriginal,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

/**
 * State of the original below the "preview" label: offer to load it with its size (metered
 * network), progress with cancel, or failure with retry.
 */
@Composable
private fun OriginalStatusChip(
    original: CloudOriginalUi,
    onLoad: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val pill = Modifier
        .clip(RoundedCornerShape(50))
        .background(Color(0x99000000))
    when (original) {
        is CloudOriginalUi.NeedsConfirmation -> {
            val label = if (original.bytes > 0) {
                stringResource(R.string.viewer_cloud_original_load, Formatter.formatShortFileSize(context, original.bytes))
            } else {
                stringResource(R.string.viewer_cloud_original_load_unknown)
            }
            Row(
                modifier = modifier
                    .then(pill)
                    .clickable(role = Role.Button, onClick = onLoad)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.Download, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(text = label, style = OneUiTheme.typography.badge, color = Color.White)
            }
        }
        is CloudOriginalUi.Downloading -> {
            val percent = progressPercent(original.done, original.total)
            val description = if (percent >= 0) {
                stringResource(R.string.viewer_cloud_original_downloading_percent, percent)
            } else {
                stringResource(R.string.viewer_cloud_original_downloading)
            }
            Row(
                modifier = modifier
                    .then(pill)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.common_cancel), onClick = onCancel)
                    .semantics { contentDescription = description }
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (percent >= 0) {
                    CircularProgressIndicator(
                        progress = { percent / 100f },
                        color = Color.White,
                        trackColor = Color(0x55FFFFFF),
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(16.dp),
                    )
                } else {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(text = description, style = OneUiTheme.typography.badge, color = Color.White)
                Spacer(Modifier.width(10.dp))
                Icon(Icons.Filled.Close, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
        }
        is CloudOriginalUi.Failed -> {
            if (original.error == DownloadError.CANCELLED) return
            Row(
                modifier = modifier
                    .then(pill)
                    .clickable(role = Role.Button, onClickLabel = stringResource(R.string.common_retry), onClick = onLoad)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Outlined.ErrorOutline, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.viewer_cloud_original_failed, stringResource(downloadErrorMessage(original.error))),
                    style = OneUiTheme.typography.badge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                )
            }
        }
        CloudOriginalUi.Idle, is CloudOriginalUi.Ready -> Unit
    }
}

/**
 * Video without a local file: large Microsoft Graph preview (no zoom), or a frame taken from the
 * offline copy, play button (offline copy, otherwise streaming from OneDrive), even without a
 * preview, and a duration badge.
 */
@Composable
private fun CloudVideoPage(
    media: MediaDetails,
    isCurrentPage: Boolean,
    callbacks: PageGestureCallbacks,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val state = remember { ZoomState(scope) }
    LaunchedEffect(isCurrentPage) { if (!isCurrentPage) state.reset() }
    val ref = media.cloudThumb
    val offline = remember(media.id, media.offlineCopy) { media.offlineThumbnail() }
    ZoomableBox(state = state, zoomEnabled = false, callbacks = callbacks, modifier = modifier) {
        if (ref != null) {
            CloudPreviewImage(ref = ref, modifier = Modifier.matchParentSize())
        } else if (offline != null) {
            ThumbnailImage(
                uri = null,
                isVideo = true,
                version = media.dateModified,
                offline = offline,
                contentScale = ContentScale.Fit,
                placeholder = false,
                modifier = Modifier.matchParentSize(),
            )
        }
        // Playback does not depend on the preview (Graph does not always generate a video thumbnail).
        Box(Modifier.align(Alignment.Center)) {
            PlayButton(
                label = stringResource(if (media.offlineCopy != null) R.string.viewer_play else R.string.viewer_play_cloud_video),
                onPlay = onPlay,
            )
        }
        CloudPreviewCaption(
            media = media,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(top = 72.dp, start = 24.dp, end = 24.dp),
        )
    }
}

/**
 * "OneDrive preview" label or, for a video, "streaming playback" and a badge with its duration.
 * [zoomForOriginal]: the prompt to zoom in for the original only appears if it is larger than
 * the preview.
 */
@Composable
private fun CloudPreviewCaption(media: MediaDetails, modifier: Modifier = Modifier, zoomForOriginal: Boolean = true) {
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
        val offline = media.offlineCopy != null
        Row(pill, verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (offline) Icons.Outlined.OfflinePin else Icons.Outlined.Cloud,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(
                    when {
                        offline && !media.hasCloud -> R.string.offline_caption_deleted
                        offline -> R.string.offline_caption
                        media.type == MediaType.VIDEO -> R.string.viewer_cloud_video_caption
                        zoomForOriginal -> R.string.viewer_cloud_only_preview
                        else -> R.string.viewer_cloud_preview_plain
                    },
                ),
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
