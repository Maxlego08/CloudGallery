package fr.eva.cloudgallery.ui.util

import androidx.core.content.FileProvider

/**
 * FileProvider de l'appli (sous-classe, comme le recommande la documentation de FileProvider) :
 * expose en lecture seule, pour le partage et « Ouvrir avec », uniquement deux dossiers de copies
 * créées par l'appli (voir res/xml/file_paths.xml) : les originaux OneDrive téléchargés
 * (`cacheDir/onedrive/originals/`) et les copies hors ligne (`filesDir/offline/`). Jamais un
 * fichier de l'utilisateur.
 */
class CloudGalleryFileProvider : FileProvider()
