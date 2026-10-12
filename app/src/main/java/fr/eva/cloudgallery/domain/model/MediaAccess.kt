package fr.eva.cloudgallery.domain.model

/** Niveau d'accès aux médias de l'appareil. */
enum class MediaAccess {
    /** Photos et vidéos : accès complet. */
    FULL,

    /** Android 14+ : seulement les éléments choisis par l'utilisateur (READ_MEDIA_VISUAL_USER_SELECTED). */
    PARTIAL,

    /** Aucun accès. */
    NONE,
}

/**
 * Accès déduit des autorisations accordées : complet avec images et vidéos, partiel avec l'une
 * des deux ou la sélection de photos (Android 14+), aucun sinon.
 */
fun mediaAccessOf(images: Boolean, videos: Boolean, selected: Boolean): MediaAccess = when {
    images && videos -> MediaAccess.FULL
    images || videos || selected -> MediaAccess.PARTIAL
    else -> MediaAccess.NONE
}

/**
 * Photos et Vidéos montrent aussi les éléments présents uniquement sur OneDrive : réglage
 * « Afficher les éléments OneDrive dans Photos », ou aucun accès aux photos du téléphone (usage
 * « OneDrive seul », décision utilisateur : OneDrive d'office).
 */
fun includeCloudOnlyInLibrary(showCloudInPhotos: Boolean, access: MediaAccess): Boolean =
    showCloudInPhotos || access == MediaAccess.NONE
