package fr.eva.cloudgallery.ui.util

import androidx.core.content.FileProvider

/**
 * The app's FileProvider (a subclass, as recommended by the FileProvider documentation):
 * exposes read-only, for sharing and "Open with", only two folders of copies
 * created by the app (see res/xml/file_paths.xml): downloaded OneDrive originals
 * (`cacheDir/onedrive/originals/`) and offline copies (`filesDir/offline/`). Never a
 * user file.
 */
class CloudGalleryFileProvider : FileProvider()
