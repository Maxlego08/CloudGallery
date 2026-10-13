package fr.eva.cloudgallery.domain.model

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Frequency of the background OneDrive sync ([hours]: null if disabled). */
enum class BackgroundSyncInterval(val hours: Long?) {
    OFF(null),
    HOURLY(1),
    EVERY_6_HOURS(6),
    DAILY(24),
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    /** Index of the Photos grid zoom level (see GridZoomLevels). */
    val photosZoomLevel: Int = 1,
    /** Index of the album grids zoom level. */
    val albumZoomLevel: Int = 1,
    val albumSort: AlbumSort = AlbumSort.CUSTOM,
    val showVideoDuration: Boolean = true,
    val autoPlayVideos: Boolean = true,
    val confirmBeforeDelete: Boolean = true,
    /** Sends to the Android trash (recoverable for 30 days) instead of deleting permanently. */
    val useTrash: Boolean = true,
    /**
     * Shows items that exist only on OneDrive in Photos and Videos (like Samsung Gallery).
     * Enabled by default: device/OneDrive duplicates are merged by hash (MediaMerger). When
     * disabled, these items remain visible in their OneDrive albums, Favorites and the user's
     * albums.
     */
    val showCloudInPhotos: Boolean = true,
    /**
     * Automatically loads the OneDrive original on deep zoom on a metered network too (mobile
     * data, data saver). Disabled by default: the original is then offered ("Load original
     * (size)"); it is always automatic on unmetered Wi-Fi.
     */
    val loadOriginalsOnMobileData: Boolean = false,
    /**
     * Offline copies downloaded only on an unmetered network (Wi-Fi). Enabled by default;
     * "Download now" lifts the restriction for the current queue.
     */
    val offlineWifiOnly: Boolean = true,
    /**
     * The permission to show notifications has already been requested (on the first "Keep
     * offline"): it is not requested again afterwards. Downloads work without it.
     */
    val offlineNotificationsAsked: Boolean = false,
    /**
     * Cap of the temporary cache of OneDrive originals (deep zoom, share), one of the values of
     * [ORIGINALS_CACHE_CHOICES]. Offline copies are never counted in it.
     */
    val originalsCacheMaxBytes: Long = DEFAULT_ORIGINALS_CACHE_BYTES,
    /**
     * Cap of the disk cache of OneDrive thumbnails and previews (Coil), one of the values of
     * [THUMBNAIL_CACHE_CHOICES]. Applied when the cache is opened, hence at the next start.
     */
    val thumbnailCacheMaxBytes: Long = DEFAULT_THUMBNAIL_CACHE_BYTES,
    /**
     * Background OneDrive sync (WorkManager), in addition to the one on return to the
     * foreground. User decision: configurable, every 6 h by default.
     */
    val backgroundSyncInterval: BackgroundSyncInterval = BackgroundSyncInterval.EVERY_6_HOURS,
    /** Background sync only on an unmetered network (Wi-Fi). */
    val backgroundSyncWifiOnly: Boolean = false,
    /**
     * "Use OneDrive only" chosen on the permission screen: without access to the phone's
     * photos, the app opens on OneDrive instead of asking for the permission again. Cleared as
     * soon as access is granted (a later revocation of access asks again).
     */
    val oneDriveOnlyChosen: Boolean = false,
    /**
     * Caching of all of OneDrive kept up to date: after each sync, new items (or new versions)
     * are cached in the background. Disabled by default.
     */
    val precacheKeepUpdated: Boolean = false,
)

/** Caps offered for the OneDrive originals cache (bytes, decimal units like the display). */
val ORIGINALS_CACHE_CHOICES: List<Long> = listOf(500_000_000L, 1_000_000_000L, 2_000_000_000L, 5_000_000_000L)

/** Default cap of the originals cache (user decision: 1 GB). */
const val DEFAULT_ORIGINALS_CACHE_BYTES = 1_000_000_000L

/** Caps offered for the cache of OneDrive thumbnails and previews (decimal bytes). */
val THUMBNAIL_CACHE_CHOICES: List<Long> = listOf(100_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L)

/** Default cap of the thumbnail cache (≈ the 256 MiB of phases 3 and 4). */
const val DEFAULT_THUMBNAIL_CACHE_BYTES = 250_000_000L

/** State of the local media indexing. */
data class IndexState(
    val running: Boolean = false,
    val lastSyncAt: Long = 0L,
    val indexedCount: Int = 0,
    val lastError: String? = null,
)
