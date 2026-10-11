package fr.eva.cloudgallery.ui.image

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Size as CoilSize
import coil3.video.VideoFrameDecoder
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import okio.Path.Companion.toOkioPath

private const val DISK_CACHE_MAX_BYTES = 256L * 1024 * 1024

/**
 * ImageLoader unique de l'application (fourni par CloudGalleryApp via SingletonImageLoader.Factory).
 * - cache mémoire ≈ 25 % de la mémoire de l'appli ;
 * - cache disque borné à 256 Mo (utile pour les futures images distantes ; les miniatures locales
 *   ne passent pas par lui) ;
 * - miniatures MediaStore via [LocalThumbnailFetcher] ; images complètes des vidéos via VideoFrameDecoder.
 */
fun buildAppImageLoader(context: Context): ImageLoader =
    ImageLoader.Builder(context)
        .memoryCache {
            MemoryCache.Builder()
                .maxSizePercent(context, 0.25)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(context.cacheDir.resolve("coil").toOkioPath())
                .maxSizeBytes(DISK_CACHE_MAX_BYTES)
                .build()
        }
        .components {
            add(LocalThumbnailKeyer(), LocalThumbnail::class)
            add(LocalThumbnailFetcher.Factory(), LocalThumbnail::class)
            add(VideoFrameDecoder.Factory())
        }
        .build()

/**
 * Miniature carrée pour les grilles et couvertures d'album. Dessine la couleur
 * `OneUiTheme.colors.placeholder` pendant le chargement, en cas d'erreur ou si [uri] est null.
 * [version] (dateModified) invalide le cache après une modification.
 */
@Composable
fun ThumbnailImage(
    uri: Uri?,
    isVideo: Boolean,
    version: Long,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    Box(modifier.background(OneUiTheme.colors.placeholder)) {
        if (uri != null) {
            val model = remember(uri, isVideo, version) { LocalThumbnail(uri, isVideo, version) }
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * Requête d'image plein écran pour la visionneuse, bornée à [maxSidePx] (côté le plus long).
 * La clé de cache mémoire inclut [version] pour refléter les modifications du fichier.
 *
 * Coil plafonne tout décodage à 4096 x 4096 par défaut (maxBitmapSize) : au-delà, le plafond est
 * relevé à [maxSidePx], sans quoi le rechargement haute résolution n'apporterait aucun détail.
 */
fun fullImageRequest(context: Context, uri: Uri, version: Long, maxSidePx: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(uri)
        .size(maxSidePx)
        .apply { if (maxSidePx > COIL_DEFAULT_MAX_BITMAP_SIDE) maxBitmapSize(CoilSize(maxSidePx, maxSidePx)) }
        .precision(Precision.INEXACT)
        .memoryCacheKey("full:$uri:$version:$maxSidePx")
        // URI content:// locales : inutile de dupliquer le fichier dans le cache disque.
        .diskCachePolicy(CachePolicy.DISABLED)
        .build()

/** Plafond par défaut de Coil pour la taille d'un bitmap décodé (Extras maxBitmapSize). */
private const val COIL_DEFAULT_MAX_BITMAP_SIDE = 4096
