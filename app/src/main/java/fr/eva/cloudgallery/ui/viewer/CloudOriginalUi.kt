package fr.eva.cloudgallery.ui.viewer

import android.net.Uri
import fr.eva.cloudgallery.data.cloud.content.CloudFileTarget
import fr.eva.cloudgallery.data.cloud.content.cloudFileTargetOf
import fr.eva.cloudgallery.domain.model.DownloadError
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaType

/** OneDrive original of the displayed image (viewer). */
sealed interface CloudOriginalUi {
    /** Preview only. */
    data object Idle : CloudOriginalUi

    /** Download in progress ([done] / [total] bytes, [total] ≤ 0 if unknown). */
    data class Downloading(val done: Long, val total: Long) : CloudOriginalUi

    /** Original available on the device ([fileUri] `file://`, internal use only). */
    data class Ready(val fileUri: Uri, val versionKey: String) : CloudOriginalUi

    /** Metered network: the user chooses whether to load the original ([bytes] bytes). */
    data class NeedsConfirmation(val bytes: Long) : CloudOriginalUi

    /** The last download failed. */
    data class Failed(val error: DownloadError) : CloudOriginalUi
}

/** [ui] for media [mediaId] (other pages show the preview only). */
data class CloudOriginalState(val mediaId: Long, val ui: CloudOriginalUi)

/** Preparing a share or an "Open with" (download from OneDrive). */
data class SharePreparation(val done: Long, val total: Long)

/** Target of the original shown full size: images only (videos are streamed). */
internal fun cloudOriginalTargetOf(media: MediaDetails): CloudFileTarget? =
    if (media.type == MediaType.IMAGE) cloudFileTargetOf(media) else null

/** Long side of the image from its metadata (px), null if unknown. */
internal fun imageLongSide(media: MediaDetails): Int? = maxOf(media.width, media.height).takeIf { it > 0 }
