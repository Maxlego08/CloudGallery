package fr.eva.cloudgallery.ui.image

import android.net.Uri
import android.os.CancellationSignal
import android.os.OperationCanceledException
import android.util.Size
import coil3.ImageLoader
import coil3.asImage
import coil3.decode.DataSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.ImageFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Coil model for a MediaStore thumbnail. [version] (dateModified) invalidates the memory
 * cache after a modification of the file (rotation, crop…).
 */
data class LocalThumbnail(
    val uri: Uri,
    val isVideo: Boolean,
    val version: Long,
)

/** Default size when the requested size is unknown / original. */
internal const val DEFAULT_THUMBNAIL_PX = 384

/**
 * Rounds the requested size to a step, so that small variations in cell size
 * (grid zoom) reuse the same cache entry.
 */
internal fun thumbnailBucket(options: Options): Int {
    val w = options.size.width.pxOrElse { DEFAULT_THUMBNAIL_PX }
    val h = options.size.height.pxOrElse { DEFAULT_THUMBNAIL_PX }
    val side = maxOf(w, h).coerceAtLeast(1)
    return BUCKETS.firstOrNull { it >= side } ?: BUCKETS.last()
}

private val BUCKETS = intArrayOf(96, 192, 256, 384, 512, 768, 1024)

/** Memory cache key: uri + version + size step. */
class LocalThumbnailKeyer : Keyer<LocalThumbnail> {
    override fun key(data: LocalThumbnail, options: Options): String =
        "thumb:${data.uri}:${data.version}:${thumbnailBucket(options)}"
}

/**
 * Loads the thumbnail via [android.content.ContentResolver.loadThumbnail]: MediaStore caches
 * it itself, so there is no Coil disk cache for these requests.
 */
class LocalThumbnailFetcher(
    private val data: LocalThumbnail,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val px = thumbnailBucket(options)
        val signal = CancellationSignal()
        try {
            val bitmap = coroutineScope {
                // Cancel as soon as the request's cancellation STARTS (cell scrolled off screen):
                // the child is cancelled immediately and interrupts loadThumbnail via the signal. An
                // invokeOnCompletion would only run after the blocking call returns.
                val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
                    try {
                        awaitCancellation()
                    } finally {
                        signal.cancel()
                    }
                }
                try {
                    withContext(Dispatchers.IO) {
                        options.context.contentResolver.loadThumbnail(data.uri, Size(px, px), signal)
                    }
                } finally {
                    watcher.cancel()
                }
            }
            // (image, isSampled, dataSource)
            return ImageFetchResult(bitmap.asImage(), true, DataSource.DISK)
        } catch (e: OperationCanceledException) {
            throw CancellationException("Thumbnail loading cancelled").apply { initCause(e) }
        }
    }

    class Factory : Fetcher.Factory<LocalThumbnail> {
        override fun create(data: LocalThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            LocalThumbnailFetcher(data, options)
    }
}
