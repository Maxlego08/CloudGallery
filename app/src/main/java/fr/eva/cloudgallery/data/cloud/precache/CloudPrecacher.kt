package fr.eva.cloudgallery.data.cloud.precache

import fr.eva.cloudgallery.data.cloud.graph.GraphApi
import fr.eva.cloudgallery.data.cloud.graph.GraphHttpException
import fr.eva.cloudgallery.data.cloud.thumb.CLOUD_PREVIEW_PX
import fr.eva.cloudgallery.data.cloud.thumb.CloudThumbnailFetcher
import fr.eva.cloudgallery.data.cloud.thumb.DEFAULT_CLOUD_THUMB_PX
import fr.eva.cloudgallery.data.cloud.thumb.cloudThumbSize
import fr.eva.cloudgallery.data.cloud.thumb.effectiveThumbPath
import fr.eva.cloudgallery.data.cloud.thumb.shouldTryFallbackSize
import fr.eva.cloudgallery.domain.model.CloudThumbRef
import javax.inject.Inject
import javax.inject.Singleton

/** Résultat de la mise en cache d'un fichier. */
sealed interface PrecacheFileResult {
    /** Déjà en cache : rien téléchargé. */
    data object Present : PrecacheFileResult

    /** Téléchargé et enregistré ([bytes] octets). */
    data class Saved(val bytes: Long) : PrecacheFileResult

    /** Graph n'a pas de miniature pour cet élément (404) : pas une panne réseau. */
    data object NoThumbnail : PrecacheFileResult
}

/**
 * Téléchargement d'une vignette ou d'un aperçu vers [CloudPrecacheStore], avec les mêmes tailles
 * Microsoft Graph et les mêmes replis que l'affichage ([CloudThumbnailFetcher]) : les réglages
 * appris par l'affichage (taille personnalisée refusée, grand aperçu refusé) sont partagés.
 * À appeler sur le dispatcher d'E/S, compte vérifié par l'appelant ([fr.eva.cloudgallery.data.cloud.CloudAccessGuard]).
 */
@Singleton
class CloudPrecacher @Inject constructor(
    private val graph: dagger.Lazy<GraphApi>,
    private val store: CloudPrecacheStore,
    private val thumbs: CloudThumbnailFetcher.Factory,
) {

    /** Vignette de grille au palier [bucket] de [ref]. */
    suspend fun grid(ref: CloudThumbRef, bucket: Int, token: CloudPrecacheStore.WriteToken): PrecacheFileResult {
        if (gridPresent(ref, bucket)) return PrecacheFileResult.Present
        return download(ref, PrecacheKind.GRID, bucket, token)
    }

    /** Aperçu de [ref] au côté [side] (voir [previewPresent]). */
    suspend fun preview(ref: CloudThumbRef, side: Int, token: CloudPrecacheStore.WriteToken): PrecacheFileResult {
        if (previewPresent(ref, side)) return PrecacheFileResult.Present
        return download(ref, PrecacheKind.PREVIEW, thumbs.effectivePreviewSide(side) ?: side, token)
    }

    /** Vignette déjà enregistrée à ce palier ou plus grand (disque). */
    fun gridPresent(ref: CloudThumbRef, bucket: Int): Boolean =
        store.find(ref, PrecacheKind.GRID, bucket, allowLower = false) != null

    /**
     * Aperçu déjà enregistré à ce côté ou plus grand (disque). Un aperçu au palier de base
     * ([CLOUD_PREVIEW_PX]) suffit quand le grand palier a été refusé par Graph lors d'un passage
     * précédent : il n'est pas redemandé à chaque passage.
     */
    fun previewPresent(ref: CloudThumbRef, side: Int): Boolean {
        val wanted = thumbs.effectivePreviewSide(side) ?: side
        if (store.find(ref, PrecacheKind.PREVIEW, wanted, allowLower = false) != null) return true
        return wanted > CLOUD_PREVIEW_PX && store.contains(ref, PrecacheKind.PREVIEW, CLOUD_PREVIEW_PX)
    }

    private suspend fun download(ref: CloudThumbRef, kind: PrecacheKind, side: Int, token: CloudPrecacheStore.WriteToken): PrecacheFileResult {
        val previewSide = if (kind == PrecacheKind.PREVIEW) side else null
        val size = cloudThumbSize(if (kind == PrecacheKind.GRID) side else DEFAULT_CLOUD_THUMB_PX, previewSide)
        val path = effectiveThumbPath(size, thumbs.customSizesRejected)
        return try {
            PrecacheFileResult.Saved(fetch(ref, path, kind, side, token))
        } catch (e: GraphHttpException) {
            val fallback = size.fallback
            if (fallback == null || path == fallback || !shouldTryFallbackSize(e.status)) {
                if (e.status == HTTP_NOT_FOUND) return PrecacheFileResult.NoThumbnail
                throw e
            }
            // Grand aperçu refusé : palier de base, enregistré comme tel. Sinon, taille standard
            // enregistrée au palier demandé (comme le cache de l'affichage).
            val savedSide = if (previewSide != null && previewSide > CLOUD_PREVIEW_PX) CLOUD_PREVIEW_PX else side
            try {
                fetch(ref, fallback, kind, savedSide, token).also {
                    if (savedSide != side) {
                        thumbs.maxPreviewSide = CLOUD_PREVIEW_PX
                    } else if (e.status == HTTP_BAD_REQUEST) {
                        thumbs.customSizesRejected = true
                    }
                }.let { PrecacheFileResult.Saved(it) }
            } catch (e2: GraphHttpException) {
                if (e2.status == HTTP_NOT_FOUND) PrecacheFileResult.NoThumbnail else throw e2
            }
        }
    }

    private suspend fun fetch(ref: CloudThumbRef, path: String, kind: PrecacheKind, side: Int, token: CloudPrecacheStore.WriteToken): Long =
        graph.get().thumbnailContent(ref.itemId, path) { body, _ -> store.write(ref, kind, side, body, token) }

    private companion object {
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
    }
}
