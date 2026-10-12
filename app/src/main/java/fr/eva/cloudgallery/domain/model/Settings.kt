package fr.eva.cloudgallery.domain.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Fréquence de la synchronisation OneDrive en arrière-plan ([hours] : null si désactivée). */
enum class BackgroundSyncInterval(val hours: Long?) {
    OFF(null),
    HOURLY(1),
    EVERY_6_HOURS(6),
    DAILY(24),
}

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
    /**
     * Plafond du cache temporaire des originaux OneDrive (zoom profond, partage), une des valeurs
     * de [ORIGINALS_CACHE_CHOICES]. Les copies hors ligne n'y sont jamais comptées.
     */
    val originalsCacheMaxBytes: Long = DEFAULT_ORIGINALS_CACHE_BYTES,
    /**
     * Plafond du cache disque des miniatures et aperçus OneDrive (Coil), une des valeurs de
     * [THUMBNAIL_CACHE_CHOICES]. Appliqué à l'ouverture du cache, donc au prochain démarrage.
     */
    val thumbnailCacheMaxBytes: Long = DEFAULT_THUMBNAIL_CACHE_BYTES,
    /**
     * Synchronisation OneDrive en arrière-plan (WorkManager), en plus de celle du retour au
     * premier plan. Décision utilisateur : réglable, toutes les 6 h par défaut.
     */
    val backgroundSyncInterval: BackgroundSyncInterval = BackgroundSyncInterval.EVERY_6_HOURS,
    /** Synchronisation en arrière-plan uniquement sur un réseau non facturé (Wi-Fi). */
    val backgroundSyncWifiOnly: Boolean = false,
    /**
     * « Utiliser OneDrive seulement » choisi sur l'écran d'autorisation : sans accès aux photos du
     * téléphone, l'appli s'ouvre sur OneDrive au lieu de redemander l'autorisation. Effacé dès
     * qu'un accès est accordé (un retrait ultérieur de l'accès redemande).
     */
    val oneDriveOnlyChosen: Boolean = false,
    /**
     * Mise en cache de tout OneDrive tenue à jour : après chaque synchronisation, les nouveaux
     * éléments (ou nouvelles versions) sont mis en cache en arrière-plan. Désactivé par défaut.
     */
    val precacheKeepUpdated: Boolean = false,
)

/** Plafonds proposés pour le cache des originaux OneDrive (octets, unités décimales comme l'affichage). */
val ORIGINALS_CACHE_CHOICES: List<Long> = listOf(500_000_000L, 1_000_000_000L, 2_000_000_000L, 5_000_000_000L)

/** Plafond par défaut du cache des originaux (décision utilisateur : 1 Go). */
const val DEFAULT_ORIGINALS_CACHE_BYTES = 1_000_000_000L

/** Plafonds proposés pour le cache des miniatures et aperçus OneDrive (octets décimaux). */
val THUMBNAIL_CACHE_CHOICES: List<Long> = listOf(100_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L)

/** Plafond par défaut du cache des miniatures (≈ les 256 Mio des phases 3 et 4). */
const val DEFAULT_THUMBNAIL_CACHE_BYTES = 250_000_000L

/** Etat de l'indexation des médias locaux. */
data class IndexState(
    val running: Boolean = false,
    val lastSyncAt: Long = 0L,
    val indexedCount: Int = 0,
    val lastError: String? = null,
)
