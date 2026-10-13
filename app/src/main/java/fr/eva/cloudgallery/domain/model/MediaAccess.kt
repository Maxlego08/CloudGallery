package fr.eva.cloudgallery.domain.model

/** Level of access to the device's media. */
enum class MediaAccess {
    /** Photos and videos: full access. */
    FULL,

    /** Android 14+: only the items selected by the user (READ_MEDIA_VISUAL_USER_SELECTED). */
    PARTIAL,

    /** No access. */
    NONE,
}

/**
 * Access derived from the granted permissions: full with images and videos, partial with one
 * of the two or the photo selection (Android 14+), none otherwise.
 */
fun mediaAccessOf(images: Boolean, videos: Boolean, selected: Boolean): MediaAccess = when {
    images && videos -> MediaAccess.FULL
    images || videos || selected -> MediaAccess.PARTIAL
    else -> MediaAccess.NONE
}

/**
 * Photos and Videos also show items that exist only on OneDrive: "Show OneDrive items in
 * Photos" setting, or no access to the phone's photos ("OneDrive only" usage, user decision:
 * OneDrive by default).
 */
fun includeCloudOnlyInLibrary(showCloudInPhotos: Boolean, access: MediaAccess): Boolean =
    showCloudInPhotos || access == MediaAccess.NONE
