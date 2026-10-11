package fr.eva.cloudgallery.domain.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Index du niveau de zoom de la grille Photos (voir GridZoomLevels). */
    val photosZoomLevel: Int = 1,
    /** Index du niveau de zoom des grilles d'album. */
    val albumZoomLevel: Int = 1,
    val albumSort: AlbumSort = AlbumSort.CUSTOM,
    val showVideoDuration: Boolean = true,
    val autoPlayVideos: Boolean = true,
    val confirmBeforeDelete: Boolean = true,
    /** Envoie à la corbeille Android (récupérable 30 j) au lieu de supprimer définitivement. */
    val useTrash: Boolean = true,
)

/** Etat de l'indexation des médias locaux. */
data class IndexState(
    val running: Boolean = false,
    val lastSyncAt: Long = 0L,
    val indexedCount: Int = 0,
    val lastError: String? = null,
)
