package fr.eva.cloudgallery.ui.viewer

import android.net.Uri
import fr.eva.cloudgallery.domain.model.CloudThumbRef

/** What the viewer displays for an image. */
sealed interface ViewerImageSource {
    /** Local file (MediaStore `content://` URI), versioned by its modification date. */
    data class MediaStore(val uri: Uri, val dateModified: Long) : ViewerImageSource

    /**
     * App-owned file ([uri] `file://`): downloaded OneDrive original or offline copy.
     * [versionKey]: content version (cTag). [cloud]/[previewSide]: OneDrive preview shown while
     * decoding; [previewFromCacheOnly]: only if it is already cached (offline copy: no network
     * request for a file that is present on the device).
     */
    data class File(
        val uri: Uri,
        val versionKey: String,
        val cloud: CloudThumbRef?,
        val previewSide: Int,
        val previewFromCacheOnly: Boolean = false,
    ) : ViewerImageSource

    /** Large OneDrive preview ([side] px), zoomable, possibly while waiting for the original. */
    data class CloudPreview(val ref: CloudThumbRef, val side: Int) : ViewerImageSource
}
