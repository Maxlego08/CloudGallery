package fr.eva.cloudgallery.ui.main

import fr.eva.cloudgallery.domain.model.MediaAccess

/*
 * Logique pure de l'écran principal (testée en JVM).
 */

/** Contenu de l'écran principal selon l'accès aux médias et le choix « OneDrive seul ». */
enum class MainScreenMode {
    /** Choix « OneDrive seul » pas encore lu : rien n'est affiché (évite un écran fugace). */
    LOADING,

    /** Ecran d'autorisation. */
    PERMISSION,

    /** Onglets complets sur OneDrive seul, avec le bandeau « Photos du téléphone non autorisées ». */
    ONEDRIVE_ONLY,

    /** Onglets complets (accès complet ou partiel). */
    LIBRARY,
}

/**
 * Accès accordé : bibliothèque. Sans accès : « OneDrive seul » s'il a été choisi et que OneDrive
 * existe dans ce build ([cloudAvailable]), sinon écran d'autorisation. [oneDriveOnlyChosen] null :
 * réglage pas encore lu.
 */
internal fun mainScreenMode(access: MediaAccess, cloudAvailable: Boolean, oneDriveOnlyChosen: Boolean?): MainScreenMode = when {
    access != MediaAccess.NONE -> MainScreenMode.LIBRARY
    !cloudAvailable -> MainScreenMode.PERMISSION
    oneDriveOnlyChosen == null -> MainScreenMode.LOADING
    oneDriveOnlyChosen -> MainScreenMode.ONEDRIVE_ONLY
    else -> MainScreenMode.PERMISSION
}
