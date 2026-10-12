package fr.eva.cloudgallery.ui.util

import androidx.annotation.StringRes
import fr.eva.cloudgallery.R
import fr.eva.cloudgallery.domain.model.DownloadError

/** Message affiché pour un échec de téléchargement OneDrive (un texte par cause). */
@StringRes
fun downloadErrorMessage(error: DownloadError): Int = when (error) {
    DownloadError.NETWORK -> R.string.cloud_download_error_network
    DownloadError.AUTH_REQUIRED -> R.string.cloud_download_error_auth
    DownloadError.THROTTLED -> R.string.cloud_download_error_throttled
    DownloadError.NOT_FOUND -> R.string.cloud_download_error_not_found
    DownloadError.CONTENT_CHANGED -> R.string.cloud_download_error_content_changed
    DownloadError.NO_SPACE -> R.string.cloud_download_error_no_space
    DownloadError.SIZE_MISMATCH -> R.string.cloud_download_error_size_mismatch
    DownloadError.SERVER -> R.string.cloud_download_error_server
    DownloadError.CANCELLED -> R.string.cloud_download_error_cancelled
}
