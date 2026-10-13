package fr.eva.cloudgallery.data.cloud.thumb

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.disk.DiskCache
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import coil3.size.pxOrElse
import fr.eva.cloudgallery.data.cloud.CloudAccessGuard
import fr.eva.cloudgallery.data.cloud.CloudDataGeneration
import fr.eva.cloudgallery.data.cloud.graph.GraphApi
import fr.eva.cloudgallery.data.cloud.graph.GraphHttpException
import fr.eva.cloudgallery.data.cloud.precache.CloudPrecacheStore
import fr.eva.cloudgallery.data.cloud.precache.PrecacheKind
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.CloudAuthRequiredException
import fr.eva.cloudgallery.domain.model.CloudThumbnail
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.BufferedSource
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** No thumbnail for this item (Graph does not generate one, or not found recently). */
class CloudThumbnailUnavailableException : IOException("OneDrive thumbnail unavailable")

/** Size bucket of a Coil request (longest requested side). */
private fun bucketOf(options: Options): Int {
    val w = options.size.width.pxOrElse { DEFAULT_CLOUD_THUMB_PX }
    val h = options.size.height.pxOrElse { DEFAULT_CLOUD_THUMB_PX }
    return cloudThumbBucket(maxOf(w, h))
}

/**
 * Memory cache key: item + cTag + bucket (or preview bucket actually requested, after a
 * possible fallback: same key as the disk cache).
 */
class CloudThumbnailKeyer internal constructor(private val env: CloudThumbnailFetcher.Factory) : Keyer<CloudThumbnail> {
    override fun key(data: CloudThumbnail, options: Options): String =
        cloudThumbKey(data.ref, bucketOf(options), env.effectivePreviewSide(data.previewSide))
}

/**
 * Loads a Microsoft Graph thumbnail (`/thumbnails/0/{size}/content`):
 * 1. ImageLoader disk cache (key = item + cTag + bucket: works offline);
 * 2. caching of all of OneDrive ([CloudPrecacheStore]) at an equal or larger size;
 * 3. otherwise, only for a signed-in account and if the request allows the network
 *    (`networkCachePolicy`), download (at most 4 in parallel, global Graph pause honored)
 *    then write to the disk cache; on failure, a smaller size from the caching of all of
 *    OneDrive if there is one.
 * All work (disk, network, MSAL) happens on the I/O dispatcher.
 */
class CloudThumbnailFetcher private constructor(
    private val data: CloudThumbnail,
    private val options: Options,
    private val imageLoader: ImageLoader,
    private val env: Factory,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(env.io) {
        // Read on the I/O dispatcher: the first read creates the disk cache (directory).
        val diskCache = imageLoader.diskCache
        val bucket = bucketOf(options)
        val previewSide = env.effectivePreviewSide(data.previewSide)
        val key = cloudThumbKey(data.ref, bucket, previewSide)
        if (options.diskCachePolicy.readEnabled && diskCache != null) {
            diskCache.openSnapshot(key)?.let { return@withContext it.toResult(diskCache, key, null, DataSource.DISK) }
        }
        // Caching of all of OneDrive: equal or larger size first (no loss of sharpness).
        precached(bucket, previewSide, allowLower = false)?.let { return@withContext it }
        try {
            fetchFromNetwork(diskCache, bucket, previewSide, key)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Offline, account unavailable, thumbnail not found: a smaller cached size is
            // better than an empty cell.
            precached(bucket, previewSide, allowLower = true) ?: throw e
        }
    }

    /** File from the caching of all of OneDrive for this request, null if there is none. */
    private fun precached(bucket: Int, previewSide: Int?, allowLower: Boolean): FetchResult? {
        val kind = if (previewSide != null) PrecacheKind.PREVIEW else PrecacheKind.GRID
        val file = env.precache.find(data.ref, kind, previewSide ?: bucket, allowLower) ?: return null
        return SourceFetchResult(ImageSource(file.toOkioPath(), FileSystem.SYSTEM), null, DataSource.DISK)
    }

    private suspend fun fetchFromNetwork(diskCache: DiskCache?, bucket: Int, previewSide: Int?, key: String): FetchResult {
        // Request limited to the cache (offline copy displayed): never a download.
        if (!options.networkCachePolicy.readEnabled) throw CloudThumbnailUnavailableException()
        val generation = env.generation.value
        if (env.isUnavailable(key, generation)) throw CloudThumbnailUnavailableException()
        env.ensureSignedIn()
        val size = cloudThumbSize(bucket, previewSide)
        return env.permits.withPermit {
            val path = effectiveThumbPath(size, env.customSizesRejected)
            try {
                download(path, key, generation, diskCache)
            } catch (e: GraphHttpException) {
                val fallback = size.fallback
                if (fallback == null || path == fallback || !shouldTryFallbackSize(e.status)) {
                    if (e.status == HTTP_NOT_FOUND) env.markUnavailable(key, generation)
                    throw e
                }
                try {
                    download(fallback, key, generation, diskCache).also {
                        if (previewSide != null && previewSide > CLOUD_PREVIEW_PX) {
                            // Large preview bucket refused, lower bucket accepted: dropped
                            // until restart (keys follow the effective bucket).
                            env.maxPreviewSide = CLOUD_PREVIEW_PX
                        } else if (e.status == HTTP_BAD_REQUEST) {
                            // Custom size refused but standard size accepted: no more double request.
                            env.customSizesRejected = true
                        }
                    }
                } catch (e2: GraphHttpException) {
                    if (e2.status == HTTP_NOT_FOUND) env.markUnavailable(key, generation)
                    throw e2
                }
            }
        }
    }

    private suspend fun download(path: String, key: String, generation: Long, diskCache: DiskCache?): FetchResult =
        env.graph.get().thumbnailContent(data.ref.itemId, path) { body, mimeType ->
            val cache = diskCache?.takeIf { options.diskCachePolicy.writeEnabled }
            val editor = cache?.openEditor(key)
            if (cache != null && editor != null) {
                writeToCache(cache, editor, body, key, mimeType, generation)
            } else {
                // Write already in progress for this key (or cache disabled): read in memory.
                val buffer = Buffer().apply { writeAll(body) }
                checkGeneration(generation)
                SourceFetchResult(ImageSource(buffer, options.fileSystem), mimeType, DataSource.NETWORK)
            }
        }

    private fun writeToCache(
        cache: DiskCache,
        editor: DiskCache.Editor,
        body: BufferedSource,
        key: String,
        mimeType: String?,
        generation: Long,
    ): FetchResult {
        try {
            cache.fileSystem.write(editor.data) { writeAll(body) }
            checkGeneration(generation)
            val snapshot = editor.commitAndOpenSnapshot() ?: throw IOException("Thumbnail missing from the disk cache")
            return snapshot.toResult(cache, key, mimeType, DataSource.NETWORK)
        } catch (e: Throwable) {
            try {
                editor.abort()
            } catch (_: Exception) {
                // Editor already committed or closed.
            }
            throw e
        }
    }

    /** OneDrive data removed during the download (sign-out): nothing is kept. */
    private fun checkGeneration(generation: Long) {
        if (env.generation.value != generation) throw CloudAuthRequiredException()
    }

    private fun DiskCache.Snapshot.toResult(cache: DiskCache, key: String, mimeType: String?, source: DataSource) =
        SourceFetchResult(ImageSource(data, cache.fileSystem, key, this), mimeType, source)

    /**
     * Single factory (Hilt). Built at application startup: its heavy dependencies (Graph
     * client, OkHttp, MSAL) are injected via dagger.Lazy and only created on the first
     * download, on the I/O dispatcher.
     */
    @Singleton
    class Factory @Inject constructor(
        private val guard: CloudAccessGuard,
        internal val graph: dagger.Lazy<GraphApi>,
        internal val generation: CloudDataGeneration,
        internal val precache: CloudPrecacheStore,
        @param:IoDispatcher internal val io: CoroutineDispatcher,
    ) : Fetcher.Factory<CloudThumbnail> {

        /** Concurrent downloads (fast scrolling: Coil cancels cells that left the screen). */
        internal val permits = Semaphore(MAX_PARALLEL_DOWNLOADS)

        /**
         * Thumbnails not found (404): no new request for 10 minutes. Forgotten as soon as the
         * OneDrive data is wiped (sign-out, purge, account change): the keys contain nothing
         * specific to the account.
         */
        private val unavailable = ExpiringKeySet(UNAVAILABLE_TTL_MS, System::currentTimeMillis)

        /** Cache wipe counter that the keys of [unavailable] correspond to. */
        @Volatile
        private var unavailableGeneration = 0L

        /** Graph refused a custom size: standard sizes only (until restart). */
        @Volatile
        internal var customSizesRejected = false

        /** Largest preview bucket accepted by Graph (lowered after a refusal, until restart). */
        @Volatile
        internal var maxPreviewSide = CLOUD_PREVIEW_TIERS.last()

        /** Preview bucket actually requested for [requested] (null: grid thumbnail). */
        internal fun effectivePreviewSide(requested: Int?): Int? = requested?.let {
            cappedPreviewSide(it, if (customSizesRejected) CLOUD_PREVIEW_PX else maxPreviewSide)
        }

        override fun create(data: CloudThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            CloudThumbnailFetcher(data, options, imageLoader, this)

        /** Registers the Keyer and this factory in the ImageLoader components. */
        fun register(components: ComponentRegistry.Builder) {
            components.add(CloudThumbnailKeyer(this), CloudThumbnail::class)
            components.add(this, CloudThumbnail::class)
        }

        internal fun isUnavailable(key: String, generation: Long): Boolean {
            syncUnavailableGeneration(generation)
            return key in unavailable
        }

        /** Remembered only if the data was not wiped since the start of the load. */
        internal fun markUnavailable(key: String, generation: Long) {
            if (this.generation.value == generation) unavailable.add(key)
        }

        @Synchronized
        private fun syncUnavailableGeneration(generation: Long) {
            if (unavailableGeneration != generation) {
                unavailable.clear()
                unavailableGeneration = generation
            }
        }

        /**
         * Download allowed only for a signed-in account, never during a sign-out nor when the
         * connected OneDrive is not the one the index data came from (common rule
         * [CloudAccessGuard]): only already disk-cached thumbnails of another account's items
         * remain displayed.
         */
        internal suspend fun ensureSignedIn() = guard.ensureSignedIn()
    }

    private companion object {
        const val MAX_PARALLEL_DOWNLOADS = 4
        const val UNAVAILABLE_TTL_MS = 10 * 60_000L
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
    }
}
