package fr.eva.cloudgallery.data.cloud.thumb

import android.util.Log
import fr.eva.cloudgallery.di.ApplicationScope
import fr.eva.cloudgallery.di.IoDispatcher
import fr.eva.cloudgallery.domain.model.DEFAULT_THUMBNAIL_CACHE_BYTES
import fr.eva.cloudgallery.domain.repository.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Cap of the Coil disk cache (OneDrive thumbnails and previews) read from the settings. Coil
 * opens its disk cache only once, on the first load that needs it (on its I/O dispatcher):
 * the setting is read right at app startup ([preload]) and a change only applies on the next
 * startup. [effectiveBytes]: cap retained when the cache was opened.
 */
@Singleton
class ThumbnailCacheLimit @Inject constructor(
    private val settings: SettingsRepository,
    @ApplicationScope private val scope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {
    private val loaded = CompletableDeferred<Long>()
    private val started = AtomicBoolean(false)

    @Volatile
    var effectiveBytes: Long? = null
        private set

    /** Reads the setting in the background (Application.onCreate). */
    fun preload() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(io) {
            val bytes = try {
                settings.settings.first().thumbnailCacheMaxBytes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Unreadable thumbnail cache cap, using default value", e)
                DEFAULT_THUMBNAIL_CACHE_BYTES
            }
            loaded.complete(bytes)
        }
    }

    /**
     * Cap when the cache is opened: the setting if already read (normal case), otherwise a
     * short wait, then the default value.
     */
    fun forDiskCacheOpening(): Long {
        preload()
        // Setting already read: await() returns the value immediately.
        val bytes = runBlocking { withTimeoutOrNull(OPEN_WAIT_MS) { loaded.await() } } ?: DEFAULT_THUMBNAIL_CACHE_BYTES
        effectiveBytes = bytes
        return bytes
    }

    private companion object {
        const val TAG = "ThumbnailCacheLimit"
        const val OPEN_WAIT_MS = 500L
    }
}
