package fr.eva.cloudgallery.data.storage

import android.content.Context
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.cloud.content.CloudOriginalStore
import fr.eva.cloudgallery.data.cloud.offline.OfflineStore
import fr.eva.cloudgallery.data.cloud.precache.CloudPrecacheStore
import fr.eva.cloudgallery.data.cloud.precache.PrecacheCleaner
import fr.eva.cloudgallery.data.cloud.thumb.CloudThumbnailCache
import fr.eva.cloudgallery.data.cloud.thumb.ThumbnailCacheLimit
import fr.eva.cloudgallery.data.db.dao.CachedFileDao
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.StorageUsage
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import fr.eva.cloudgallery.domain.repository.StorageRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Storage page: sizes measured on disk (originals and offline copies folders, Coil disk cache),
 * item counts read from `cached_file`. Everything on the I/O dispatcher; the originals and
 * copies stores are created lazily (dagger.Lazy).
 */
@Singleton
class StorageRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val originals: dagger.Lazy<CloudOriginalStore>,
    private val cachedFiles: CachedFileDao,
    private val thumbnails: CloudThumbnailCache,
    private val thumbnailLimit: ThumbnailCacheLimit,
    private val precache: CloudPrecacheStore,
    private val precacheCleaner: PrecacheCleaner,
    private val settings: SettingsRepository,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : StorageRepository {

    override suspend fun usage(): StorageUsage = withContext(io) {
        val disk = SingletonImageLoader.get(context).diskCache
        val precached = precache.usage()
        StorageUsage(
            thumbnailBytes = disk?.size ?: 0L,
            thumbnailMaxBytes = disk?.maxSize ?: thumbnailLimit.effectiveBytes,
            precacheBytes = precached.bytes,
            precacheFiles = precached.files,
            originalBytes = directoryBytes(File(context.cacheDir, CloudOriginalStore.ORIGINALS_DIR)),
            originalCount = cachedFiles.listByKind(CacheKind.ORIGINAL).size,
            offlineBytes = directoryBytes(File(context.filesDir, OfflineStore.OFFLINE_DIR)),
            offlineCount = cachedFiles.observeCount(CacheKind.OFFLINE).first(),
            freeBytes = context.filesDir.usableSpace,
        )
    }

    override suspend fun clearCache(): Unit = withContext(io) {
        thumbnails.clear()
        // Caching of all of OneDrive stopped and cleared; "Keep up to date" turned off, otherwise
        // everything would be downloaded again on the next sync.
        settings.setPrecacheKeepUpdated(false)
        precacheCleaner.clearAll()
        originals.get().clearCache()
    }

    override suspend fun applyOriginalsCap(): Unit = withContext(io) { originals.get().trim() }
}
