package fr.eva.cloudgallery.data.cloud.offline

import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import fr.eva.cloudgallery.data.db.dao.CachedFileDao
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.OfflineThumbnail
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import okio.Path.Companion.toOkioPath
import java.io.FileNotFoundException
import javax.inject.Inject
import javax.inject.Singleton

/** Memory cache key: media + copy version (no file path). */
private class OfflineThumbnailKeyer : Keyer<OfflineThumbnail> {
    override fun key(data: OfflineThumbnail, options: Options): String = "offline:${data.mediaId}:${data.version}"
}

/**
 * Thumbnail taken from a media item's offline copy (item kept offline whose OneDrive thumbnail is
 * not cached, or which no longer exists on OneDrive). The file is looked up in the database and
 * then read on the I/O dispatcher; Coil decodes it at the requested size (image, or a video frame
 * via VideoFrameDecoder). Nothing is written to the disk cache.
 */
class OfflineThumbnailFetcher private constructor(
    private val data: OfflineThumbnail,
    private val options: Options,
    private val factory: Factory,
) : Fetcher {

    override suspend fun fetch(): FetchResult = withContext(factory.io) {
        val row = factory.cachedFiles.get(data.mediaId, CacheKind.OFFLINE) ?: throw FileNotFoundException("Offline copy missing")
        val file = factory.store.fileOf(row.path)?.takeIf { it.isFile } ?: throw FileNotFoundException("Offline copy missing")
        SourceFetchResult(
            source = ImageSource(file.toOkioPath(), options.fileSystem),
            mimeType = data.mimeType,
            dataSource = DataSource.DISK,
        )
    }

    @Singleton
    class Factory @Inject constructor(
        internal val cachedFiles: CachedFileDao,
        internal val store: OfflineStore,
        @param:IoDispatcher internal val io: CoroutineDispatcher,
    ) : Fetcher.Factory<OfflineThumbnail> {

        override fun create(data: OfflineThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            OfflineThumbnailFetcher(data, options, this)

        /** Registers the Keyer and this factory in the ImageLoader components. */
        fun register(components: ComponentRegistry.Builder) {
            components.add(OfflineThumbnailKeyer(), OfflineThumbnail::class)
            components.add(this, OfflineThumbnail::class)
        }
    }
}
