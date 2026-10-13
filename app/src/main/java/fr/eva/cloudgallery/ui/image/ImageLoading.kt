package fr.eva.cloudgallery.ui.image

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Size as CoilSize
import coil3.video.VideoFrameDecoder
import fr.eva.cloudgallery.domain.model.CloudThumbRef
import fr.eva.cloudgallery.domain.model.CloudThumbnail
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.OfflineThumbnail
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import okio.Path.Companion.toOkioPath

/**
 * The application's single ImageLoader (provided by CloudGalleryApp via SingletonImageLoader.Factory).
 * - memory cache ≈ 25% of the app's memory;
 * - disk cache bounded to [diskCacheMaxBytes] (setting read when the cache is opened, on
 *   Coil's I/O dispatcher): OneDrive thumbnails and previews only (local thumbnails
 *   and local full-screen images do not go through it);
 * - MediaStore thumbnails via [LocalThumbnailFetcher]; full video frames via VideoFrameDecoder;
 * - [extraComponents]: components provided by the data layer (OneDrive thumbnails, the
 *   [CloudThumbnail] model).
 * Construction is lazy: no cache is opened before the first load.
 */
fun buildAppImageLoader(
    context: Context,
    diskCacheMaxBytes: () -> Long,
    extraComponents: ComponentRegistry.Builder.() -> Unit = {},
): ImageLoader =
    ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("coil").toOkioPath())
                .maxSizeBytes(diskCacheMaxBytes())
                .build()
        }
        .components {
            add(LocalThumbnailKeyer(), LocalThumbnail::class)
            add(LocalThumbnailFetcher.Factory(), LocalThumbnail::class)
            add(VideoFrameDecoder.Factory())
            extraComponents()
        }
        .build()

/**
 * Square thumbnail for grids and album covers. Draws the
 * `OneUiTheme.colors.placeholder` color (if [placeholder]) on error or without a source, animated
 * while loading ([LoadingSkeleton]).
 * Local file ([uri], always preferred) via MediaStore, otherwise OneDrive thumbnail ([cloud]),
 * otherwise (or if the OneDrive thumbnail is unavailable, offline for instance) an image taken from
 * the offline copy ([offline]). [version] (dateModified) invalidates the local cache after a
 * modification; the OneDrive thumbnail is versioned by its cTag.
 */
@Composable
fun ThumbnailImage(
    uri: Uri?,
    isVideo: Boolean,
    version: Long,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    cloud: CloudThumbRef? = null,
    offline: OfflineThumbnail? = null,
    /** False in the viewer: black (transparent) background instead of the placeholder color. */
    placeholder: Boolean = true,
) {
    Box(if (placeholder) modifier.background(OneUiTheme.colors.placeholder) else modifier) {
        // OneDrive thumbnail failed: fall back to the offline copy (if there is one).
        var cloudFailed by remember(cloud) { mutableStateOf(false) }
        val model: Any? = remember(uri, isVideo, version, cloud, offline, cloudFailed) {
            when {
                uri != null -> LocalThumbnail(uri, isVideo, version)
                cloud != null && !(cloudFailed && offline != null) -> CloudThumbnail(cloud)
                offline != null -> offline
                else -> null
            }
        }
        if (model != null) {
            // Loading: animated background (LoadingSkeleton); loaded or failed: static background.
            var settled by remember(model) { mutableStateOf(false) }
            if (placeholder && !settled) LoadingSkeleton(Modifier.matchParentSize())
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { settled = true },
                onError = {
                    settled = true
                    if (model is CloudThumbnail && offline != null) cloudFailed = true
                },
            )
        }
    }
}

/**
 * Full-screen image request for the viewer, bounded to [maxSidePx] (longest side).
 * The memory cache key includes [version] to reflect file modifications
 * (dateModified of a local file, cTag of a downloaded OneDrive original).
 *
 * Coil caps every decode at 4096 x 4096 by default (maxBitmapSize): beyond that, the cap is
 * raised to [maxSidePx], otherwise the high-resolution reload would bring no extra detail.
 */
fun fullImageRequest(context: Context, uri: Uri, version: String, maxSidePx: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(uri)
        .size(maxSidePx)
        .apply { if (maxSidePx > COIL_DEFAULT_MAX_BITMAP_SIDE) maxBitmapSize(CoilSize(maxSidePx, maxSidePx)) }
        .precision(Precision.INEXACT)
        .memoryCacheKey("full:$uri:$version:$maxSidePx")
        // Files already on the device (content:// or downloaded original): no need to
        // duplicate them in the disk cache.
        .diskCachePolicy(CachePolicy.DISABLED)
        .build()

/** Default side of large OneDrive previews (see CloudThumbnailLogic). */
const val DEFAULT_CLOUD_PREVIEW_SIDE = 1600

/**
 * Large preview of an item present only on OneDrive (viewer), with its original
 * proportions, fitting in a square of [side] px. [onError]: true if the preview could not be
 * loaded (offline without cache, expired session, no thumbnail). [onLoaded]: size of
 * the loaded image (px). [cacheOnly]: only from the cache (memory or disk), never a
 * Microsoft Graph request.
 */
@Composable
fun CloudPreviewImage(
    ref: CloudThumbRef,
    modifier: Modifier = Modifier,
    side: Int = DEFAULT_CLOUD_PREVIEW_SIDE,
    contentDescription: String? = null,
    cacheOnly: Boolean = false,
    onError: (Boolean) -> Unit = {},
    onLoaded: (Size) -> Unit = {},
) {
    val context = LocalContext.current
    val model: Any = remember(ref, side, cacheOnly) {
        val data = CloudThumbnail(ref, previewSide = side)
        if (cacheOnly) {
            ImageRequest.Builder(context).data(data).networkCachePolicy(CachePolicy.DISABLED).build()
        } else {
            data
        }
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        onState = { state ->
            onError(state is AsyncImagePainter.State.Error)
            if (state is AsyncImagePainter.State.Success) {
                val intrinsic = state.painter.intrinsicSize
                if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) onLoaded(intrinsic)
            }
        },
        modifier = modifier,
    )
}

/** Coil's default cap on the size of a decoded bitmap (Extras maxBitmapSize). */
private const val COIL_DEFAULT_MAX_BITMAP_SIDE = 4096

/** Fallback thumbnail taken from the offline copy of an item kept offline, otherwise null. */
fun MediaSummary.offlineThumbnail(): OfflineThumbnail? =
    if (hasOffline) OfflineThumbnail(id, cloudThumb?.version ?: "m$dateModified", mimeType) else null

/** Like [MediaSummary.offlineThumbnail], for the viewer (version = that of the copy). */
fun MediaDetails.offlineThumbnail(): OfflineThumbnail? =
    offlineCopy?.let { OfflineThumbnail(id, it.version ?: "m$dateModified", mimeType) }
