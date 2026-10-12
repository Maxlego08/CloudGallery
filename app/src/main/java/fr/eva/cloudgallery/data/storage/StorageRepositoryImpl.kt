package fr.eva.cloudgallery.data.storage

import android.content.Context
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.data.cloud.content.CloudOriginalStore
import fr.eva.cloudgallery.data.cloud.offline.OfflineStore
import fr.eva.cloudgallery.data.cloud.thumb.CloudThumbnailCache
import fr.eva.cloudgallery.data.cloud.thumb.ThumbnailCacheLimit
import fr.eva.cloudgallery.data.db.dao.CachedFileDao
import fr.eva.cloudgallery.data.db.entity.CacheKind
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.StorageUsage
import fr.eva.cloudgallery.domain.repository.StorageRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Page Stockage : tailles mesurées sur le disque (dossiers des originaux et des copies hors
 * ligne, cache disque Coil), nombres d'éléments lus dans `cached_file`. Tout sur le dispatcher
 * d'E/S ; les originaux et copies sont créés paresseusement (dagger.Lazy).
 */
@Singleton
class StorageRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val originals: dagger.Lazy<CloudOriginalStore>,
    private val cachedFiles: CachedFileDao,
    private val thumbnails: CloudThumbnailCache,
    private val thumbnailLimit: ThumbnailCacheLimit,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : StorageRepository {

    override suspend fun usage(): StorageUsage = withContext(io) {
        val disk = SingletonImageLoader.get(context).diskCache
        StorageUsage(
            thumbnailBytes = disk?.size ?: 0L,
            thumbnailMaxBytes = disk?.maxSize ?: thumbnailLimit.effectiveBytes,
            originalBytes = directoryBytes(File(context.cacheDir, CloudOriginalStore.ORIGINALS_DIR)),
            originalCount = cachedFiles.listByKind(CacheKind.ORIGINAL).size,
            offlineBytes = directoryBytes(File(context.filesDir, OfflineStore.OFFLINE_DIR)),
            offlineCount = cachedFiles.observeCount(CacheKind.OFFLINE).first(),
            freeBytes = context.filesDir.usableSpace,
        )
    }

    override suspend fun clearCache(): Unit = withContext(io) {
        thumbnails.clear()
        originals.get().clearCache()
    }

    override suspend fun applyOriginalsCap(): Unit = withContext(io) { originals.get().trim() }
}
