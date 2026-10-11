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
 * Modèle Coil pour une miniature MediaStore. [version] (dateModified) invalide le cache
 * mémoire après une modification du fichier (rotation, recadrage…).
 */
data class LocalThumbnail(
    val uri: Uri,
    val isVideo: Boolean,
    val version: Long,
)

/** Taille par défaut quand la taille demandée est inconnue / originale. */
internal const val DEFAULT_THUMBNAIL_PX = 384

/**
 * Arrondit la taille demandée à un palier, pour que de petites variations de taille de cellule
 * (zoom de la grille) réutilisent la même entrée de cache.
 */
internal fun thumbnailBucket(options: Options): Int {
    val w = options.size.width.pxOrElse { DEFAULT_THUMBNAIL_PX }
    val h = options.size.height.pxOrElse { DEFAULT_THUMBNAIL_PX }
    val side = maxOf(w, h).coerceAtLeast(1)
    return BUCKETS.firstOrNull { it >= side } ?: BUCKETS.last()
}

private val BUCKETS = intArrayOf(96, 192, 256, 384, 512, 768, 1024)

/** Clé de cache mémoire : uri + version + palier de taille. */
class LocalThumbnailKeyer : Keyer<LocalThumbnail> {
    override fun key(data: LocalThumbnail, options: Options): String =
        "thumb:${data.uri}:${data.version}:${thumbnailBucket(options)}"
}

/**
 * Charge la miniature via [android.content.ContentResolver.loadThumbnail] : MediaStore la
 * met en cache lui-même, il n'y a donc pas de cache disque Coil pour ces requêtes.
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
                // Annulation dès le DÉBUT de l'annulation de la requête (cellule sortie de l'écran) :
                // l'enfant est annulé immédiatement et interrompt loadThumbnail via le signal. Un
                // invokeOnCompletion ne s'exécuterait qu'après le retour de l'appel bloquant.
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
            throw CancellationException("Chargement de la miniature annulé").apply { initCause(e) }
        }
    }

    class Factory : Fetcher.Factory<LocalThumbnail> {
        override fun create(data: LocalThumbnail, options: Options, imageLoader: ImageLoader): Fetcher =
            LocalThumbnailFetcher(data, options)
    }
}
