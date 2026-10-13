package fr.eva.cloudgallery.ui.viewer

import android.net.Uri
import fr.eva.cloudgallery.domain.model.CloudThumbRef

/** Ce que la visionneuse affiche pour une image. */
sealed interface ViewerImageSource {
    /** Fichier local (URI `content://` MediaStore), versionné par sa date de modification. */
    data class MediaStore(val uri: Uri, val dateModified: Long) : ViewerImageSource

    /**
     * Fichier de l'appli ([uri] `file://`) : original OneDrive téléchargé ou copie hors ligne.
     * [versionKey] : version du contenu (cTag). [cloud]/[previewSide] : aperçu OneDrive affiché
     * pendant le décodage ; [previewFromCacheOnly] : seulement s'il est déjà en cache (copie hors
     * ligne : aucune requête réseau pour un fichier présent sur l'appareil).
     */
    data class File(
        val uri: Uri,
        val versionKey: String,
        val cloud: CloudThumbRef?,
        val previewSide: Int,
        val previewFromCacheOnly: Boolean = false,
    ) : ViewerImageSource

    /** Grand aperçu OneDrive ([side] px), zoomable, en attendant éventuellement l'original. */
    data class CloudPreview(val ref: CloudThumbRef, val side: Int) : ViewerImageSource
}
