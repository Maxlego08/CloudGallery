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
 * Plafond du cache disque Coil (miniatures et aperçus OneDrive) lu dans les réglages. Coil ouvre
 * son cache disque une seule fois, au premier chargement qui en a besoin (sur son dispatcher
 * d'E/S) : le réglage est lu dès le démarrage de l'appli ([preload]) et un changement ne
 * s'applique qu'au démarrage suivant. [effectiveBytes] : plafond retenu à l'ouverture du cache.
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

    /** Lecture du réglage en arrière-plan (Application.onCreate). */
    fun preload() {
        if (!started.compareAndSet(false, true)) return
        scope.launch(io) {
            val bytes = try {
                settings.settings.first().thumbnailCacheMaxBytes
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Plafond du cache des miniatures illisible, valeur par défaut", e)
                DEFAULT_THUMBNAIL_CACHE_BYTES
            }
            loaded.complete(bytes)
        }
    }

    /**
     * Plafond à l'ouverture du cache : le réglage s'il est déjà lu (cas normal), sinon une courte
     * attente, puis la valeur par défaut.
     */
    fun forDiskCacheOpening(): Long {
        preload()
        // Réglage déjà lu : await() rend la valeur aussitôt.
        val bytes = runBlocking { withTimeoutOrNull(OPEN_WAIT_MS) { loaded.await() } } ?: DEFAULT_THUMBNAIL_CACHE_BYTES
        effectiveBytes = bytes
        return bytes
    }

    private companion object {
        const val TAG = "ThumbnailCacheLimit"
        const val OPEN_WAIT_MS = 500L
    }
}
