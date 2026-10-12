package fr.eva.cloudgallery.ui.viewer

import android.net.Uri
import fr.eva.cloudgallery.data.cloud.content.CloudFileTarget
import fr.eva.cloudgallery.data.cloud.content.cloudFileTargetOf
import fr.eva.cloudgallery.domain.model.DownloadError
import fr.eva.cloudgallery.domain.model.MediaDetails
import fr.eva.cloudgallery.domain.model.MediaType

/** Original OneDrive de l'image affichée (visionneuse). */
sealed interface CloudOriginalUi {
    /** Aperçu seul. */
    data object Idle : CloudOriginalUi

    /** Téléchargement en cours ([done] / [total] octets, [total] ≤ 0 si inconnu). */
    data class Downloading(val done: Long, val total: Long) : CloudOriginalUi

    /** Original disponible sur l'appareil ([fileUri] `file://`, usage interne uniquement). */
    data class Ready(val fileUri: Uri, val versionKey: String) : CloudOriginalUi

    /** Réseau facturé : l'utilisateur choisit de charger l'original ([bytes] octets). */
    data class NeedsConfirmation(val bytes: Long) : CloudOriginalUi

    /** Echec du dernier téléchargement. */
    data class Failed(val error: DownloadError) : CloudOriginalUi
}

/** [ui] pour le média [mediaId] (les autres pages affichent l'aperçu seul). */
data class CloudOriginalState(val mediaId: Long, val ui: CloudOriginalUi)

/** Préparation d'un partage ou d'un « Ouvrir avec » (téléchargement depuis OneDrive). */
data class SharePreparation(val done: Long, val total: Long)

/** Cible de l'original affiché en grand : images uniquement (les vidéos sont lues en streaming). */
internal fun cloudOriginalTargetOf(media: MediaDetails): CloudFileTarget? =
    if (media.type == MediaType.IMAGE) cloudFileTargetOf(media) else null

/** Grand côté de l'image d'après ses métadonnées (px), null si inconnu. */
internal fun imageLongSide(media: MediaDetails): Int? = maxOf(media.width, media.height).takeIf { it > 0 }
