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

/** Result of caching one file. */
sealed interface PrecacheFileResult {
    /** Already cached: nothing downloaded. */
    data object Present : PrecacheFileResult

    /** Downloaded and saved ([bytes] bytes). */
    data class Saved(val bytes: Long) : PrecacheFileResult

    /** Graph has no thumbnail for this item (404): not a network failure. */
    data object NoThumbnail : PrecacheFileResult
}

/**
 * Downloads a thumbnail or a preview into [CloudPrecacheStore], with the same Microsoft Graph
 * sizes and the same fallbacks as the display path ([CloudThumbnailFetcher]): settings learned by
 * the display path (custom size rejected, large preview rejected) are shared.
 * To be called on the I/O dispatcher, account checked by the caller ([fr.eva.cloudgallery.data.cloud.CloudAccessGuard]).
 */
@Singleton
class CloudPrecacher @Inject constructor(
    private val graph: dagger.Lazy<GraphApi>,
    private val store: CloudPrecacheStore,
    private val thumbs: CloudThumbnailFetcher.Factory,
) {

    /** Grid thumbnail of [ref] at the [bucket] tier. */
    suspend fun grid(ref: CloudThumbRef, bucket: Int, token: CloudPrecacheStore.WriteToken): PrecacheFileResult {
        if (gridPresent(ref, bucket)) return PrecacheFileResult.Present
        return download(ref, PrecacheKind.GRID, bucket, token)
    }

    /** Preview of [ref] with side [side] (see [previewPresent]). */
    suspend fun preview(ref: CloudThumbRef, side: Int, token: CloudPrecacheStore.WriteToken): PrecacheFileResult {
        if (previewPresent(ref, side)) return PrecacheFileResult.Present
        return download(ref, PrecacheKind.PREVIEW, thumbs.effectivePreviewSide(side) ?: side, token)
    }

    /** Thumbnail already saved at this tier or larger (disk). */
    fun gridPresent(ref: CloudThumbRef, bucket: Int): Boolean =
        store.find(ref, PrecacheKind.GRID, bucket, allowLower = false) != null

    /**
     * Preview already saved at this side or larger (disk). A preview at the base tier
     * ([CLOUD_PREVIEW_PX]) is enough when the large tier was rejected by Graph during a previous
     * pass: it is not requested again on every pass.
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
            // Large preview rejected: base tier, saved as such. Otherwise, standard size saved at
            // the requested tier (like the display cache).
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
