package fr.eva.cloudgallery.domain.model

/**
 * Space used by the app on the device, per tier (Storage page). Sizes measured on disk
 * (including partial files and files awaiting cleanup), in bytes.
 */
data class StorageUsage(
    /** Coil disk cache: OneDrive thumbnails and previews. */
    val thumbnailBytes: Long,
    /** Thumbnail cache cap in effect (opened at startup), null if unknown. */
    val thumbnailMaxBytes: Long?,
    /**
     * Caching of all of OneDrive: thumbnails and previews kept outside the cap above
     * ([precacheFiles] files).
     */
    val precacheBytes: Long,
    val precacheFiles: Int,
    /** Temporary OneDrive originals (deep zoom, share). */
    val originalBytes: Long,
    val originalCount: Int,
    /** Offline copies (pinned: never cleared with the cache). */
    val offlineBytes: Long,
    val offlineCount: Int,
    /** Space still free on the app's volume. */
    val freeBytes: Long,
) {
    /** Clearable cache (thumbnails, previews, caching of all of OneDrive and originals). */
    val cacheBytes: Long get() = thumbnailBytes + precacheBytes + originalBytes
}
