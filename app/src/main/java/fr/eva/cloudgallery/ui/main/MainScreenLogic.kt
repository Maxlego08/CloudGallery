package fr.eva.cloudgallery.ui.main

import fr.eva.cloudgallery.domain.model.MediaAccess

/*
 * Pure logic of the main screen (JVM-tested).
 */

/** Main screen content depending on media access and the "OneDrive only" choice. */
enum class MainScreenMode {
    /** "OneDrive only" choice not read yet: nothing is shown (avoids a flashing screen). */
    LOADING,

    /** Permission screen. */
    PERMISSION,

    /** Full tabs on OneDrive only, with the "Phone photos not allowed" banner. */
    ONEDRIVE_ONLY,

    /** Full tabs (full or partial access). */
    LIBRARY,
}

/**
 * Access granted: library. Without access: "OneDrive only" if it was chosen and OneDrive
 * exists in this build ([cloudAvailable]), otherwise the permission screen. [oneDriveOnlyChosen]
 * null: setting not read yet.
 */
internal fun mainScreenMode(access: MediaAccess, cloudAvailable: Boolean, oneDriveOnlyChosen: Boolean?): MainScreenMode = when {
    access != MediaAccess.NONE -> MainScreenMode.LIBRARY
    !cloudAvailable -> MainScreenMode.PERMISSION
    oneDriveOnlyChosen == null -> MainScreenMode.LOADING
    oneDriveOnlyChosen -> MainScreenMode.ONEDRIVE_ONLY
    else -> MainScreenMode.PERMISSION
}
