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
import coil3.ComponentRegistry
import coil3.ImageLoader
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import coil3.request.maxBitmapSize
import coil3.size.Precision
import coil3.size.Size as CoilSize
import coil3.video.VideoFrameDecoder
import fr.eva.cloudgallery.domain.model.CloudThumbRef
import fr.eva.cloudgallery.domain.model.CloudThumbnail
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import okio.Path.Companion.toOkioPath

private const val DISK_CACHE_MAX_BYTES = 256L * 1024 * 1024

/**
 * ImageLoader unique de l'application (fourni par CloudGalleryApp via SingletonImageLoader.Factory).
 * - cache mémoire ≈ 25 % de la mémoire de l'appli ;
 * - cache disque borné à 256 Mo : miniatures OneDrive uniquement (les miniatures locales et les
 *   images plein écran locales ne passent pas par lui) ;
 * - miniatures MediaStore via [LocalThumbnailFetcher] ; images complètes des vidéos via VideoFrameDecoder ;
 * - [extraComponents] : composants fournis par la couche données (miniatures OneDrive, modèle
 *   [CloudThumbnail]).
 * La construction est paresseuse : aucun cache n'est ouvert avant le premier chargement.
 */
fun buildAppImageLoader(
    context: Context,
    extraComponents: ComponentRegistry.Builder.() -> Unit = {},
): ImageLoader =
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
            extraComponents()
        }
        .build()

/**
 * Miniature carrée pour les grilles et couvertures d'album. Dessine la couleur
 * `OneUiTheme.colors.placeholder` pendant le chargement, en cas d'erreur ou sans source.
 * Fichier local ([uri], toujours prioritaire) via MediaStore, sinon miniature OneDrive ([cloud]).
 * [version] (dateModified) invalide le cache local après une modification ; la miniature
 * OneDrive est versionnée par son cTag.
 */
@Composable
fun ThumbnailImage(
    uri: Uri?,
    isVideo: Boolean,
    version: Long,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    cloud: CloudThumbRef? = null,
) {
    Box(modifier.background(OneUiTheme.colors.placeholder)) {
        val model: Any? = remember(uri, isVideo, version, cloud) {
            when {
                uri != null -> LocalThumbnail(uri, isVideo, version)
                cloud != null -> CloudThumbnail(cloud)
                else -> null
            }
        }
        if (model != null) {
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

/**
 * Grand aperçu d'un élément présent uniquement sur OneDrive (visionneuse), aux proportions
 * d'origine. [onError] : vrai si l'aperçu n'a pas pu être chargé (hors connexion sans cache,
 * session expirée, pas de miniature).
 */
@Composable
fun CloudPreviewImage(
    ref: CloudThumbRef,
    modifier: Modifier = Modifier,
    onError: (Boolean) -> Unit = {},
) {
    val model = remember(ref) { CloudThumbnail(ref, preview = true) }
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        onState = { state -> onError(state is AsyncImagePainter.State.Error) },
        modifier = modifier,
    )
}

/** Plafond par défaut de Coil pour la taille d'un bitmap décodé (Extras maxBitmapSize). */
private const val COIL_DEFAULT_MAX_BITMAP_SIDE = 4096
