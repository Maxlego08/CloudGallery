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
    /**
     * Affiche dans Photos et Vidéos les éléments présents uniquement sur OneDrive. Désactivé par
     * défaut tant que les doublons appareil/OneDrive ne sont pas fusionnés : ces éléments restent
     * visibles dans leurs albums OneDrive, les Favoris et les albums de l'utilisateur.
     */
    val showCloudInPhotos: Boolean = false,
    /**
     * Charge automatiquement l'original OneDrive au zoom profond aussi sur un réseau facturé
     * (données mobiles, économiseur de données). Désactivé par défaut : l'original est alors
     * proposé (« Charger l'original (taille) ») ; il est toujours automatique en Wi-Fi non facturé.
     */
    val loadOriginalsOnMobileData: Boolean = false,
    /**
     * Copies hors ligne téléchargées uniquement sur un réseau non facturé (Wi-Fi). Activé par
     * défaut ; « Télécharger maintenant » lève la restriction pour la file en cours.
     */
    val offlineWifiOnly: Boolean = true,
    /**
     * L'autorisation d'afficher des notifications a déjà été demandée (au premier « Conserver
     * hors ligne ») : elle n'est plus redemandée ensuite. Les téléchargements fonctionnent sans.
     */
    val offlineNotificationsAsked: Boolean = false,
)

/** Etat de l'indexation des médias locaux. */
data class IndexState(
    val running: Boolean = false,
    val lastSyncAt: Long = 0L,
    val indexedCount: Int = 0,
    val lastError: String? = null,
)
