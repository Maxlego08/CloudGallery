package fr.eva.cloudgallery.data.cloud.thumb

import android.content.Context
import coil3.SingletonImageLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import fr.eva.cloudgallery.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/** Miniatures OneDrive en cache (disque et mémoire), vidées avec les données OneDrive. */
interface CloudThumbnailCache {
    /**
     * Numéro de vidage : un téléchargement commencé avant un vidage n'est pas enregistré
     * (une miniature en cours de chargement ne réapparaît pas après une déconnexion).
     */
    val generation: Long

    /** Vide les miniatures OneDrive (disque et mémoire). Thread d'E/S. */
    suspend fun clear()
}

/**
 * Les miniatures OneDrive sont écrites dans le cache disque de l'ImageLoader Coil unique
 * (`cacheDir/coil`, 256 Mo) : c'est son seul contenu (miniatures locales chargées par MediaStore,
 * images plein écran locales sans cache disque), il est donc vidé entièrement. En mémoire, seules
 * les entrées OneDrive sont retirées.
 */
@Singleton
class CoilCloudThumbnailCache @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : CloudThumbnailCache {

    private val generationCounter = AtomicLong(0)

    override val generation: Long get() = generationCounter.get()

    override suspend fun clear(): Unit = withContext(io) {
        generationCounter.incrementAndGet()
        val loader = SingletonImageLoader.get(context)
        loader.diskCache?.clear()
        loader.memoryCache?.let { memory ->
            memory.keys
                .filter { it.key.startsWith(CLOUD_THUMB_KEY_PREFIX) }
                .forEach { memory.remove(it) }
        }
    }
}
