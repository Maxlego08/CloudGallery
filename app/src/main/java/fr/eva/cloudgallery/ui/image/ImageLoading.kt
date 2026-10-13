package fr.eva.cloudgallery.ui.image

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaSummary
import fr.eva.cloudgallery.domain.model.OfflineThumbnail
import fr.eva.cloudgallery.ui.theme.OneUiTheme
import okio.Path.Companion.toOkioPath

/**
 * ImageLoader unique de l'application (fourni par CloudGalleryApp via SingletonImageLoader.Factory).
 * - cache mémoire ≈ 25 % de la mémoire de l'appli ;
 * - cache disque borné à [diskCacheMaxBytes] (réglage lu à l'ouverture du cache, sur le
 *   dispatcher d'E/S de Coil) : miniatures et aperçus OneDrive uniquement (les miniatures locales
 *   et les images plein écran locales ne passent pas par lui) ;
 * - miniatures MediaStore via [LocalThumbnailFetcher] ; images complètes des vidéos via VideoFrameDecoder ;
 * - [extraComponents] : composants fournis par la couche données (miniatures OneDrive, modèle
 *   [CloudThumbnail]).
 * La construction est paresseuse : aucun cache n'est ouvert avant le premier chargement.
 */
fun buildAppImageLoader(
    context: Context,
    diskCacheMaxBytes: () -> Long,
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
                .maxSizeBytes(diskCacheMaxBytes())
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
 * `OneUiTheme.colors.placeholder` (si [placeholder]) en cas d'erreur ou sans source, animée
 * pendant le chargement ([LoadingSkeleton]).
 * Fichier local ([uri], toujours prioritaire) via MediaStore, sinon miniature OneDrive ([cloud]),
 * sinon (ou si la miniature OneDrive est indisponible, hors connexion par exemple) image tirée de
 * la copie hors ligne ([offline]). [version] (dateModified) invalide le cache local après une
 * modification ; la miniature OneDrive est versionnée par son cTag.
 */
@Composable
fun ThumbnailImage(
    uri: Uri?,
    isVideo: Boolean,
    version: Long,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    cloud: CloudThumbRef? = null,
    offline: OfflineThumbnail? = null,
    /** Faux dans la visionneuse : fond noir (transparent) au lieu de la couleur d'attente. */
    placeholder: Boolean = true,
) {
    Box(if (placeholder) modifier.background(OneUiTheme.colors.placeholder) else modifier) {
        // Miniature OneDrive en échec : repli sur la copie hors ligne (si elle existe).
        var cloudFailed by remember(cloud) { mutableStateOf(false) }
        val model: Any? = remember(uri, isVideo, version, cloud, offline, cloudFailed) {
            when {
                uri != null -> LocalThumbnail(uri, isVideo, version)
                cloud != null && !(cloudFailed && offline != null) -> CloudThumbnail(cloud)
                offline != null -> offline
                else -> null
            }
        }
        if (model != null) {
            // Chargement en cours : fond animé (LoadingSkeleton) ; chargée ou en échec : fond fixe.
            var settled by remember(model) { mutableStateOf(false) }
            if (placeholder && !settled) LoadingSkeleton(Modifier.matchParentSize())
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = contentScale,
                modifier = Modifier.fillMaxSize(),
                onSuccess = { settled = true },
                onError = {
                    settled = true
                    if (model is CloudThumbnail && offline != null) cloudFailed = true
                },
            )
        }
    }
}

/**
 * Requête d'image plein écran pour la visionneuse, bornée à [maxSidePx] (côté le plus long).
 * La clé de cache mémoire inclut [version] pour refléter les modifications du fichier
 * (dateModified d'un fichier local, cTag d'un original OneDrive téléchargé).
 *
 * Coil plafonne tout décodage à 4096 x 4096 par défaut (maxBitmapSize) : au-delà, le plafond est
 * relevé à [maxSidePx], sans quoi le rechargement haute résolution n'apporterait aucun détail.
 */
fun fullImageRequest(context: Context, uri: Uri, version: String, maxSidePx: Int): ImageRequest =
    ImageRequest.Builder(context)
        .data(uri)
        .size(maxSidePx)
        .apply { if (maxSidePx > COIL_DEFAULT_MAX_BITMAP_SIDE) maxBitmapSize(CoilSize(maxSidePx, maxSidePx)) }
        .precision(Precision.INEXACT)
        .memoryCacheKey("full:$uri:$version:$maxSidePx")
        // Fichiers déjà sur l'appareil (content:// ou original téléchargé) : inutile de les
        // dupliquer dans le cache disque.
        .diskCachePolicy(CachePolicy.DISABLED)
        .build()

/** Côté par défaut des grands aperçus OneDrive (voir CloudThumbnailLogic). */
const val DEFAULT_CLOUD_PREVIEW_SIDE = 1600

/**
 * Grand aperçu d'un élément présent uniquement sur OneDrive (visionneuse), aux proportions
 * d'origine, tenant dans un carré de [side] px. [onError] : vrai si l'aperçu n'a pas pu être
 * chargé (hors connexion sans cache, session expirée, pas de miniature). [onLoaded] : taille de
 * l'image chargée (px). [cacheOnly] : seulement depuis le cache (mémoire ou disque), jamais de
 * requête Microsoft Graph.
 */
@Composable
fun CloudPreviewImage(
    ref: CloudThumbRef,
    modifier: Modifier = Modifier,
    side: Int = DEFAULT_CLOUD_PREVIEW_SIDE,
    contentDescription: String? = null,
    cacheOnly: Boolean = false,
    onError: (Boolean) -> Unit = {},
    onLoaded: (Size) -> Unit = {},
) {
    val context = LocalContext.current
    val model: Any = remember(ref, side, cacheOnly) {
        val data = CloudThumbnail(ref, previewSide = side)
        if (cacheOnly) {
            ImageRequest.Builder(context).data(data).networkCachePolicy(CachePolicy.DISABLED).build()
        } else {
            data
        }
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription,
        contentScale = ContentScale.Fit,
        onState = { state ->
            onError(state is AsyncImagePainter.State.Error)
            if (state is AsyncImagePainter.State.Success) {
                val intrinsic = state.painter.intrinsicSize
                if (intrinsic.isSpecified && intrinsic.width > 0f && intrinsic.height > 0f) onLoaded(intrinsic)
            }
        },
        modifier = modifier,
    )
}

/** Plafond par défaut de Coil pour la taille d'un bitmap décodé (Extras maxBitmapSize). */
private const val COIL_DEFAULT_MAX_BITMAP_SIDE = 4096

/** Miniature de repli tirée de la copie hors ligne d'un élément conservé hors ligne, sinon null. */
fun MediaSummary.offlineThumbnail(): OfflineThumbnail? =
    if (hasOffline) OfflineThumbnail(id, cloudThumb?.version ?: "m$dateModified", mimeType) else null

/** Comme [MediaSummary.offlineThumbnail], pour la visionneuse (version = celle de la copie). */
fun MediaDetails.offlineThumbnail(): OfflineThumbnail? =
    offlineCopy?.let { OfflineThumbnail(id, it.version ?: "m$dateModified", mimeType) }
