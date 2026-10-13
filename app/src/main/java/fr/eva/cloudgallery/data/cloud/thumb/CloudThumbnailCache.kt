package fr.eva.cloudgallery.data.cloud.thumb

import android.content.Context
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cached OneDrive thumbnails (disk and memory), cleared along with the OneDrive data. A
 * thumbnail being downloaded during a purge is not saved: see
 * [fr.eva.cloudgallery.data.cloud.CloudDataGeneration], incremented before the purge.
 */
interface CloudThumbnailCache {
    /** Clears OneDrive thumbnails (disk and memory). I/O thread. */
    suspend fun clear()
}

/**
 * OneDrive thumbnails are written to the disk cache of the single Coil ImageLoader
 * (`cacheDir/coil`, adjustable cap: [ThumbnailCacheLimit]): that is its only content (local thumbnails loaded by MediaStore,
 * local full-screen images without disk cache), so it is cleared entirely. In memory, only
 * OneDrive entries are removed.
 */
@Singleton
class CoilCloudThumbnailCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : CloudThumbnailCache {

    override suspend fun clear(): Unit = withContext(io) {
        val loader = SingletonImageLoader.get(context)
        loader.diskCache?.clear()
        loader.memoryCache?.let { memory ->
            memory.keys
                .filter { it.key.startsWith(CLOUD_THUMB_KEY_PREFIX) }
                .forEach { memory.remove(it) }
        }
    }
}
